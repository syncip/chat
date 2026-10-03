//! MLS-Client (RFC 9420) auf Basis von OpenMLS, mit Multi-Device (siehe docs/MULTIDEVICE.md).
//!
//! * `account` (AIK): Konto-Identität, beglaubigt Geräte.
//! * `device` (DSK): MLS-Signaturschlüssel dieses Geräts (jedes Gerät ist ein eigenes Blatt).
//! * Das BasicCredential trägt Adresse, AIK, Geräte-ID und das Gerätezertifikat; es wird bei KeyPackages, beim Beitritt und nach
//!   jedem Commit geprüft.
use crate::device::{self, DeviceIdentity};
use crate::error::{mls, Error, Result};
use crate::identity::parse_address;
use crate::padding::{pad, unpad};
use openmls::prelude::{tls_codec::*, *};
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_traits::signatures::Signer;
use openmls_traits::OpenMlsProvider;
use rand::RngCore;

pub const CIPHERSUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

pub struct Client {
    provider: OpenMlsRustCrypto,
    account: SignatureKeyPair,
    device: SignatureKeyPair,
    device_id: String,
    cert_sig: Vec<u8>,
    address: String,
}

/// Ein MLS-Blatt (Gerät) einer Gruppe.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemberInfo {
    pub address: String,
    pub aik: Vec<u8>,
    pub device_id: String,
}

/// Ergebnis der Verarbeitung einer empfangenen MLS-Nachricht.
#[derive(Debug)]
pub enum Received {
    /// Entschlüsselter, entpolsterter Anwendungs-Payload.
    Application {
        sender: String,
        sender_device: String,
        plaintext: Vec<u8>,
    },
    /// Ein Commit wurde angewendet (Mitglieder/Epoche geändert). `removed_self`: dieses Gerät wurde entfernt.
    Commit { sender: String, removed_self: bool },
    /// Proposal gespeichert, wartet auf Commit.
    Proposal,
}

pub struct AddResult {
    /// Commit für alle bisherigen Mitglieder.
    pub commit: Vec<u8>,
    /// Welcome für die neuen Mitglieder.
    pub welcome: Vec<u8>,
}

fn group_config() -> MlsGroupCreateConfig {
    MlsGroupCreateConfig::builder()
        .ciphersuite(CIPHERSUITE)
        .use_ratchet_tree_extension(true)
        .max_past_epochs(5)
        .build()
}

fn join_config() -> MlsGroupJoinConfig {
    MlsGroupJoinConfig::builder()
        .use_ratchet_tree_extension(true)
        .max_past_epochs(5)
        .build()
}

fn new_keypair() -> Result<SignatureKeyPair> {
    SignatureKeyPair::new(CIPHERSUITE.signature_algorithm()).map_err(mls)
}

fn credential_identity(c: &Credential) -> Result<Vec<u8>> {
    BasicCredential::try_from(c.clone())
        .map(|b| b.identity().to_vec())
        .map_err(|_| Error::Invalid("credential type"))
}

impl Client {
    fn build(
        provider: OpenMlsRustCrypto,
        account: SignatureKeyPair,
        address: &str,
    ) -> Result<Self> {
        let device = new_keypair()?;
        device.store(provider.storage()).map_err(mls)?;
        let mut id = [0u8; 8];
        rand::rngs::OsRng.fill_bytes(&mut id);
        let device_id = crate::identity::hex(&id);
        let cert_sig = account
            .sign(&device::cert_message(address, &device_id, device.public()))
            .map_err(|_| Error::Crypto("sign"))?;
        Ok(Self {
            provider,
            account,
            device,
            device_id,
            cert_sig,
            address: address.to_string(),
        })
    }

    /// Neues Konto: frischer AIK und erstes Gerät.
    pub fn new(address: &str) -> Result<Self> {
        parse_address(address)?;
        Self::build(OpenMlsRustCrypto::default(), new_keypair()?, address)
    }

    /// Neues Gerät für ein bestehendes Konto (AIK aus der Backup-Datei).
    pub fn link_device(address: &str, account_keypair: &[u8]) -> Result<Self> {
        parse_address(address)?;
        let account = SignatureKeyPair::tls_deserialize_exact(account_keypair).map_err(mls)?;
        Self::build(OpenMlsRustCrypto::default(), account, address)
    }

    pub fn address(&self) -> &str {
        &self.address
    }

    pub fn device_id(&self) -> &str {
        &self.device_id
    }

    /// Öffentlicher Konto-Schlüssel (AIK, 32 Byte): Safety Numbers und Kontakt-Pinning.
    pub fn identity_public(&self) -> Vec<u8> {
        self.account.to_public_vec()
    }

    /// Öffentlicher Geräteschlüssel (DSK).
    pub fn device_public(&self) -> Vec<u8> {
        self.device.to_public_vec()
    }

    /// Gerätezertifikat (AIK-Signatur über Adresse, Geräte-ID, DSK).
    pub fn device_cert(&self) -> Vec<u8> {
        self.cert_sig.clone()
    }

    /// Signiert mit dem **Geräteschlüssel** (API-Anfragen).
    pub fn sign(&self, data: &[u8]) -> Result<Vec<u8>> {
        self.device.sign(data).map_err(|_| Error::Crypto("sign"))
    }

    /// Signiert mit dem **Konto-Schlüssel** (Registrierung, Gerät hinzufügen).
    pub fn sign_account(&self, data: &[u8]) -> Result<Vec<u8>> {
        self.account.sign(data).map_err(|_| Error::Crypto("sign"))
    }

    /// AIK-Schlüsselpaar serialisiert (enthält den privaten Schlüssel! nur verschlüsselt speichern).
    pub fn export_identity(&self) -> Result<Vec<u8>> {
        self.account.tls_serialize_detached().map_err(mls)
    }

    /// Geräte-Postfach (aus dem AIK abgeleitet) für beliebige Geräte-ID des Kontos.
    pub fn device_inbox(&self, device_id: &str) -> Result<device::Inbox> {
        device::derive_inbox(&self.export_identity()?, device_id)
    }

    fn credential(&self) -> CredentialWithKey {
        let identity = device::encode_credential(
            &self.address,
            self.account.public(),
            &self.device_id,
            &self.cert_sig,
        );
        CredentialWithKey {
            credential: BasicCredential::new(identity).into(),
            signature_key: self.device.public().into(),
        }
    }

    /// Erzeugt `n` KeyPackages (serialisiert). `last_resort` markiert das Fallback-Paket.
    pub fn key_packages(&self, n: usize, last_resort: bool) -> Result<Vec<Vec<u8>>> {
        let mut out = Vec::with_capacity(n);
        for _ in 0..n {
            let mut b = KeyPackage::builder();
            if last_resort {
                b = b.mark_as_last_resort();
            }
            let bundle = b
                .build(CIPHERSUITE, &self.provider, &self.device, self.credential())
                .map_err(mls)?;
            out.push(bundle.key_package().tls_serialize_detached().map_err(mls)?);
        }
        Ok(out)
    }

    fn parse_key_package(&self, bytes: &[u8]) -> Result<KeyPackage> {
        KeyPackageIn::tls_deserialize_exact(bytes)
            .map_err(mls)?
            .validate(self.provider.crypto(), ProtocolVersion::Mls10)
            .map_err(mls)
    }

    /// Geprüfte Identität eines KeyPackages (Zertifikat und Geräteschlüssel müssen zusammenpassen).
    pub fn key_package_identity(&self, bytes: &[u8]) -> Result<DeviceIdentity> {
        let kp = self.parse_key_package(bytes)?;
        let id = credential_identity(kp.leaf_node().credential())?;
        device::verify_credential(&id, kp.leaf_node().signature_key().as_slice())
    }

    /// Neue Gruppe; gibt die Gruppen-ID zurück.
    pub fn create_group(&mut self) -> Result<Vec<u8>> {
        let g = MlsGroup::new(
            &self.provider,
            &self.device,
            &group_config(),
            self.credential(),
        )
        .map_err(mls)?;
        Ok(g.group_id().as_slice().to_vec())
    }

    fn load(&self, gid: &[u8]) -> Result<MlsGroup> {
        MlsGroup::load(self.provider.storage(), &GroupId::from_slice(gid))
            .map_err(mls)?
            .ok_or(Error::Invalid("unknown group"))
    }

    /// Alle Blätter inkl. Zertifikatsprüfung; liefert zusätzlich die rohen Credential-Bytes je Blatt-Index.
    fn validated_members(g: &MlsGroup) -> Result<Vec<(LeafNodeIndex, Vec<u8>, MemberInfo)>> {
        g.members()
            .map(|m| {
                let id = credential_identity(&m.credential)?;
                let d = device::verify_credential(&id, &m.signature_key)?;
                Ok((
                    m.index,
                    id,
                    MemberInfo {
                        address: d.address,
                        aik: d.aik,
                        device_id: d.device_id,
                    },
                ))
            })
            .collect()
    }

    pub fn add_members(&mut self, gid: &[u8], key_packages: &[Vec<u8>]) -> Result<AddResult> {
        let mut g = self.load(gid)?;
        let mut kps = Vec::new();
        for b in key_packages {
            self.key_package_identity(b)?; // ungültige Zertifikate werden nie hinzugefügt
            kps.push(self.parse_key_package(b)?);
        }
        let (commit, welcome, _) = g
            .add_members(&self.provider, &self.device, &kps)
            .map_err(mls)?;
        g.merge_pending_commit(&self.provider).map_err(mls)?;
        Ok(AddResult {
            commit: commit.tls_serialize_detached().map_err(mls)?,
            welcome: welcome.tls_serialize_detached().map_err(mls)?,
        })
    }

    fn remove_leaves(&mut self, gid: &[u8], pred: impl Fn(&MemberInfo) -> bool) -> Result<Vec<u8>> {
        let mut g = self.load(gid)?;
        let idx: Vec<LeafNodeIndex> = Self::validated_members(&g)?
            .into_iter()
            .filter(|(_, _, m)| pred(m))
            .map(|(i, _, _)| i)
            .collect();
        if idx.is_empty() {
            return Err(Error::Invalid("no such member"));
        }
        let (commit, _, _) = g
            .remove_members(&self.provider, &self.device, &idx)
            .map_err(mls)?;
        g.merge_pending_commit(&self.provider).map_err(mls)?;
        commit.tls_serialize_detached().map_err(mls)
    }

    /// Alle Geräte der Konten mit diesen Adressen entfernen; gibt den Commit zurück.
    pub fn remove_members(&mut self, gid: &[u8], addresses: &[String]) -> Result<Vec<u8>> {
        self.remove_leaves(gid, |m| addresses.contains(&m.address))
    }

    /// Einzelne Geräte entfernen (z. B. widerrufene Geräte des eigenen Kontos).
    pub fn remove_devices(&mut self, gid: &[u8], devices: &[(String, String)]) -> Result<Vec<u8>> {
        self.remove_leaves(gid, |m| {
            devices
                .iter()
                .any(|(a, d)| *a == m.address && *d == m.device_id)
        })
    }

    /// Eigenen Schlüssel erneuern (Post-Compromise Security); gibt den Commit zurück.
    pub fn update_keys(&mut self, gid: &[u8]) -> Result<Vec<u8>> {
        let mut g = self.load(gid)?;
        let b = g
            .self_update(&self.provider, &self.device, LeafNodeParameters::default())
            .map_err(mls)?;
        g.merge_pending_commit(&self.provider).map_err(mls)?;
        b.0.tls_serialize_detached().map_err(mls)
    }

    /// Welcome verarbeiten und der Gruppe beitreten; gibt die Gruppen-ID zurück.
    pub fn join(&mut self, welcome: &[u8]) -> Result<Vec<u8>> {
        let msg = MlsMessageIn::tls_deserialize_exact(welcome).map_err(mls)?;
        let MlsMessageBodyIn::Welcome(w) = msg.extract() else {
            return Err(Error::Invalid("not a welcome"));
        };
        // Ein Welcome darf keinen bestehenden Gruppenzustand überschreiben (z. B. durch ein böswilliges
        // Mitglied, das die Gruppen-ID kennt). Die ID steht erst nach dem Beitritt fest, daher zuerst
        // in einer Kopie des Speichers beitreten und prüfen.
        let probe = OpenMlsRustCrypto::default();
        {
            let src = self
                .provider
                .storage()
                .values
                .read()
                .map_err(|_| Error::Crypto("lock"))?;
            let mut dst = probe
                .storage()
                .values
                .write()
                .map_err(|_| Error::Crypto("lock"))?;
            dst.clone_from(&src);
        }
        let probe_group = StagedWelcome::new_from_welcome(&probe, &join_config(), w.clone(), None)
            .map_err(mls)?
            .into_group(&probe)
            .map_err(mls)?;
        let probe_gid = probe_group.group_id().clone();
        if MlsGroup::load(self.provider.storage(), &probe_gid)
            .map_err(mls)?
            .is_some()
        {
            return Err(Error::Invalid("group already exists"));
        }
        // Alle Mitglieds-Zertifikate prüfen, bevor wir dem Zustand vertrauen.
        Self::validated_members(&probe_group)?;
        let g = StagedWelcome::new_from_welcome(&self.provider, &join_config(), w, None)
            .map_err(mls)?
            .into_group(&self.provider)
            .map_err(mls)?;
        Ok(g.group_id().as_slice().to_vec())
    }

    /// Payload auffüllen und verschlüsseln.
    pub fn encrypt(&mut self, gid: &[u8], plaintext: &[u8]) -> Result<Vec<u8>> {
        let mut g = self.load(gid)?;
        let out = g
            .create_message(&self.provider, &self.device, &pad(plaintext))
            .map_err(mls)?;
        out.tls_serialize_detached().map_err(mls)
    }

    pub fn process(&mut self, gid: &[u8], message: &[u8]) -> Result<Received> {
        let mut g = self.load(gid)?;
        let before = Self::validated_members(&g)?;
        let msg = MlsMessageIn::tls_deserialize_exact(message).map_err(mls)?;
        let proto = msg.try_into_protocol_message().map_err(mls)?;
        let processed = g.process_message(&self.provider, proto).map_err(mls)?;
        let sender = match processed.sender() {
            Sender::Member(i) => before
                .iter()
                .find(|(idx, _, _)| idx == i)
                .map(|(_, _, m)| m.clone()),
            _ => None,
        };
        let (sender_addr, sender_dev) =
            sender.map(|m| (m.address, m.device_id)).unwrap_or_default();
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(m) => Ok(Received::Application {
                sender: sender_addr,
                sender_device: sender_dev,
                plaintext: unpad(&m.into_bytes())?,
            }),
            ProcessedMessageContent::StagedCommitMessage(c) => {
                let removed_self = c.self_removed();
                let removed: Vec<LeafNodeIndex> = c
                    .remove_proposals()
                    .map(|q| q.remove_proposal().removed())
                    .collect();
                g.merge_staged_commit(&self.provider, *c).map_err(mls)?;
                if !removed_self {
                    // Zertifikate aller Blätter prüfen; ein Blatt darf seine Identität nicht ändern (außer nach Entfernen).
                    let check = Self::validated_members(&g).and_then(|after| {
                        for (idx, id, _) in &after {
                            if let Some((_, old, _)) = before.iter().find(|(i, _, _)| i == idx) {
                                if old != id && !removed.contains(idx) {
                                    return Err(Error::Crypto("member identity changed"));
                                }
                            }
                        }
                        Ok(())
                    });
                    if let Err(e) = check {
                        let _ = g.delete(self.provider.storage()); // manipulierte Gruppe verwerfen
                        return Err(e);
                    }
                }
                Ok(Received::Commit {
                    sender: sender_addr,
                    removed_self,
                })
            }
            ProcessedMessageContent::ProposalMessage(p) => {
                g.store_pending_proposal(self.provider.storage(), *p)
                    .map_err(mls)?;
                Ok(Received::Proposal)
            }
            ProcessedMessageContent::ExternalJoinProposalMessage(_) => {
                Err(Error::Invalid("external proposals not supported"))
            }
        }
    }

    /// Mitglieder (ein Eintrag je Gerät/Blatt), Zertifikate geprüft.
    pub fn members(&self, gid: &[u8]) -> Result<Vec<MemberInfo>> {
        let g = self.load(gid)?;
        Ok(Self::validated_members(&g)?
            .into_iter()
            .map(|(_, _, m)| m)
            .collect())
    }

    /// Gruppe lokal löschen (z. B. nach Entfernung).
    pub fn delete_group(&mut self, gid: &[u8]) -> Result<()> {
        let mut g = self.load(gid)?;
        g.delete(self.provider.storage()).map_err(mls)
    }

    /// Serialisiert den kompletten Zustand dieses Geräts (Schlüsselmaterial! nur verschlüsselt speichern, siehe `vault`).
    pub fn export_state(&self) -> Result<Vec<u8>> {
        let values = self
            .provider
            .storage()
            .values
            .read()
            .map_err(|_| Error::Crypto("lock"))?;
        let mut out = Vec::new();
        let account = self.export_identity()?;
        let device = self.device.tls_serialize_detached().map_err(mls)?;
        for part in [
            self.address.as_bytes(),
            account.as_slice(),
            device.as_slice(),
            self.device_id.as_bytes(),
            self.cert_sig.as_slice(),
        ] {
            out.extend_from_slice(&(part.len() as u32).to_be_bytes());
            out.extend_from_slice(part);
        }
        out.extend_from_slice(&(values.len() as u32).to_be_bytes());
        for (k, v) in values.iter() {
            out.extend_from_slice(&(k.len() as u32).to_be_bytes());
            out.extend_from_slice(k);
            out.extend_from_slice(&(v.len() as u32).to_be_bytes());
            out.extend_from_slice(v);
        }
        Ok(out)
    }

    pub fn import_state(data: &[u8]) -> Result<Self> {
        let mut r = Reader(data);
        let address =
            String::from_utf8(r.bytes()?.to_vec()).map_err(|_| Error::Invalid("state"))?;
        let account = SignatureKeyPair::tls_deserialize_exact(r.bytes()?).map_err(mls)?;
        let device = SignatureKeyPair::tls_deserialize_exact(r.bytes()?).map_err(mls)?;
        let device_id =
            String::from_utf8(r.bytes()?.to_vec()).map_err(|_| Error::Invalid("state"))?;
        let cert_sig = r.bytes()?.to_vec();
        let n = r.u32()? as usize;
        let provider = OpenMlsRustCrypto::default();
        {
            let mut values = provider
                .storage()
                .values
                .write()
                .map_err(|_| Error::Crypto("lock"))?;
            for _ in 0..n {
                let k = r.bytes()?.to_vec();
                let v = r.bytes()?.to_vec();
                values.insert(k, v);
            }
        }
        parse_address(&address)?;
        if !device::valid_device_id(&device_id) {
            return Err(Error::Invalid("state"));
        }
        Ok(Self {
            provider,
            account,
            device,
            device_id,
            cert_sig,
            address,
        })
    }
}

struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn u32(&mut self) -> Result<u32> {
        let (h, t) = self.0.split_at_checked(4).ok_or(Error::Invalid("state"))?;
        self.0 = t;
        Ok(u32::from_be_bytes(h.try_into().unwrap()))
    }
    fn bytes(&mut self) -> Result<&'a [u8]> {
        let n = self.u32()? as usize;
        let (h, t) = self.0.split_at_checked(n).ok_or(Error::Invalid("state"))?;
        self.0 = t;
        Ok(h)
    }
}
