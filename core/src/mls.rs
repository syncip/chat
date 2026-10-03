//! MLS-Client (RFC 9420) auf Basis von OpenMLS.
//! Der Identity Key (Ed25519) ist zugleich der MLS-Signaturschlüssel, die Adresse steht im BasicCredential.
use crate::error::{mls, Error, Result};
use crate::identity::parse_address;
use crate::padding::{pad, unpad};
use openmls::prelude::{tls_codec::*, *};
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_traits::OpenMlsProvider;

pub const CIPHERSUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

pub struct Client {
    provider: OpenMlsRustCrypto,
    signer: SignatureKeyPair,
    address: String,
}

/// Ergebnis der Verarbeitung einer empfangenen MLS-Nachricht.
#[derive(Debug)]
pub enum Received {
    /// Entschlüsselter, entpolsterter Anwendungs-Payload.
    Application { sender: String, plaintext: Vec<u8> },
    /// Ein Commit wurde angewendet (Mitglieder/Epoche geändert). `removed_self`: wir wurden entfernt.
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
    MlsGroupJoinConfig::builder().use_ratchet_tree_extension(true).max_past_epochs(5).build()
}

impl Client {
    /// Neue Identität mit frischem Identity Key.
    pub fn new(address: &str) -> Result<Self> {
        parse_address(address)?;
        let provider = OpenMlsRustCrypto::default();
        let signer = SignatureKeyPair::new(CIPHERSUITE.signature_algorithm()).map_err(mls)?;
        signer.store(provider.storage()).map_err(mls)?;
        Ok(Self { provider, signer, address: address.to_string() })
    }

    pub fn address(&self) -> &str {
        &self.address
    }

    /// Öffentlicher Identity Key (Ed25519, 32 Byte).
    pub fn identity_public(&self) -> Vec<u8> {
        self.signer.to_public_vec()
    }

    /// Signiert beliebige Daten mit dem Identity Key (Ed25519).
    pub fn sign(&self, data: &[u8]) -> Result<Vec<u8>> {
        use openmls_traits::signatures::Signer;
        self.signer.sign(data).map_err(|_| Error::Crypto("sign"))
    }

    /// Schlüsselpaar serialisiert (enthält den privaten Schlüssel! nur verschlüsselt speichern).
    pub fn export_identity(&self) -> Result<Vec<u8>> {
        self.signer.tls_serialize_detached().map_err(mls)
    }

    /// Identität aus Backup wiederherstellen (ohne Gruppen).
    pub fn from_identity(address: &str, keypair: &[u8]) -> Result<Self> {
        parse_address(address)?;
        let provider = OpenMlsRustCrypto::default();
        let signer = SignatureKeyPair::tls_deserialize_exact(keypair).map_err(mls)?;
        signer.store(provider.storage()).map_err(mls)?;
        Ok(Self { provider, signer, address: address.to_string() })
    }

    fn credential(&self) -> CredentialWithKey {
        CredentialWithKey {
            credential: BasicCredential::new(self.address.clone().into_bytes()).into(),
            signature_key: self.signer.public().into(),
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
                .build(CIPHERSUITE, &self.provider, &self.signer, self.credential())
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

    /// Adresse und Identity Key aus einem KeyPackage (zur Verifikation vor dem Hinzufügen).
    pub fn key_package_identity(&self, bytes: &[u8]) -> Result<(String, Vec<u8>)> {
        let kp = self.parse_key_package(bytes)?;
        let cred = BasicCredential::try_from(kp.leaf_node().credential().clone()).map_err(mls)?;
        let addr = String::from_utf8(cred.identity().to_vec()).map_err(|_| Error::Invalid("identity"))?;
        parse_address(&addr)?;
        Ok((addr, kp.leaf_node().signature_key().as_slice().to_vec()))
    }

    /// Neue Gruppe; gibt die Gruppen-ID zurück.
    pub fn create_group(&mut self) -> Result<Vec<u8>> {
        let g = MlsGroup::new(&self.provider, &self.signer, &group_config(), self.credential())
            .map_err(mls)?;
        Ok(g.group_id().as_slice().to_vec())
    }

    fn load(&self, gid: &[u8]) -> Result<MlsGroup> {
        MlsGroup::load(self.provider.storage(), &GroupId::from_slice(gid))
            .map_err(mls)?
            .ok_or(Error::Invalid("unknown group"))
    }

    pub fn add_members(&mut self, gid: &[u8], key_packages: &[Vec<u8>]) -> Result<AddResult> {
        let mut g = self.load(gid)?;
        let kps = key_packages
            .iter()
            .map(|b| self.parse_key_package(b))
            .collect::<Result<Vec<_>>>()?;
        let (commit, welcome, _) = g.add_members(&self.provider, &self.signer, &kps).map_err(mls)?;
        g.merge_pending_commit(&self.provider).map_err(mls)?;
        Ok(AddResult {
            commit: commit.tls_serialize_detached().map_err(mls)?,
            welcome: welcome.tls_serialize_detached().map_err(mls)?,
        })
    }

    /// Mitglieder per Adresse entfernen; gibt den Commit zurück.
    pub fn remove_members(&mut self, gid: &[u8], addresses: &[String]) -> Result<Vec<u8>> {
        let mut g = self.load(gid)?;
        let idx: Vec<LeafNodeIndex> = g
            .members()
            .filter(|m| {
                BasicCredential::try_from(m.credential.clone())
                    .map(|c| addresses.iter().any(|a| a.as_bytes() == c.identity()))
                    .unwrap_or(false)
            })
            .map(|m| m.index)
            .collect();
        if idx.is_empty() {
            return Err(Error::Invalid("no such member"));
        }
        let (commit, _, _) = g.remove_members(&self.provider, &self.signer, &idx).map_err(mls)?;
        g.merge_pending_commit(&self.provider).map_err(mls)?;
        commit.tls_serialize_detached().map_err(mls)
    }

    /// Eigenen Schlüssel erneuern (Post-Compromise Security); gibt den Commit zurück.
    pub fn update_keys(&mut self, gid: &[u8]) -> Result<Vec<u8>> {
        let mut g = self.load(gid)?;
        let b = g
            .self_update(&self.provider, &self.signer, LeafNodeParameters::default())
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
            .create_message(&self.provider, &self.signer, &pad(plaintext))
            .map_err(mls)?;
        out.tls_serialize_detached().map_err(mls)
    }

    pub fn process(&mut self, gid: &[u8], message: &[u8]) -> Result<Received> {
        let mut g = self.load(gid)?;
        let msg = MlsMessageIn::tls_deserialize_exact(message).map_err(mls)?;
        let proto = msg.try_into_protocol_message().map_err(mls)?;
        let processed = g.process_message(&self.provider, proto).map_err(mls)?;
        let sender = match processed.sender() {
            Sender::Member(i) => g
                .member(*i)
                .and_then(|c| BasicCredential::try_from(c.clone()).ok())
                .map(|c| String::from_utf8_lossy(c.identity()).into_owned())
                .unwrap_or_default(),
            _ => String::new(),
        };
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(m) => Ok(Received::Application {
                sender,
                plaintext: unpad(&m.into_bytes())?,
            }),
            ProcessedMessageContent::StagedCommitMessage(c) => {
                let removed_self = c.self_removed();
                g.merge_staged_commit(&self.provider, *c).map_err(mls)?;
                Ok(Received::Commit { sender, removed_self })
            }
            ProcessedMessageContent::ProposalMessage(p) => {
                g.store_pending_proposal(self.provider.storage(), *p).map_err(mls)?;
                Ok(Received::Proposal)
            }
            ProcessedMessageContent::ExternalJoinProposalMessage(_) => {
                Err(Error::Invalid("external proposals not supported"))
            }
        }
    }

    /// Mitglieder: (Adresse, Identity Key).
    pub fn members(&self, gid: &[u8]) -> Result<Vec<(String, Vec<u8>)>> {
        let g = self.load(gid)?;
        Ok(g.members()
            .map(|m| {
                let addr = BasicCredential::try_from(m.credential)
                    .map(|c| String::from_utf8_lossy(c.identity()).into_owned())
                    .unwrap_or_default();
                (addr, m.signature_key)
            })
            .collect())
    }

    /// Gruppe lokal löschen (z. B. nach Entfernung).
    pub fn delete_group(&mut self, gid: &[u8]) -> Result<()> {
        let mut g = self.load(gid)?;
        g.delete(self.provider.storage()).map_err(mls)
    }

    /// Serialisiert den kompletten MLS-Zustand (Schlüsselmaterial! nur verschlüsselt speichern, siehe `vault`).
    pub fn export_state(&self) -> Result<Vec<u8>> {
        let values = self.provider.storage().values.read().map_err(|_| Error::Crypto("lock"))?;
        let mut out = Vec::new();
        let addr = self.address.as_bytes();
        let kp = self.export_identity()?;
        for part in [addr, kp.as_slice()] {
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
        let addr = String::from_utf8(r.bytes()?.to_vec()).map_err(|_| Error::Invalid("state"))?;
        let kp = r.bytes()?.to_vec();
        let n = r.u32()? as usize;
        let provider = OpenMlsRustCrypto::default();
        {
            let mut values = provider.storage().values.write().map_err(|_| Error::Crypto("lock"))?;
            for _ in 0..n {
                let k = r.bytes()?.to_vec();
                let v = r.bytes()?.to_vec();
                values.insert(k, v);
            }
        }
        parse_address(&addr)?;
        let signer = SignatureKeyPair::tls_deserialize_exact(&kp).map_err(mls)?;
        Ok(Self { provider, signer, address: addr })
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
