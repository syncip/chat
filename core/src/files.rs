//! Datei-Verschlüsselung in 64-KiB-Chunks (XChaCha20-Poly1305, STREAM-BE32).
//! Reihenfolge, Vollständigkeit und Ende der Datei sind authentifiziert.
use crate::error::{Error, Result};
use chacha20poly1305::{
    aead::stream::{DecryptorBE32, EncryptorBE32},
    KeyInit, XChaCha20Poly1305,
};
use rand::RngCore;

pub const CHUNK: usize = 64 * 1024;
pub const TAG: usize = 16;
pub const KEY_LEN: usize = 32;
pub const NONCE_LEN: usize = 19;

pub fn random_key() -> ([u8; KEY_LEN], [u8; NONCE_LEN]) {
    let mut k = [0u8; KEY_LEN];
    let mut n = [0u8; NONCE_LEN];
    rand::rngs::OsRng.fill_bytes(&mut k);
    rand::rngs::OsRng.fill_bytes(&mut n);
    (k, n)
}

/// Größe des Ciphertexts bei gegebener Klartextgröße.
pub fn encrypted_size(plain: usize) -> usize {
    let chunks = plain.div_ceil(CHUNK).max(1);
    plain + chunks * TAG
}

pub struct FileEncryptor(Option<EncryptorBE32<XChaCha20Poly1305>>);

impl FileEncryptor {
    pub fn new(key: &[u8], nonce: &[u8]) -> Result<Self> {
        if key.len() != KEY_LEN || nonce.len() != NONCE_LEN {
            return Err(Error::Invalid("key/nonce length"));
        }
        let c = XChaCha20Poly1305::new(key.into());
        Ok(Self(Some(EncryptorBE32::from_aead(c, nonce.into()))))
    }
    /// Chunk verschlüsseln. Alle Chunks außer dem letzten müssen exakt `CHUNK` Byte groß sein.
    pub fn next(&mut self, chunk: &[u8], last: bool) -> Result<Vec<u8>> {
        if !last && chunk.len() != CHUNK {
            return Err(Error::Invalid("chunk size"));
        }
        if chunk.len() > CHUNK {
            return Err(Error::Invalid("chunk size"));
        }
        let enc = self.0.as_mut().ok_or(Error::Invalid("finished"))?;
        if last {
            self.0
                .take()
                .unwrap()
                .encrypt_last(chunk)
                .map_err(|_| Error::Crypto("encrypt"))
        } else {
            enc.encrypt_next(chunk)
                .map_err(|_| Error::Crypto("encrypt"))
        }
    }
}

pub struct FileDecryptor(Option<DecryptorBE32<XChaCha20Poly1305>>);

impl FileDecryptor {
    pub fn new(key: &[u8], nonce: &[u8]) -> Result<Self> {
        if key.len() != KEY_LEN || nonce.len() != NONCE_LEN {
            return Err(Error::Invalid("key/nonce length"));
        }
        let c = XChaCha20Poly1305::new(key.into());
        Ok(Self(Some(DecryptorBE32::from_aead(c, nonce.into()))))
    }
    /// Chunk entschlüsseln (Ciphertext-Chunks sind `CHUNK + TAG` groß, der letzte kürzer).
    pub fn next(&mut self, chunk: &[u8], last: bool) -> Result<Vec<u8>> {
        if !last && chunk.len() != CHUNK + TAG {
            return Err(Error::Invalid("chunk size"));
        }
        let dec = self.0.as_mut().ok_or(Error::Invalid("finished"))?;
        if last {
            self.0
                .take()
                .unwrap()
                .decrypt_last(chunk)
                .map_err(|_| Error::Crypto("decrypt"))
        } else {
            dec.decrypt_next(chunk)
                .map_err(|_| Error::Crypto("decrypt"))
        }
    }
}

pub fn encrypt(key: &[u8], nonce: &[u8], data: &[u8]) -> Result<Vec<u8>> {
    let mut enc = FileEncryptor::new(key, nonce)?;
    let mut out = Vec::with_capacity(encrypted_size(data.len()));
    let mut chunks = data.chunks(CHUNK).peekable();
    if chunks.peek().is_none() {
        return enc.next(&[], true);
    }
    while let Some(c) = chunks.next() {
        let last = chunks.peek().is_none();
        out.extend(enc.next(c, last)?);
    }
    Ok(out)
}

pub fn decrypt(key: &[u8], nonce: &[u8], data: &[u8]) -> Result<Vec<u8>> {
    let mut dec = FileDecryptor::new(key, nonce)?;
    let mut out = Vec::with_capacity(data.len());
    let mut chunks = data.chunks(CHUNK + TAG).peekable();
    if chunks.peek().is_none() {
        return Err(Error::Invalid("empty"));
    }
    while let Some(c) = chunks.next() {
        let last = chunks.peek().is_none();
        out.extend(dec.next(c, last)?);
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn roundtrip_sizes() {
        let (k, n) = random_key();
        for len in [0usize, 1, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK + 5] {
            let d: Vec<u8> = (0..len).map(|i| i as u8).collect();
            let c = encrypt(&k, &n, &d).unwrap();
            assert_eq!(c.len(), encrypted_size(len));
            assert_eq!(decrypt(&k, &n, &c).unwrap(), d);
        }
    }
    #[test]
    fn detects_truncation_and_reorder() {
        let (k, n) = random_key();
        let d = vec![1u8; 3 * CHUNK];
        let c = encrypt(&k, &n, &d).unwrap();
        let cs = CHUNK + TAG;
        // letzten Chunk abschneiden
        assert!(decrypt(&k, &n, &c[..2 * cs]).is_err());
        // Chunks vertauschen
        let mut r = Vec::new();
        r.extend(&c[cs..2 * cs]);
        r.extend(&c[..cs]);
        r.extend(&c[2 * cs..]);
        assert!(decrypt(&k, &n, &r).is_err());
        // Bitflip
        let mut f = c.clone();
        f[10] ^= 1;
        assert!(decrypt(&k, &n, &f).is_err());
    }
}
