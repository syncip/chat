//! Nachrichtenformat (Payload innerhalb der MLS-Anwendungsnachricht).
use crate::error::{Error, Result};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Part {
    /// Markdown-Subset, niemals als HTML interpretieren.
    Text {
        body: String,
    },
    Code {
        lang: String,
        body: String,
    },
    Quote {
        reference: String,
        snippet: String,
    },
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

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReceiptKind {
    Delivered,
    Read,
}

/// Postfach-Capability eines Mitglieds: Server, Postfach-ID, Einwurf-Token, Umschlag-Schlüssel (base64).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CapEntry {
    pub address: String,
    /// Gerät, dem dieses Postfach gehört (jedes Gerät hat eigene Unterhaltungs-Postfächer).
    #[serde(default)]
    pub device: String,
    pub domain: String,
    pub mailbox_id: String,
    pub send_token: String,
    pub key: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Content {
    /// `once`: Einmal-Nachricht (nach dem ersten Anzeigen beim Empfänger gelöscht; nur in 1:1-Chats).
    Message {
        parts: Vec<Part>,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        once: bool,
    },
    Reaction {
        reference: String,
        emoji: String,
    },
    Edit {
        reference: String,
        parts: Vec<Part>,
    },
    Delete {
        reference: String,
    },
    /// Zustell-/Lesebestätigung (nur 1:1-Chats). `receipt`: `delivered` oder `read`.
    Receipt {
        receipt: ReceiptKind,
        references: Vec<String>,
    },
    /// Ablaufzeit in Sekunden für Folge-Nachrichten (0 = aus).
    Disappear {
        seconds: u64,
    },
    /// „Hier erreichst du uns“: Empfangs-Postfächer (Capabilities) von Mitgliedern dieser Unterhaltung.
    Directory {
        entries: Vec<CapEntry>,
    },
    /// Gruppenname.
    GroupName {
        name: String,
    },
    /// Gruppenbild (data-URL, klein; `null` = entfernt).
    GroupAvatar {
        avatar: Option<String>,
    },
    /// Eigenes Profilbild des Absenders (data-URL, klein; `null` = entfernt).
    Profile {
        avatar: Option<String>,
    },
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

pub const MAX_PARTS: usize = 256;

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
            Content::Message { parts, .. } | Content::Edit { parts, .. } => parts.len(),
            Content::Receipt { references, .. } => references.len(),
            Content::Directory { entries } => entries.len(),
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
                once: false,
                parts: vec![
                    Part::Quote {
                        reference: "a".into(),
                        snippet: "hi".into(),
                    },
                    Part::Code {
                        lang: "rust".into(),
                        body: "fn main(){}".into(),
                    },
                ],
            },
        };
        assert_eq!(Envelope::decode(&e.encode().unwrap()).unwrap(), e);
        assert!(Envelope::decode(b"{}").is_err());
        // `once` wird nur geschrieben, wenn gesetzt (kompatibel zu älteren Clients)
        assert!(!String::from_utf8(e.encode().unwrap())
            .unwrap()
            .contains("once"));
        let o = Envelope {
            content: Content::Message {
                parts: vec![],
                once: true,
            },
            ..e.clone()
        };
        assert!(String::from_utf8(o.encode().unwrap())
            .unwrap()
            .contains("\"once\":true"));
        let r = Envelope {
            content: Content::Receipt {
                receipt: ReceiptKind::Read,
                references: vec!["a".into()],
            },
            ..e
        };
        let enc = String::from_utf8(r.encode().unwrap()).unwrap();
        assert!(
            enc.contains("\"kind\":\"receipt\"") && enc.contains("\"receipt\":\"read\""),
            "{enc}"
        );
        assert_eq!(Envelope::decode(enc.as_bytes()).unwrap(), r);
    }
}
