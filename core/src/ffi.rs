//! UniFFI-Schnittstelle (Kotlin/Android; später Swift). Spiegelt `wasm.rs`: Binärdaten als `Vec<u8>`, strukturierte Daten als JSON.
use crate::{envelope, error::Error, files, identity, message::Envelope, mls, vault};
use std::sync::{Arc, Mutex};

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiError {
    #[error("{reason}")]
    Failed { reason: String },
}

impl From<Error> for FfiError {
    fn from(e: Error) -> Self {
        FfiError::Failed { reason: e.to_string() }
    }
}

type R<T> = Result<T, FfiError>;

fn bad(m: &str) -> FfiError {
    FfiError::Failed { reason: m.to_string() }
}

#[derive(uniffi::Record)]
pub struct AddResult {
    pub commit: Vec<u8>,
    pub welcome: Vec<u8>,
}

#[derive(uniffi::Record)]
pub struct OpenedEnvelope {
    pub kind: u8,
    pub group_id: Vec<u8>,
    pub sender_device: Vec<u8>,
    pub payload: Vec<u8>,
}

#[derive(uniffi::Record)]
pub struct OpenedVault {
    pub vault: Arc<VaultSession>,
    pub plaintext: Vec<u8>,
}

/// MLS-Client (Identität + alle Gruppen). Thread-sicher durch internen Mutex.
#[derive(uniffi::Object)]
pub struct MlsClient {
    inner: Mutex<mls::Client>,
}

impl MlsClient {
    fn with<T>(&self, f: impl FnOnce(&mut mls::Client) -> crate::error::Result<T>) -> R<T> {
        let mut g = self.inner.lock().map_err(|_| bad("lock"))?;
        Ok(f(&mut g)?)
    }
    fn wrap(c: mls::Client) -> Arc<Self> {
        Arc::new(Self { inner: Mutex::new(c) })
    }
}

#[uniffi::export]
impl MlsClient {
    #[uniffi::constructor]
    pub fn create(address: String) -> R<Arc<Self>> {
        Ok(Self::wrap(mls::Client::new(&address)?))
    }
    /// Neues Gerät für ein bestehendes Konto (AIK-Schlüsselpaar aus der Backup-Datei).
    #[uniffi::constructor]
    pub fn link_device(address: String, keypair: Vec<u8>) -> R<Arc<Self>> {
        Ok(Self::wrap(mls::Client::link_device(&address, &keypair)?))
    }
    #[uniffi::constructor]
    pub fn import_state(data: Vec<u8>) -> R<Arc<Self>> {
        Ok(Self::wrap(mls::Client::import_state(&data)?))
    }
    pub fn export_state(&self) -> R<Vec<u8>> {
        self.with(|c| c.export_state())
    }
    pub fn export_identity(&self) -> R<Vec<u8>> {
        self.with(|c| c.export_identity())
    }
    pub fn address(&self) -> R<String> {
        self.with(|c| Ok(c.address().to_string()))
    }
    pub fn identity_public(&self) -> R<Vec<u8>> {
        self.with(|c| Ok(c.identity_public()))
    }
    pub fn sign(&self, data: Vec<u8>) -> R<Vec<u8>> {
        self.with(|c| c.sign(&data))
    }
    /// Signatur mit dem Konto-Schlüssel (Registrierung, Gerät hinzufügen).
    pub fn sign_account(&self, data: Vec<u8>) -> R<Vec<u8>> {
        self.with(|c| c.sign_account(&data))
    }
    pub fn device_id(&self) -> R<String> {
        self.with(|c| Ok(c.device_id().to_string()))
    }
    pub fn device_public(&self) -> R<Vec<u8>> {
        self.with(|c| Ok(c.device_public()))
    }
    pub fn device_cert(&self) -> R<Vec<u8>> {
        self.with(|c| Ok(c.device_cert()))
    }
    /// JSON `{mailbox_id, token, key(hex)}`: Geräte-Postfach, aus dem AIK abgeleitet.
    pub fn device_inbox(&self, device_id: String) -> R<String> {
        self.with(|c| {
            let i = c.device_inbox(&device_id)?;
            Ok(serde_json::json!({"mailbox_id": i.mailbox_id, "token": i.token, "key": hex(&i.key)}).to_string())
        })
    }
    pub fn key_packages(&self, n: u32, last_resort: bool) -> R<Vec<Vec<u8>>> {
        self.with(|c| c.key_packages(n as usize, last_resort))
    }
    /// JSON `{"address":..., "identity":"AIK hex", "device":...}`; prüft das Gerätezertifikat.
    pub fn key_package_identity(&self, kp: Vec<u8>) -> R<String> {
        self.with(|c| {
            let d = c.key_package_identity(&kp)?;
            Ok(serde_json::json!({"address": d.address, "identity": hex(&d.aik), "device": d.device_id}).to_string())
        })
    }
    pub fn create_group(&self) -> R<Vec<u8>> {
        self.with(|c| c.create_group())
    }
    pub fn add_members(&self, gid: Vec<u8>, key_packages: Vec<Vec<u8>>) -> R<AddResult> {
        self.with(|c| {
            let r = c.add_members(&gid, &key_packages)?;
            Ok(AddResult { commit: r.commit, welcome: r.welcome })
        })
    }
    pub fn remove_members(&self, gid: Vec<u8>, addresses: Vec<String>) -> R<Vec<u8>> {
        self.with(|c| c.remove_members(&gid, &addresses))
    }
    /// Einzelne Geräte entfernen: JSON `[{"address":…,"device":…}]`.
    pub fn remove_devices(&self, gid: Vec<u8>, devices_json: String) -> R<Vec<u8>> {
        #[derive(serde::Deserialize)]
        struct D {
            address: String,
            device: String,
        }
        let d: Vec<D> = serde_json::from_str(&devices_json).map_err(|_| bad("devices"))?;
        let pairs: Vec<(String, String)> = d.into_iter().map(|x| (x.address, x.device)).collect();
        self.with(|c| c.remove_devices(&gid, &pairs))
    }
    pub fn update_keys(&self, gid: Vec<u8>) -> R<Vec<u8>> {
        self.with(|c| c.update_keys(&gid))
    }
    pub fn join(&self, welcome: Vec<u8>) -> R<Vec<u8>> {
        self.with(|c| c.join(&welcome))
    }
    pub fn delete_group(&self, gid: Vec<u8>) -> R<()> {
        self.with(|c| c.delete_group(&gid))
    }
    /// Envelope-JSON validieren, auffüllen und verschlüsseln.
    pub fn encrypt_envelope(&self, gid: Vec<u8>, envelope_json: String) -> R<Vec<u8>> {
        let env: Envelope = serde_json::from_str(&envelope_json).map_err(|_| bad("envelope"))?;
        self.with(|c| c.encrypt(&gid, &env.encode()?))
    }
    /// JSON: `{kind:"application",sender,envelope}` | `{kind:"commit",sender,removedSelf}` | `{kind:"proposal"}`.
    pub fn process(&self, gid: Vec<u8>, message: Vec<u8>) -> R<String> {
        self.with(|c| {
            Ok(match c.process(&gid, &message)? {
                mls::Received::Application { sender, sender_device, plaintext } => {
                    let env = Envelope::decode(&plaintext)?;
                    serde_json::json!({"kind":"application","sender":sender,"senderDevice":sender_device,"envelope":env})
                }
                mls::Received::Commit { sender, removed_self } => {
                    serde_json::json!({"kind":"commit","sender":sender,"removedSelf":removed_self})
                }
                mls::Received::Proposal => serde_json::json!({"kind":"proposal"}),
            }
            .to_string())
        })
    }
    /// JSON-Array `[{address, identity(AIK hex), device}]`, ein Eintrag je Gerät.
    pub fn members(&self, gid: Vec<u8>) -> R<String> {
        self.with(|c| {
            let v: Vec<_> = c
                .members(&gid)?
                .into_iter()
                .map(|m| serde_json::json!({"address": m.address, "identity": hex(&m.aik), "device": m.device_id}))
                .collect();
            Ok(serde_json::Value::Array(v).to_string())
        })
    }
}

/// Entsperrter Vault (Argon2 nur einmal pro Sitzung).
#[derive(uniffi::Object)]
pub struct VaultSession {
    inner: vault::VaultSession,
}

#[uniffi::export]
impl VaultSession {
    #[uniffi::constructor]
    pub fn create(pass: String) -> R<Arc<Self>> {
        Ok(Arc::new(Self { inner: vault::VaultSession::create(&pass, vault::DEFAULT_KDF)? }))
    }
    pub fn seal(&self, data: Vec<u8>) -> R<Vec<u8>> {
        Ok(self.inner.seal(&data)?)
    }
}

#[uniffi::export]
pub fn vault_open(pass: String, blob: Vec<u8>) -> R<OpenedVault> {
    let (v, pt) = vault::VaultSession::open(&pass, &blob)?;
    Ok(OpenedVault { vault: Arc::new(VaultSession { inner: v }), plaintext: pt })
}

#[uniffi::export]
pub fn vault_seal(pass: String, data: Vec<u8>) -> R<Vec<u8>> {
    Ok(vault::seal(&pass, &data, vault::DEFAULT_KDF)?)
}

#[uniffi::export]
pub fn vault_seal_with(pass: String, data: Vec<u8>, m_kib: u32, t: u32) -> R<Vec<u8>> {
    Ok(vault::seal(&pass, &data, vault::KdfParams { m_kib, t })?)
}

#[uniffi::export]
pub fn vault_open_plain(pass: String, blob: Vec<u8>) -> R<Vec<u8>> {
    Ok(vault::open(&pass, &blob)?)
}

#[uniffi::export]
pub fn ed25519_verify(pk: Vec<u8>, msg: Vec<u8>, sig: Vec<u8>) -> bool {
    crate::device::verify_ed25519(&pk, &msg, &sig)
}

#[uniffi::export]
pub fn envelope_key() -> Vec<u8> {
    envelope::random_key().to_vec()
}

#[uniffi::export]
pub fn envelope_seal(key: Vec<u8>, kind: u8, gid: Vec<u8>, dev: Vec<u8>, payload: Vec<u8>) -> R<Vec<u8>> {
    Ok(envelope::seal(&key, kind, &gid, &dev, &payload)?)
}

#[uniffi::export]
pub fn envelope_open(key: Vec<u8>, blob: Vec<u8>) -> R<OpenedEnvelope> {
    let (kind, group_id, sender_device, payload) = envelope::open(&key, &blob)?;
    Ok(OpenedEnvelope { kind, group_id, sender_device, payload })
}

/// Neuer Dateischlüssel (32 Byte) + Nonce (19 Byte), hintereinander.
#[uniffi::export]
pub fn file_random_key() -> Vec<u8> {
    let (k, n) = files::random_key();
    [k.as_slice(), n.as_slice()].concat()
}

#[uniffi::export]
pub fn file_encrypt(key: Vec<u8>, nonce: Vec<u8>, data: Vec<u8>) -> R<Vec<u8>> {
    Ok(files::encrypt(&key, &nonce, &data)?)
}

#[uniffi::export]
pub fn file_decrypt(key: Vec<u8>, nonce: Vec<u8>, data: Vec<u8>) -> R<Vec<u8>> {
    Ok(files::decrypt(&key, &nonce, &data)?)
}

#[uniffi::export]
pub fn encrypted_size(plain: u64) -> u64 {
    files::encrypted_size(plain as usize) as u64
}

#[uniffi::export]
pub fn pair_safety_number(a: Vec<u8>, b: Vec<u8>) -> String {
    identity::pair_safety_number(&a, &b)
}

#[uniffi::export]
pub fn sha256(data: Vec<u8>) -> Vec<u8> {
    use sha2::{Digest, Sha256};
    Sha256::digest(&data).to_vec()
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ffi_roundtrip_two_clients() {
        let a = MlsClient::create("alice@a.example".into()).unwrap();
        let b = MlsClient::create("bob@b.example".into()).unwrap();
        let kp = b.key_packages(1, false).unwrap().remove(0);
        let id: serde_json::Value = serde_json::from_str(&a.key_package_identity(kp.clone()).unwrap()).unwrap();
        assert_eq!(id["address"], "bob@b.example");
        let gid = a.create_group().unwrap();
        let add = a.add_members(gid.clone(), vec![kp]).unwrap();
        assert_eq!(b.join(add.welcome).unwrap(), gid);
        let env = r#"{"v":1,"id":"1","ts":1,"content":{"kind":"message","parts":[{"type":"text","body":"hi"}]}}"#;
        let ct = a.encrypt_envelope(gid.clone(), env.into()).unwrap();
        let res: serde_json::Value = serde_json::from_str(&b.process(gid, ct).unwrap()).unwrap();
        assert_eq!(res["kind"], "application");
        assert_eq!(res["sender"], "alice@a.example");
        assert_eq!(res["envelope"]["content"]["parts"][0]["body"], "hi");
    }
}
