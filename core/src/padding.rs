//! Auffüllen von Klartexten in Größenklassen, damit Nachrichtenlängen nicht verraten werden.
use crate::error::{Error, Result};

const MIN: usize = 256;

pub fn pad(data: &[u8]) -> Vec<u8> {
    let total = (data.len() + 4).max(MIN).next_power_of_two();
    let mut out = Vec::with_capacity(total);
    out.extend_from_slice(&(data.len() as u32).to_be_bytes());
    out.extend_from_slice(data);
    out.resize(total, 0);
    out
}

pub fn unpad(data: &[u8]) -> Result<Vec<u8>> {
    if data.len() < 4 {
        return Err(Error::Invalid("padding"));
    }
    let n = u32::from_be_bytes([data[0], data[1], data[2], data[3]]) as usize;
    data.get(4..4 + n)
        .map(|s| s.to_vec())
        .ok_or(Error::Invalid("padding"))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn roundtrip_and_buckets() {
        for n in [0usize, 1, 100, 252, 253, 1000, 70_000] {
            let d = vec![7u8; n];
            let p = pad(&d);
            assert!(p.len().is_power_of_two() && p.len() >= MIN);
            assert_eq!(unpad(&p).unwrap(), d);
        }
        assert_eq!(pad(b"a").len(), pad(b"bbbbbbbb").len());
    }
}
