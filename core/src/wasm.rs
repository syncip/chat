//! WASM-Schnittstelle für den Web-Client. Alle Binärdaten als `Uint8Array`, strukturierte Daten als JSON.
use crate::{envelope, error::Error, files, identity, message::Envelope, mls, vault};
use wasm_bindgen::prelude::*;

fn js(e: Error) -> JsError {
    JsError::new(&e.to_string())
}

#[wasm_bindgen]
pub struct Client(mls::Client);

#[wasm_bindgen]
impl Client {
    /// Neue Identität.
    #[wasm_bindgen(constructor)]
    pub fn new(address: &str) -> Result<Client, JsError> {
        mls::Client::new(address).map(Client).map_err(js)
    }
    /// Neues Gerät für ein bestehendes Konto (AIK-Schlüsselpaar aus der Backup-Datei).
    #[wasm_bindgen(js_name = linkDevice)]
    pub fn link_device(address: &str, keypair: &[u8]) -> Result<Client, JsError> {
        mls::Client::link_device(address, keypair)
            .map(Client)
            .map_err(js)
    }
    #[wasm_bindgen(js_name = importState)]
    pub fn import_state(data: &[u8]) -> Result<Client, JsError> {
        mls::Client::import_state(data).map(Client).map_err(js)
    }
    #[wasm_bindgen(js_name = exportState)]
    pub fn export_state(&self) -> Result<Vec<u8>, JsError> {
        self.0.export_state().map_err(js)
    }
    #[wasm_bindgen(js_name = exportIdentity)]
    pub fn export_identity(&self) -> Result<Vec<u8>, JsError> {
        self.0.export_identity().map_err(js)
    }
    pub fn address(&self) -> String {
        self.0.address().to_string()
    }
    #[wasm_bindgen(js_name = deviceId)]
    pub fn device_id(&self) -> String {
        self.0.device_id().to_string()
    }
    #[wasm_bindgen(js_name = devicePublic)]
    pub fn device_public(&self) -> Vec<u8> {
        self.0.device_public()
    }
    #[wasm_bindgen(js_name = deviceCert)]
    pub fn device_cert(&self) -> Vec<u8> {
        self.0.device_cert()
    }
    /// Signiert mit dem Konto-Schlüssel (Registrierung, Gerät hinzufügen).
    #[wasm_bindgen(js_name = signAccount)]
    pub fn sign_account(&self, data: &[u8]) -> Result<Vec<u8>, JsError> {
        self.0.sign_account(data).map_err(js)
    }
    /// JSON `{mailbox_id, token, key}` (key hex): Geräte-Postfach, aus dem AIK abgeleitet.
    #[wasm_bindgen(js_name = deviceInbox)]
    pub fn device_inbox(&self, device_id: &str) -> Result<String, JsError> {
        let i = self.0.device_inbox(device_id).map_err(js)?;
        Ok(
            serde_json::json!({"mailbox_id": i.mailbox_id, "token": i.token, "key": hex(&i.key)})
                .to_string(),
        )
    }
    /// Schlüssel (32 Byte) für den Konto-Sync-Blob.
    #[wasm_bindgen(js_name = syncKey)]
    pub fn sync_key(&self) -> Result<Vec<u8>, JsError> {
        Ok(self.0.sync_key().map_err(js)?.to_vec())
    }
    #[wasm_bindgen(js_name = identityPublic)]
    pub fn identity_public(&self) -> Vec<u8> {
        self.0.identity_public()
    }
    /// Signiert Daten mit dem Identity Key (für Login/Request-Signaturen).
    pub fn sign(&self, data: &[u8]) -> Result<Vec<u8>, JsError> {
        self.0.sign(data).map_err(js)
    }
    /// `n` KeyPackages als Array von Uint8Array.
    #[wasm_bindgen(js_name = keyPackages)]
    pub fn key_packages(&self, n: usize, last_resort: bool) -> Result<js_sys::Array, JsError> {
        let v = self.0.key_packages(n, last_resort).map_err(js)?;
        Ok(v.into_iter()
            .map(|b| js_sys::Uint8Array::from(&b[..]))
            .collect())
    }
    /// JSON `{address, identity, device}` (identity = AIK hex). Prüft das Gerätezertifikat.
    #[wasm_bindgen(js_name = keyPackageIdentity)]
    pub fn key_package_identity(&self, kp: &[u8]) -> Result<String, JsError> {
        let d = self.0.key_package_identity(kp).map_err(js)?;
        Ok(serde_json::json!({"address": d.address, "identity": hex(&d.aik), "device": d.device_id}).to_string())
    }
    #[wasm_bindgen(js_name = createGroup)]
    pub fn create_group(&mut self) -> Result<Vec<u8>, JsError> {
        self.0.create_group().map_err(js)
    }
    /// JSON `{commit, welcome}` (base64-frei: Uint8Array-Paar als Array).
    #[wasm_bindgen(js_name = addMembers)]
    pub fn add_members(
        &mut self,
        gid: &[u8],
        kps: js_sys::Array,
    ) -> Result<js_sys::Array, JsError> {
        let kps: Vec<Vec<u8>> = kps
            .iter()
            .map(|v| js_sys::Uint8Array::new(&v).to_vec())
            .collect();
        let r = self.0.add_members(gid, &kps).map_err(js)?;
        Ok(js_sys::Array::of2(
            &js_sys::Uint8Array::from(&r.commit[..]),
            &js_sys::Uint8Array::from(&r.welcome[..]),
        ))
    }
    #[wasm_bindgen(js_name = removeMembers)]
    pub fn remove_members(
        &mut self,
        gid: &[u8],
        addresses: Vec<String>,
    ) -> Result<Vec<u8>, JsError> {
        self.0.remove_members(gid, &addresses).map_err(js)
    }
    #[wasm_bindgen(js_name = updateKeys)]
    pub fn update_keys(&mut self, gid: &[u8]) -> Result<Vec<u8>, JsError> {
        self.0.update_keys(gid).map_err(js)
    }
    pub fn join(&mut self, welcome: &[u8]) -> Result<Vec<u8>, JsError> {
        self.0.join(welcome).map_err(js)
    }
    #[wasm_bindgen(js_name = deleteGroup)]
    pub fn delete_group(&mut self, gid: &[u8]) -> Result<(), JsError> {
        self.0.delete_group(gid).map_err(js)
    }
    /// Envelope-JSON verschlüsseln.
    #[wasm_bindgen(js_name = encryptEnvelope)]
    pub fn encrypt_envelope(
        &mut self,
        gid: &[u8],
        envelope_json: &str,
    ) -> Result<Vec<u8>, JsError> {
        let env: Envelope =
            serde_json::from_str(envelope_json).map_err(|_| JsError::new("envelope"))?;
        self.0.encrypt(gid, &env.encode().map_err(js)?).map_err(js)
    }
    /// JSON: `{kind:"application", sender, envelope}` | `{kind:"commit", sender, removedSelf}` | `{kind:"proposal"}`.
    pub fn process(&mut self, gid: &[u8], message: &[u8]) -> Result<String, JsError> {
        let v = match self.0.process(gid, message).map_err(js)? {
            mls::Received::Application {
                sender,
                sender_device,
                plaintext,
            } => {
                let env = Envelope::decode(&plaintext).map_err(js)?;
                serde_json::json!({"kind":"application","sender":sender,"senderDevice":sender_device,"envelope":env})
            }
            mls::Received::Commit {
                sender,
                removed_self,
            } => {
                serde_json::json!({"kind":"commit","sender":sender,"removedSelf":removed_self})
            }
            mls::Received::Proposal => serde_json::json!({"kind":"proposal"}),
        };
        Ok(v.to_string())
    }
    /// JSON-Array `[{address, identity(AIK hex), device}]`, ein Eintrag je Gerät.
    pub fn members(&self, gid: &[u8]) -> Result<String, JsError> {
        let m = self.0.members(gid).map_err(js)?;
        let v: Vec<_> = m
            .into_iter()
            .map(|m| serde_json::json!({"address": m.address, "identity": hex(&m.aik), "device": m.device_id}))
            .collect();
        Ok(serde_json::Value::Array(v).to_string())
    }
    /// Einzelne Geräte entfernen: JSON `[{"address":…,"device":…}]`; gibt den Commit zurück.
    #[wasm_bindgen(js_name = removeDevices)]
    pub fn remove_devices(&mut self, gid: &[u8], devices_json: &str) -> Result<Vec<u8>, JsError> {
        #[derive(serde::Deserialize)]
        struct D {
            address: String,
            device: String,
        }
        let d: Vec<D> = serde_json::from_str(devices_json).map_err(|_| JsError::new("devices"))?;
        let pairs: Vec<(String, String)> = d.into_iter().map(|x| (x.address, x.device)).collect();
        self.0.remove_devices(gid, &pairs).map_err(js)
    }
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

#[wasm_bindgen(js_name = safetyNumber)]
pub fn safety_number(ik: &[u8]) -> String {
    identity::safety_number(ik)
}

#[wasm_bindgen(js_name = pairSafetyNumber)]
pub fn pair_safety_number(a: &[u8], b: &[u8]) -> String {
    identity::pair_safety_number(a, b)
}

#[wasm_bindgen(js_name = vaultSeal)]
pub fn vault_seal(pass: &str, data: &[u8]) -> Result<Vec<u8>, JsError> {
    vault::seal(pass, data, vault::DEFAULT_KDF).map_err(js)
}

#[wasm_bindgen(js_name = vaultOpen)]
pub fn vault_open(pass: &str, blob: &[u8]) -> Result<Vec<u8>, JsError> {
    vault::open(pass, blob).map_err(js)
}

/// Neuer zufälliger Dateischlüssel (32 Byte) + Nonce (19 Byte), hintereinander.
#[wasm_bindgen(js_name = fileRandomKey)]
pub fn file_random_key() -> Vec<u8> {
    let (k, n) = files::random_key();
    [k.as_slice(), n.as_slice()].concat()
}

#[wasm_bindgen(js_name = fileEncrypt)]
pub fn file_encrypt(key: &[u8], nonce: &[u8], data: &[u8]) -> Result<Vec<u8>, JsError> {
    files::encrypt(key, nonce, data).map_err(js)
}

#[wasm_bindgen(js_name = fileDecrypt)]
pub fn file_decrypt(key: &[u8], nonce: &[u8], data: &[u8]) -> Result<Vec<u8>, JsError> {
    files::decrypt(key, nonce, data).map_err(js)
}

#[wasm_bindgen(js_name = encryptedSize)]
pub fn encrypted_size(plain: usize) -> usize {
    files::encrypted_size(plain)
}

/// Entsperrter Vault (Argon2 nur einmal pro Sitzung).
#[wasm_bindgen]
pub struct Vault(vault::VaultSession);

#[wasm_bindgen]
impl Vault {
    pub fn create(pass: &str) -> Result<Vault, JsError> {
        vault::VaultSession::create(pass, vault::DEFAULT_KDF)
            .map(Vault)
            .map_err(js)
    }
    /// Gibt `[Vault, Uint8Array]` zurück.
    pub fn open(pass: &str, blob: &[u8]) -> Result<js_sys::Array, JsError> {
        let (v, pt) = vault::VaultSession::open(pass, blob).map_err(js)?;
        Ok(js_sys::Array::of2(
            &JsValue::from(Vault(v)),
            &js_sys::Uint8Array::from(&pt[..]),
        ))
    }
    pub fn seal(&self, data: &[u8]) -> Result<Vec<u8>, JsError> {
        self.0.seal(data).map_err(js)
    }
}

#[wasm_bindgen(js_name = envelopeKey)]
pub fn envelope_key() -> Vec<u8> {
    envelope::random_key().to_vec()
}

#[wasm_bindgen(js_name = envelopeSeal)]
pub fn envelope_seal(
    key: &[u8],
    kind: u8,
    gid: &[u8],
    dev: &[u8],
    payload: &[u8],
) -> Result<Vec<u8>, JsError> {
    envelope::seal(key, kind, gid, dev, payload).map_err(js)
}

/// Gibt `[kind:number, groupId:Uint8Array, senderDevice:Uint8Array, payload:Uint8Array]` zurück.
#[wasm_bindgen(js_name = envelopeOpen)]
pub fn envelope_open(key: &[u8], blob: &[u8]) -> Result<js_sys::Array, JsError> {
    let (k, g, d, p) = envelope::open(key, blob).map_err(js)?;
    let a = js_sys::Array::new();
    a.push(&JsValue::from(k));
    a.push(&js_sys::Uint8Array::from(&g[..]));
    a.push(&js_sys::Uint8Array::from(&d[..]));
    a.push(&js_sys::Uint8Array::from(&p[..]));
    Ok(a)
}

/// SHA-256 ohne WebCrypto (steht in unsicheren Kontexten wie http://IP:PORT nicht zur Verfügung).
#[wasm_bindgen]
pub fn sha256(data: &[u8]) -> Vec<u8> {
    use sha2::{Digest, Sha256};
    Sha256::digest(data).to_vec()
}

#[wasm_bindgen(js_name = ed25519Verify)]
pub fn ed25519_verify(pk: &[u8], msg: &[u8], sig: &[u8]) -> bool {
    crate::device::verify_ed25519(pk, msg, sig)
}
