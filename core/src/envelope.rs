//! Äußere Umschlag-Verschlüsselung für Postfach-Blobs.
//!
//! MLS-Nachrichten enthalten die Gruppen-ID im Klartext-Header. Damit der Server nicht erkennt, welche
//! Nachrichten zur selben Gruppe gehören, wird jeder Blob zusätzlich mit einem Postfach-Schlüssel
//! verschlüsselt (den der Empfänger zusammen mit der Postfach-Capability weitergibt) und auf eine
//! Größenklasse aufgefüllt. Der Server sieht nur zufällig wirkende Bytes gleicher Größenklassen.
use crate::error::{Error, Result};
use crate::padding::{pad, unpad};
use chacha20poly1305::{aead::Aead, KeyInit, XChaCha20Poly1305, XNonce};
use rand::RngCore;

pub const KIND_WELCOME: u8 = 1;
pub const KIND_MLS: u8 = 2;
const NONCE: usize = 24;

pub fn random_key() -> [u8; 32] {
    let mut k = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut k);
    k
}

pub fn seal(key: &[u8], kind: u8, gid: &[u8], payload: &[u8]) -> Result<Vec<u8>> {
    if key.len() != 32 || gid.len() > u16::MAX as usize {
        return Err(Error::Invalid("envelope params"));
    }
    let mut inner = Vec::with_capacity(3 + gid.len() + payload.len());
    inner.push(kind);
    inner.extend_from_slice(&(gid.len() as u16).to_be_bytes());
    inner.extend_from_slice(gid);
    inner.extend_from_slice(payload);
    let mut nonce = [0u8; NONCE];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let ct = XChaCha20Poly1305::new(key.into())
        .encrypt(XNonce::from_slice(&nonce), pad(&inner).as_slice())
        .map_err(|_| Error::Crypto("encrypt"))?;
    let mut out = nonce.to_vec();
    out.extend(ct);
    Ok(out)
}

/// Liefert `(kind, group_id, payload)`.
pub fn open(key: &[u8], blob: &[u8]) -> Result<(u8, Vec<u8>, Vec<u8>)> {
    if key.len() != 32 || blob.len() < NONCE + 16 {
        return Err(Error::Invalid("envelope"));
    }
    let pt = XChaCha20Poly1305::new(key.into())
        .decrypt(XNonce::from_slice(&blob[..NONCE]), &blob[NONCE..])
        .map_err(|_| Error::Crypto("decrypt"))?;
    let inner = unpad(&pt)?;
    if inner.len() < 3 {
        return Err(Error::Invalid("envelope"));
    }
    let n = u16::from_be_bytes([inner[1], inner[2]]) as usize;
    let gid = inner.get(3..3 + n).ok_or(Error::Invalid("envelope"))?;
    Ok((inner[0], gid.to_vec(), inner[3 + n..].to_vec()))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn roundtrip_and_uniform_size() {
        let k = random_key();
        let a = seal(&k, KIND_MLS, b"gid", b"short").unwrap();
        let b = seal(&k, KIND_MLS, b"gid", b"a bit longer payload").unwrap();
        assert_eq!(a.len(), b.len());
        let (kind, gid, p) = open(&k, &a).unwrap();
        assert_eq!((kind, gid.as_slice(), p.as_slice()), (KIND_MLS, &b"gid"[..], &b"short"[..]));
        assert!(open(&random_key(), &a).is_err());
        let mut t = a.clone();
        t[30] ^= 1;
        assert!(open(&k, &t).is_err());
    }
}
