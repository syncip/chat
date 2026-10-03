//! Identität: Adresse + Ed25519-Identity-Key. Der IK ist zugleich der MLS-Signaturschlüssel.
use crate::error::{Error, Result};
use sha2::{Digest, Sha256};

/// Validiert `name@domain` (kleingeschrieben, einfache Zeichen).
pub fn parse_address(addr: &str) -> Result<(&str, &str)> {
    let (name, domain) = addr.split_once('@').ok_or(Error::Invalid("address"))?;
    let ok = |s: &str, extra: &str| {
        !s.is_empty()
            && s.len() <= 253
            && s.chars()
                .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || extra.contains(c))
    };
    if !ok(name, "._-") || !ok(domain, ".-:") {
        return Err(Error::Invalid("address"));
    }
    Ok((name, domain))
}

/// SHA-256 des Public Keys.
pub fn fingerprint_bytes(ik_pub: &[u8]) -> [u8; 32] {
    Sha256::digest(ik_pub).into()
}

/// Safety Number: 12 Gruppen à 5 Ziffern aus dem Fingerprint (Anzeige/Vergleich).
pub fn safety_number(ik_pub: &[u8]) -> String {
    let h = fingerprint_bytes(ik_pub);
    let groups: Vec<String> = h
        .chunks(2)
        .take(12)
        .map(|c| {
            format!(
                "{:05}",
                u32::from(u16::from_be_bytes([c[0], c[1]])) % 100_000
            )
        })
        .collect();
    groups.join(" ")
}

/// Kombinierte Safety Number zweier Nutzer (reihenfolgeunabhängig).
pub fn pair_safety_number(a: &[u8], b: &[u8]) -> String {
    let (x, y) = if a <= b { (a, b) } else { (b, a) };
    let mut buf = Vec::new();
    buf.extend_from_slice(x);
    buf.extend_from_slice(y);
    safety_number(&buf)
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn unhex(s: &str) -> Result<Vec<u8>> {
    if !s.len().is_multiple_of(2) || !s.bytes().all(|c| c.is_ascii_hexdigit()) {
        return Err(Error::Invalid("hex"));
    }
    (0..s.len() / 2)
        .map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).map_err(|_| Error::Invalid("hex")))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn address() {
        assert!(parse_address("alice@chat.example.org").is_ok());
        assert!(parse_address("Alice@x").is_err());
        assert!(parse_address("alice").is_err());
        assert!(parse_address("@x").is_err());
    }
    #[test]
    fn pair_symmetric() {
        assert_eq!(
            pair_safety_number(b"a", b"b"),
            pair_safety_number(b"b", b"a")
        );
    }
}
