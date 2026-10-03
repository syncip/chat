use chat_core::message::*;
use chat_core::mls::{Client, Received};

fn kp(c: &Client) -> Vec<u8> {
    c.key_packages(1, false).unwrap().remove(0)
}

#[test]
fn one_to_one_and_group_lifecycle() {
    let mut alice = Client::new("alice@a.example").unwrap();
    let mut bob = Client::new("bob@b.example").unwrap();
    let mut carol = Client::new("carol@c.example").unwrap();

    // Identität im KeyPackage prüfen
    let bkp = kp(&bob);
    let id = alice.key_package_identity(&bkp).unwrap();
    assert_eq!(id.address, "bob@b.example");
    assert_eq!(id.aik, bob.identity_public());
    assert_eq!(id.device_id, bob.device_id());

    let gid = alice.create_group().unwrap();
    let add = alice.add_members(&gid, &[bkp]).unwrap();
    let gid_b = bob.join(&add.welcome).unwrap();
    assert_eq!(gid, gid_b);

    let env = Envelope {
        v: 1,
        id: "1".into(),
        ts: 0,
        content: Content::Message {
            once: false,
            parts: vec![Part::Code { lang: "sh".into(), body: "rm -rf /".into() }],
        },
    };
    let ct = alice.encrypt(&gid, &env.encode().unwrap()).unwrap();
    match bob.process(&gid, &ct).unwrap() {
        Received::Application { sender, plaintext, .. } => {
            assert_eq!(sender, "alice@a.example");
            assert_eq!(Envelope::decode(&plaintext).unwrap(), env);
        }
        r => panic!("unexpected {r:?}"),
    }

    // gleiche Länge-Klasse => gleiche Ciphertext-Länge (Padding)
    let c1 = alice.encrypt(&gid, b"hi").unwrap();
    let c2 = alice.encrypt(&gid, b"hello there, this is longer").unwrap();
    assert_eq!(c1.len(), c2.len());
    bob.process(&gid, &c1).unwrap();
    bob.process(&gid, &c2).unwrap();

    // Carol hinzufügen: Bob wendet Commit an
    let add2 = alice.add_members(&gid, &[kp(&carol)]).unwrap();
    assert!(matches!(bob.process(&gid, &add2.commit).unwrap(), Received::Commit { .. }));
    carol.join(&add2.welcome).unwrap();
    let ct = bob.encrypt(&gid, b"to all").unwrap();
    for c in [&mut alice, &mut carol] {
        assert!(matches!(c.process(&gid, &ct).unwrap(), Received::Application { .. }));
    }
    assert_eq!(alice.members(&gid).unwrap().len(), 3);

    // Schlüssel erneuern
    let upd = bob.update_keys(&gid).unwrap();
    alice.process(&gid, &upd).unwrap();
    carol.process(&gid, &upd).unwrap();

    // Bob entfernen: danach kann er nicht mehr lesen
    let rm = alice.remove_members(&gid, &["bob@b.example".into()]).unwrap();
    match bob.process(&gid, &rm).unwrap() {
        Received::Commit { removed_self, .. } => assert!(removed_self),
        r => panic!("{r:?}"),
    }
    carol.process(&gid, &rm).unwrap();
    let secret = alice.encrypt(&gid, b"bob must not read").unwrap();
    assert!(bob.process(&gid, &secret).is_err());
    assert!(matches!(carol.process(&gid, &secret).unwrap(), Received::Application { .. }));
}

#[test]
fn tampered_ciphertext_rejected() {
    let mut a = Client::new("a@x.y").unwrap();
    let mut b = Client::new("b@x.y").unwrap();
    let gid = a.create_group().unwrap();
    let add = a.add_members(&gid, &[kp(&b)]).unwrap();
    b.join(&add.welcome).unwrap();
    let mut ct = a.encrypt(&gid, b"x").unwrap();
    let n = ct.len() - 3;
    ct[n] ^= 0x01;
    // OpenMLS löst im Debug-Build zusätzlich ein debug_assert aus; im Release-Build ist es ein Err.
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| b.process(&gid, &ct)));
    assert!(matches!(r, Err(_) | Ok(Err(_))));
}

#[test]
fn state_export_import_keeps_groups() {
    let mut a = Client::new("a@x.y").unwrap();
    let mut b = Client::new("b@x.y").unwrap();
    let gid = a.create_group().unwrap();
    let add = a.add_members(&gid, &[kp(&b)]).unwrap();
    b.join(&add.welcome).unwrap();

    let blob = a.export_state().unwrap();
    let mut a2 = Client::import_state(&blob).unwrap();
    assert_eq!(a2.identity_public(), a.identity_public());
    let ct = a2.encrypt(&gid, b"after restore").unwrap();
    match b.process(&gid, &ct).unwrap() {
        Received::Application { plaintext, .. } => assert_eq!(plaintext, b"after restore"),
        r => panic!("{r:?}"),
    }
    assert!(Client::import_state(&blob[..blob.len() / 2]).is_err());
}

#[test]
fn sign_account_and_device_differ() {
    let a = Client::new("a@x.y").unwrap();
    assert_ne!(a.identity_public(), a.device_public());
    assert_eq!(a.sign_account(b"x").unwrap().len(), 64);
}

#[test]
fn multi_device_same_account_in_one_group() {
    let mut a1 = Client::new("alice@a.example").unwrap();
    // zweites Gerät desselben Kontos aus dem exportierten AIK
    let mut a2 = Client::link_device("alice@a.example", &a1.export_identity().unwrap()).unwrap();
    let mut bob = Client::new("bob@b.example").unwrap();
    assert_eq!(a1.identity_public(), a2.identity_public());
    assert_ne!(a1.device_id(), a2.device_id());
    assert_ne!(a1.device_public(), a2.device_public());
    // das Geräte-Postfach ist aus dem AIK ableitbar: beide Geräte berechnen dasselbe
    assert_eq!(a1.device_inbox(a2.device_id()).unwrap(), a2.device_inbox(a2.device_id()).unwrap());

    let gid = a1.create_group().unwrap();
    let add = a1.add_members(&gid, &[kp(&bob)]).unwrap();
    bob.join(&add.welcome).unwrap();

    // Gerät 2 wird hinzugefügt (Welcome würde an dessen Inbox gehen)
    let add2 = a1.add_members(&gid, &[kp(&a2)]).unwrap();
    bob.process(&gid, &add2.commit).unwrap();
    a2.join(&add2.welcome).unwrap();
    let members = bob.members(&gid).unwrap();
    assert_eq!(members.len(), 3);
    assert_eq!(members.iter().filter(|m| m.address == "alice@a.example").count(), 2);

    // Gerät 2 schreibt, Bob und Gerät 1 lesen; Absender-Gerät ist erkennbar
    let ct = a2.encrypt(&gid, b"von gerat 2").unwrap();
    for c in [&mut bob, &mut a1] {
        match c.process(&gid, &ct).unwrap() {
            Received::Application { sender, sender_device, plaintext } => {
                assert_eq!(sender, "alice@a.example");
                assert_eq!(sender_device, a2.device_id());
                assert_eq!(plaintext, b"von gerat 2");
            }
            r => panic!("{r:?}"),
        }
    }
    // eigene Nachricht kann das sendende Gerät nicht entschlüsseln (deshalb steht die Geräte-ID im Umschlag)
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| a2.process(&gid, &ct)));
    assert!(matches!(r, Err(_) | Ok(Err(_))));

    // Gerät 2 widerrufen: Gerät 1 entfernt nur dieses Blatt
    let rm = a1.remove_devices(&gid, &[("alice@a.example".into(), a2.device_id().to_string())]).unwrap();
    match a2.process(&gid, &rm).unwrap() {
        Received::Commit { removed_self, .. } => assert!(removed_self),
        r => panic!("{r:?}"),
    }
    bob.process(&gid, &rm).unwrap();
    assert_eq!(bob.members(&gid).unwrap().len(), 2);
    assert_eq!(bob.members(&gid).unwrap().iter().filter(|m| m.device_id == a1.device_id()).count(), 1);
}

#[test]
fn state_roundtrip_keeps_device_identity() {
    let a = Client::new("a@x.y").unwrap();
    let r = Client::import_state(&a.export_state().unwrap()).unwrap();
    assert_eq!(r.identity_public(), a.identity_public());
    assert_eq!(r.device_id(), a.device_id());
    assert_eq!(r.device_public(), a.device_public());
    assert_eq!(r.device_cert(), a.device_cert());
}

#[test]
fn sign_is_deterministic_length() {
    let a = Client::new("a@x.y").unwrap();
    assert_eq!(a.sign(b"hello").unwrap().len(), 64);
}

#[test]
fn welcome_cannot_overwrite_existing_group() {
    let mut a = Client::new("a@x.y").unwrap();
    let mut b = Client::new("b@x.y").unwrap();
    let gid = a.create_group().unwrap();
    let add = a.add_members(&gid, &[kp(&b)]).unwrap();
    b.join(&add.welcome).unwrap();
    let ct = a.encrypt(&gid, b"state must survive").unwrap();
    // Replay desselben Welcomes: abgelehnt, Zustand bleibt intakt.
    assert!(b.join(&add.welcome).is_err());
    assert!(matches!(b.process(&gid, &ct).unwrap(), Received::Application { .. }));
}
