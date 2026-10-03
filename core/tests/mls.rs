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
    let (addr, ik) = alice.key_package_identity(&bkp).unwrap();
    assert_eq!(addr, "bob@b.example");
    assert_eq!(ik, bob.identity_public());

    let gid = alice.create_group().unwrap();
    let add = alice.add_members(&gid, &[bkp]).unwrap();
    let gid_b = bob.join(&add.welcome).unwrap();
    assert_eq!(gid, gid_b);

    let env = Envelope {
        v: 1,
        id: "1".into(),
        ts: 0,
        content: Content::Message {
            parts: vec![Part::Code { lang: "sh".into(), body: "rm -rf /".into() }],
        },
    };
    let ct = alice.encrypt(&gid, &env.encode().unwrap()).unwrap();
    match bob.process(&gid, &ct).unwrap() {
        Received::Application { sender, plaintext } => {
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
fn identity_backup() {
    let a = Client::new("a@x.y").unwrap();
    let r = Client::from_identity("a@x.y", &a.export_identity().unwrap()).unwrap();
    assert_eq!(r.identity_public(), a.identity_public());
}

#[test]
fn sign_is_deterministic_length() {
    let a = Client::new("a@x.y").unwrap();
    assert_eq!(a.sign(b"hello").unwrap().len(), 64);
}
