//! Passphrase-Verschlüsselung für lokale Zustände und Schlüssel-Backups (Argon2id + XChaCha20-Poly1305).
use crate::error::{Error, Result};
use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::{aead::Aead, KeyInit, XChaCha20Poly1305, XNonce};
use rand::RngCore;

const MAGIC: &[u8; 4] = b"CHV1";

#[derive(Clone, Copy)]
pub struct KdfParams {
    pub m_kib: u32,
    pub t: u32,
}

/// Standard: 64 MiB, 3 Iterationen, 1 Lane (Browser-tauglich).
pub const DEFAULT_KDF: KdfParams = KdfParams { m_kib: 64 * 1024, t: 3 };

fn derive(pass: &[u8], salt: &[u8], p: KdfParams) -> Result<[u8; 32]> {
    let params = Params::new(p.m_kib, p.t, 1, Some(32)).map_err(|_| Error::Crypto("kdf params"))?;
    let mut key = [0u8; 32];
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(pass, salt, &mut key)
        .map_err(|_| Error::Crypto("kdf"))?;
    Ok(key)
}

/// Abgeleiteter Schlüssel für wiederholtes Speichern: Argon2 läuft nur einmal beim Entsperren.
pub struct VaultSession {
    key: [u8; 32],
    salt: [u8; 16],
    kdf: KdfParams,
}

impl VaultSession {
    pub fn create(pass: &str, kdf: KdfParams) -> Result<Self> {
        let mut salt = [0u8; 16];
        rand::rngs::OsRng.fill_bytes(&mut salt);
        let key = derive(pass.as_bytes(), &salt, kdf)?;
        Ok(Self { key, salt, kdf })
    }

    /// Entsperrt einen bestehenden Vault; liefert Sitzung und Klartext.
    pub fn open(pass: &str, blob: &[u8]) -> Result<(Self, Vec<u8>)> {
        let (kdf, salt) = parse_header(blob)?;
        let key = derive(pass.as_bytes(), &salt, kdf)?;
        let pt = open_with_key(&key, blob)?;
        Ok((Self { key, salt, kdf }, pt))
    }

    pub fn seal(&self, plaintext: &[u8]) -> Result<Vec<u8>> {
        let mut nonce = [0u8; 24];
        rand::rngs::OsRng.fill_bytes(&mut nonce);
        let mut header = Vec::new();
        header.extend_from_slice(MAGIC);
        header.extend_from_slice(&self.kdf.m_kib.to_be_bytes());
        header.extend_from_slice(&self.kdf.t.to_be_bytes());
        header.extend_from_slice(&self.salt);
        header.extend_from_slice(&nonce);
        let ct = XChaCha20Poly1305::new((&self.key).into())
            .encrypt(
                XNonce::from_slice(&nonce),
                chacha20poly1305::aead::Payload { msg: plaintext, aad: &header },
            )
            .map_err(|_| Error::Crypto("encrypt"))?;
        header.extend_from_slice(&ct);
        Ok(header)
    }
}

const H: usize = 4 + 4 + 4 + 16 + 24;

fn parse_header(blob: &[u8]) -> Result<(KdfParams, [u8; 16])> {
    if blob.len() < H + 16 || &blob[..4] != MAGIC {
        return Err(Error::Invalid("vault"));
    }
    let m_kib = u32::from_be_bytes(blob[4..8].try_into().unwrap());
    let t = u32::from_be_bytes(blob[8..12].try_into().unwrap());
    // Obergrenzen gegen manipulierte Header (DoS).
    if m_kib > 1024 * 1024 || t > 20 || m_kib < 8 || t < 1 {
        return Err(Error::Invalid("vault params"));
    }
    Ok((KdfParams { m_kib, t }, blob[12..28].try_into().unwrap()))
}

fn open_with_key(key: &[u8; 32], blob: &[u8]) -> Result<Vec<u8>> {
    XChaCha20Poly1305::new(key.into())
        .decrypt(
            XNonce::from_slice(&blob[28..H]),
            chacha20poly1305::aead::Payload { msg: &blob[H..], aad: &blob[..H] },
        )
        .map_err(|_| Error::Crypto("wrong passphrase or corrupted data"))
}

pub fn seal(pass: &str, plaintext: &[u8], kdf: KdfParams) -> Result<Vec<u8>> {
    let mut salt = [0u8; 16];
    let mut nonce = [0u8; 24];
    rand::rngs::OsRng.fill_bytes(&mut salt);
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let key = derive(pass.as_bytes(), &salt, kdf)?;
    let mut header = Vec::new();
    header.extend_from_slice(MAGIC);
    header.extend_from_slice(&kdf.m_kib.to_be_bytes());
    header.extend_from_slice(&kdf.t.to_be_bytes());
    header.extend_from_slice(&salt);
    header.extend_from_slice(&nonce);
    let ct = XChaCha20Poly1305::new((&key).into())
        .encrypt(
            XNonce::from_slice(&nonce),
            chacha20poly1305::aead::Payload { msg: plaintext, aad: &header },
        )
        .map_err(|_| Error::Crypto("encrypt"))?;
    header.extend_from_slice(&ct);
    Ok(header)
}

pub fn open(pass: &str, blob: &[u8]) -> Result<Vec<u8>> {
    VaultSession::open(pass, blob).map(|(_, pt)| pt)
}

#[cfg(test)]
mod tests {
    use super::*;
    const FAST: KdfParams = KdfParams { m_kib: 8, t: 1 };
    #[test]
    fn roundtrip() {
        let b = seal("pw", b"secret", FAST).unwrap();
        assert_eq!(open("pw", &b).unwrap(), b"secret");
        assert!(open("other", &b).is_err());
        let mut c = b.clone();
        let n = c.len() - 1;
        c[n] ^= 1;
        assert!(open("pw", &c).is_err());
        // Header-Manipulation (AAD) wird erkannt.
        let mut d = b.clone();
        d[11] ^= 1;
        assert!(open("pw", &d).is_err());
    }

    #[test]
    fn session_reuses_key() {
        let s = VaultSession::create("pw", FAST).unwrap();
        let a = s.seal(b"one").unwrap();
        let b = s.seal(b"one").unwrap();
        assert_ne!(a, b); // frische Nonce
        let (s2, pt) = VaultSession::open("pw", &a).unwrap();
        assert_eq!(pt, b"one");
        assert_eq!(open("pw", &s2.seal(b"two").unwrap()).unwrap(), b"two");
        assert!(VaultSession::open("bad", &b).is_err());
    }
}
