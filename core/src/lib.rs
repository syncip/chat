//! Krypto-Kern des Chats: MLS, Datei-Verschlüsselung, lokale Vaults, Nachrichtenformat.
//! Wird vom Web-Client als WASM und später von nativen Clients (Windows/Android/...) genutzt.
pub mod envelope;
pub mod error;
pub mod files;
pub mod identity;
pub mod message;
pub mod mls;
pub mod padding;
pub mod vault;

#[cfg(target_arch = "wasm32")]
pub mod wasm;
