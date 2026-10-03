//! Nachrichtenformat (Payload innerhalb der MLS-Anwendungsnachricht).
use crate::error::{Error, Result};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Part {
    /// Markdown-Subset, niemals als HTML interpretieren.
    Text { body: String },
    Code { lang: String, body: String },
    Quote { reference: String, snippet: String },
    File {
        blob_id: String,
        blob_server: String,
        key: String,
        nonce: String,
        name: String,
        mime: String,
        size: u64,
        sha256: String,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Content {
    Message { parts: Vec<Part> },
    Reaction { reference: String, emoji: String },
    Edit { reference: String, parts: Vec<Part> },
    Delete { reference: String },
    Read { reference: String },
    /// Ablaufzeit in Sekunden für Folge-Nachrichten (0 = aus).
    Disappear { seconds: u64 },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Envelope {
    pub v: u8,
    /// Zufällige Nachrichten-ID (vom Sender vergeben).
    pub id: String,
    /// Sendezeit in ms seit Epoche (vom Sender; unauthentifiziert gegenüber Servern, aber im E2E-Payload).
    pub ts: u64,
    pub content: Content,
}

pub const MAX_PARTS: usize = 64;

impl Envelope {
    pub fn encode(&self) -> Result<Vec<u8>> {
        serde_json::to_vec(self).map_err(|_| Error::Invalid("encode"))
    }
    pub fn decode(b: &[u8]) -> Result<Self> {
        let e: Envelope = serde_json::from_slice(b).map_err(|_| Error::Invalid("decode"))?;
        if e.v != 1 {
            return Err(Error::Invalid("version"));
        }
        let n = match &e.content {
            Content::Message { parts } | Content::Edit { parts, .. } => parts.len(),
            _ => 0,
        };
        if n > MAX_PARTS {
            return Err(Error::Invalid("too many parts"));
        }
        Ok(e)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn roundtrip() {
        let e = Envelope {
            v: 1,
            id: "x".into(),
            ts: 1,
            content: Content::Message {
                parts: vec![
                    Part::Quote { reference: "a".into(), snippet: "hi".into() },
                    Part::Code { lang: "rust".into(), body: "fn main(){}".into() },
                ],
            },
        };
        assert_eq!(Envelope::decode(&e.encode().unwrap()).unwrap(), e);
        assert!(Envelope::decode(b"{}").is_err());
    }
}
