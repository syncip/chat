//! Geräte-Identität (Multi-Device, siehe docs/MULTIDEVICE.md).
//!
//! Konto-Identität = **AIK** (Ed25519). Jedes Gerät hat einen eigenen **DSK** (MLS-Signaturschlüssel). Der AIK beglaubigt den DSK
//! mit einem Zertifikat, das im MLS-Credential steht und von jedem Client geprüft wird.
use crate::error::{Error, Result};
use crate::identity::{hex, parse_address, unhex};
use ed25519_dalek::{Signature, VerifyingKey};
use hkdf::Hkdf;
use serde::{Deserialize, Serialize};
use sha2::Sha256;

/// Geprüfte Identität eines MLS-Blatts.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceIdentity {
    pub address: String,
    /// Öffentlicher Konto-Schlüssel (32 Byte).
    pub aik: Vec<u8>,
    /// 16 Hex-Zeichen.
    pub device_id: String,
}

#[derive(Serialize, Deserialize)]
struct Wire {
    a: String,
    k: String,
    d: String,
    s: String,
}

pub fn cert_message(address: &str, device_id: &str, dpk: &[u8]) -> Vec<u8> {
    format!("CHAT-DEVICE-V1\n{address}\n{device_id}\n{}", hex(dpk)).into_bytes()
}

pub fn valid_device_id(id: &str) -> bool {
    id.len() == 16
        && id
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}

/// Credential-Bytes (JSON) für das BasicCredential.
pub fn encode_credential(address: &str, aik: &[u8], device_id: &str, cert_sig: &[u8]) -> Vec<u8> {
    serde_json::to_vec(&Wire {
        a: address.into(),
        k: hex(aik),
        d: device_id.into(),
        s: hex(cert_sig),
    })
    .expect("json")
}

fn verify_sig(aik: &[u8], msg: &[u8], sig: &[u8]) -> Result<()> {
    let key: [u8; 32] = aik.try_into().map_err(|_| Error::Invalid("aik"))?;
    let key = VerifyingKey::from_bytes(&key).map_err(|_| Error::Invalid("aik"))?;
    let sig: [u8; 64] = sig.try_into().map_err(|_| Error::Invalid("cert"))?;
    key.verify_strict(msg, &Signature::from_bytes(&sig))
        .map_err(|_| Error::Crypto("device certificate invalid"))
}

/// Ed25519-Signatur prüfen (z. B. Kanal-Beiträge, signiert mit dem Konto-Schlüssel).
pub fn verify_ed25519(pk: &[u8], msg: &[u8], sig: &[u8]) -> bool {
    verify_sig(pk, msg, sig).is_ok()
}

/// Prüft Credential-Bytes gegen den Signaturschlüssel des Blatts (`dpk`).
pub fn verify_credential(bytes: &[u8], dpk: &[u8]) -> Result<DeviceIdentity> {
    let w: Wire = serde_json::from_slice(bytes).map_err(|_| Error::Invalid("credential"))?;
    parse_address(&w.a)?;
    if !valid_device_id(&w.d) {
        return Err(Error::Invalid("device id"));
    }
    let aik = unhex(&w.k)?;
    let sig = unhex(&w.s)?;
    verify_sig(&aik, &cert_message(&w.a, &w.d, dpk), &sig)?;
    Ok(DeviceIdentity {
        address: w.a,
        aik,
        device_id: w.d,
    })
}

/// Ohne Prüfung der Signatur lesen (nur Adresse/Gerät eines bereits validierten Credentials).
pub fn peek_credential(bytes: &[u8]) -> Option<(String, String)> {
    let w: Wire = serde_json::from_slice(bytes).ok()?;
    Some((w.a, w.d))
}

/// Postfach nur für ein Gerät, **deterministisch aus dem AIK abgeleitet**: jedes Gerät des Kontos kann es berechnen,
/// der Server sieht nur den Hash des Tokens.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Inbox {
    pub mailbox_id: String,
    pub token: String,
    pub key: [u8; 32],
}

fn base32_lower(b: &[u8]) -> String {
    const A: &[u8; 32] = b"abcdefghijklmnopqrstuvwxyz234567";
    let mut out = String::new();
    let (mut buf, mut bits) = (0u32, 0u32);
    for &x in b {
        buf = (buf << 8) | x as u32;
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            out.push(A[((buf >> bits) & 31) as usize] as char);
        }
    }
    if bits > 0 {
        out.push(A[((buf << (5 - bits)) & 31) as usize] as char);
    }
    out
}

/// `account_secret`: die serialisierten AIK-Schlüsselbytes (enthalten den privaten Schlüssel).
pub fn derive_inbox(account_secret: &[u8], device_id: &str) -> Result<Inbox> {
    if !valid_device_id(device_id) {
        return Err(Error::Invalid("device id"));
    }
    let hk = Hkdf::<Sha256>::new(Some(b"chat-inbox-v1"), account_secret);
    let mut okm = [0u8; 16 + 32 + 32];
    hk.expand(device_id.as_bytes(), &mut okm)
        .map_err(|_| Error::Crypto("hkdf"))?;
    let mut key = [0u8; 32];
    key.copy_from_slice(&okm[48..]);
    Ok(Inbox {
        mailbox_id: base32_lower(&okm[..16]),
        token: hex(&okm[16..48]),
        key,
    })
}

/// Schlüssel für den verschlüsselten Konto-Sync-Blob (aus dem AIK abgeleitet; alle Geräte des Kontos kommen auf denselben).
pub fn derive_sync_key(account_secret: &[u8]) -> Result<[u8; 32]> {
    let hk = Hkdf::<Sha256>::new(Some(b"chat-sync-v1"), account_secret);
    let mut key = [0u8; 32];
    hk.expand(b"account-sync", &mut key)
        .map_err(|_| Error::Crypto("hkdf"))?;
    Ok(key)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn credential_verification() {
        use ed25519_dalek::{Signer, SigningKey};
        let aik = SigningKey::from_bytes(&[7u8; 32]);
        let dpk = [9u8; 32];
        let dev = "0123456789abcdef";
        let sig = aik.sign(&cert_message("a@x.y", dev, &dpk));
        let cred = encode_credential(
            "a@x.y",
            aik.verifying_key().as_bytes(),
            dev,
            &sig.to_bytes(),
        );
        let id = verify_credential(&cred, &dpk).unwrap();
        assert_eq!((id.address.as_str(), id.device_id.as_str()), ("a@x.y", dev));
        // falscher Geräteschlüssel, fremde Adresse, fremdes Gerät, kaputte Signatur
        assert!(verify_credential(&cred, &[8u8; 32]).is_err());
        let other_addr = encode_credential(
            "b@x.y",
            aik.verifying_key().as_bytes(),
            dev,
            &sig.to_bytes(),
        );
        assert!(verify_credential(&other_addr, &dpk).is_err());
        let other_dev = encode_credential(
            "a@x.y",
            aik.verifying_key().as_bytes(),
            "ffffffffffffffff",
            &sig.to_bytes(),
        );
        assert!(verify_credential(&other_dev, &dpk).is_err());
        let mut bad = sig.to_bytes();
        bad[0] ^= 1;
        assert!(verify_credential(
            &encode_credential("a@x.y", aik.verifying_key().as_bytes(), dev, &bad),
            &dpk
        )
        .is_err());
        // Zertifikat eines anderen Kontos-Schlüssels
        let evil = SigningKey::from_bytes(&[1u8; 32]);
        let forged = evil.sign(&cert_message("a@x.y", dev, &dpk));
        assert!(verify_credential(
            &encode_credential(
                "a@x.y",
                aik.verifying_key().as_bytes(),
                dev,
                &forged.to_bytes()
            ),
            &dpk
        )
        .is_err());
    }

    #[test]
    fn sync_key_is_deterministic_and_secret_bound() {
        let a = derive_sync_key(b"secret").unwrap();
        assert_eq!(a, derive_sync_key(b"secret").unwrap());
        assert_ne!(a, derive_sync_key(b"other").unwrap());
    }

    #[test]
    fn inbox_is_deterministic_and_well_formed() {
        let a = derive_inbox(b"secret", "0123456789abcdef").unwrap();
        assert_eq!(a, derive_inbox(b"secret", "0123456789abcdef").unwrap());
        assert_ne!(
            a.mailbox_id,
            derive_inbox(b"secret", "0123456789abcde0")
                .unwrap()
                .mailbox_id
        );
        assert_ne!(
            a.mailbox_id,
            derive_inbox(b"other", "0123456789abcdef")
                .unwrap()
                .mailbox_id
        );
        assert_eq!(a.mailbox_id.len(), 26);
        assert!(a
            .mailbox_id
            .bytes()
            .all(|c| c.is_ascii_lowercase() || (b'2'..=b'7').contains(&c)));
        assert!(derive_inbox(b"s", "xyz").is_err());
    }
}
