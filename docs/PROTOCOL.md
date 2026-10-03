# Protokoll-Entwurf v0

Status: **v0, implementiert** (Server `server/`, Kern `core/`, Web-Client `web/`). Abschnitt 11 listet, wo die Umsetzung vom ursprünglichen Entwurf abweicht und was noch offen ist.

## 1. Identität und Adressen

- Jeder Nutzer hat einen **Identity Key (IK)**, ein Ed25519-Schlüsselpaar, das im Client erzeugt wird.
  Der private Schlüssel verlässt das Gerät nie.
- Adresse: `name@domain` (z. B. `alice@chat.example.org`). `name` ist frei wählbar und unverifiziert,
  die eigentliche Identität ist der IK.
- **Fingerprint / Safety Number:** `SHA-256(IK_pub)` in lesbarer Form (Gruppen aus Ziffern/Wörtern, QR-Code).
  Kontakte können sich damit außerhalb des Systems verifizieren.
- **Recovery:** Der IK lässt sich als BIP39-Wortfolge sichern, die bei der Registrierung einmalig angezeigt wird.
  „Passwort vergessen“ gibt es nicht.
- Der IK ist unabhängig vom Server: Ein Nutzer kann mit einer signierten **Migrationsnachricht** zu einem
  anderen Server umziehen; Kontakte folgen der Signatur.

## 2. Registrierung und Login

Registrierung ist auf **jedem Server** möglich, auch auf fremden (Nutzer wählen ihren Home-Server frei).
Der Betreiber stellt ein: `offen`, `nur mit Einladung` (Default) oder `geschlossen`.

```
GET  /v1/server-info                    Server-Name, Modus, Limits, Server-Public-Key, Client-Bundle-Hash
POST /v1/register                       { invite, name, ik_pub, signature(challenge) , keypackages[] }
GET  /v1/auth/challenge                 → { nonce }
```

- **Einladungen:** Einmal-Token, vom Betreiber oder (konfigurierbar) von Nutzern erzeugt, mit Ablauf.
  Optional Proof-of-Work bei offener Registrierung.
- **Login:** Challenge-Response mit dem IK. Jede API-Anfrage wird mit dem IK signiert
  (Methode, Pfad, Body-Hash, Zeitstempel, Nonce), kein Passwort, keine Cookies.
  Der WebSocket authentifiziert sich beim Verbindungsaufbau per Challenge.
- Lokal werden Schlüssel mit einer Passphrase (Argon2id) verschlüsselt abgelegt. Im Browser als
  nicht-extrahierbare WebCrypto-Keys bzw. verschlüsselt in IndexedDB.

## 3. Ende-zu-Ende-Verschlüsselung: MLS

- Basis: **MLS (RFC 9420)** über OpenMLS (Rust, als WASM im Web).
- Ciphersuite (Start): `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`. Hybride PQ-Suite, sobald verfügbar.
- 1:1-Chats sind MLS-Gruppen mit 2 Mitgliedern → ein einziger Code-Pfad für alles.
- Gruppenzustand wird **ausschließlich clientseitig** gehalten. Der Server kennt keine Gruppen.
- Credentials: `BasicCredential` = Adresse + IK, KeyPackages sind mit dem IK signiert.
- **KeyPackages:** Jedes Gerät lädt viele Einmal-KeyPackages und ein „Last-Resort“-KeyPackage hoch.
  `GET /v1/users/{name}/keypackage` liefert eines aus. Ein bösartiger Server könnte hier ein falsches
  ausliefern → Verifikation per Safety Number, TOFU-Pinning und Warnung bei Schlüsselwechsel.
- Gruppenverwaltung (Add/Remove/Update/Commit) sind MLS-Handshake-Nachrichten und laufen durch dieselben Postfächer.
- Maximale Gruppengröße (Start): 200 Mitglieder (Fan-out macht der Client).
- Verschwindende Nachrichten: Ablaufzeit pro Gruppe/Nachricht, clientseitig durchgesetzt.

## 4. Nachrichtenformat (im verschlüsselten Payload)

Alles Folgende liegt im MLS-Anwendungsnachricht-Payload (JSON, `core/src/message.rs`), der Server sieht nur Padding-Blobs.

| Typ | Felder |
|---|---|
| `text` | `body` (Markdown-Subset, **kein HTML**) |
| `code` | `lang`, `body` (Anzeige als Codeblock, Escape-sicher) |
| `quote` | `ref` (Nachrichten-ID), `snippet`, optional `body` (Antwort) |
| `file` | `blob_id`, `blob_server`, `key`, `nonce`, `name`, `mime`, `size`, `sha256` |
| `reaction` / `edit` / `delete` / `read` | `ref`, … (Lesebestätigung Default aus) |

Eine Nachricht kann mehrere Teile enthalten (z. B. Text + Zitat + mehrere Dateien).
Ausführbare Dateien (exe, doc, …) werden nie ausgeführt oder inline gerendert, nur zum Download angeboten.
Bilder werden clientseitig gerendert, Metadaten (EXIF) vor dem Senden entfernt.

## 5. Postfächer und Zustellung (Sealed Sender)

- Jeder Kontakt bekommt vom Empfänger ein **eigenes Postfach**: `mailbox_id` (128 Bit, zufällig) +
  `send_token` (Capability). Nur wer das Token hat, darf dort einwerfen, **ohne sich auszuweisen**.
  Der Server weiß daher nicht, wer sendet.
- `PUT  /v1/mailboxes/{mailbox_id}`  Body: Blob (auf Größenklasse aufgefüllt: 1 KiB, 4 KiB, 16 KiB, 64 KiB, …), Header `Send-Token`
- `GET  /v1/mailboxes/{id}/messages?after=…` und WebSocket `/v1/stream` (nur Besitzer, signiert)
- `DELETE …/messages/{seq}` nach Abruf; Ablaufzeit (Default 30 Tage).
- Postfach-Rotation: neue Postfächer werden in MLS-Nachrichten verteilt, alte gelöscht.
- **Kontaktaufnahme:** Ein „Intro-Postfach“ pro Konto (per Link/QR teilbar, Rate-Limited) nimmt Erstkontakt
  (MLS-Welcome-Anfrage) an. Im Allowlist-Modus kann es deaktiviert werden.

## 6. Föderation

- **Discovery:** `https://domain/.well-known/chat-server` → API-Basis-URL, Server-Public-Key, Version.
- **Zustellung an Remote-Server** auf zwei Wegen:
  1. *Relay (Default):* Client → eigener Home-Server → Remote-Server. Verbirgt die Client-IP vor dem Remote-Server.
  2. *Direkt:* Client → Remote-Server (z. B. über Tor).
  Server-zu-Server-Aufrufe (`POST /v1/federation/deliver`) sind mit dem Server-Schlüssel signiert
  (HTTP Message Signatures). Inhalt: `mailbox_id`, `send_token`, Blob. Der Remote-Server sieht nur die
  Herkunfts-Domain, nie den Nutzer.
- **Offene Föderation:** Server werden automatisch akzeptiert (Start). Schutz über Blockieren
  (Abschnitt 8) und Rate Limits. Betreiber können zusätzlich globale Block-/Allowlists setzen.
- Kein Server hat Autorität über Identitäten: Vertrauen kommt aus IK-Signaturen, nicht aus Domains.

## 7. Dateien

- Clientseitig mit zufälligem 256-Bit-Schlüssel verschlüsselt, in **64-KiB-Chunks**
  (XChaCha20-Poly1305, STREAM-Konstruktion; Reihenfolge und Ende sind authentifiziert).
- `POST /v1/blobs` (authentifizierter Upload, zählt auf das Konto-Kontingent),
  `GET /v1/blobs/{blob_id}` (unratbare ID als Capability). Der Server speichert nur Ciphertext.
- Schlüssel, Name, Typ und Größe reisen nur im MLS-Payload.
- **Limits** (alle per Umgebungsvariable/Config vom Betreiber änderbar):

| Variable | Default |
|---|---|
| `MAX_FILE_SIZE` | 100 MB pro Datei |
| `MAX_MESSAGE_ATTACHMENTS` | 10 Dateien pro Nachricht |
| `MAX_MESSAGE_TOTAL_SIZE` | 500 MB pro Nachricht |
| `MAX_MESSAGE_TEXT` | 64 KiB Text pro Nachricht |
| `USER_QUOTA` | 10 GB pro Nutzer |
| `BLOB_RETENTION` | 30 Tage |

- Die Dateien liegen auf dem Home-Server des Senders. Der Empfänger lädt direkt oder über seinen Server (Proxy-Option).

## 8. Blockieren und Allowlists

Alle Listen sind **clientseitig**, verschlüsselt und werden zwischen den eigenen Geräten synchronisiert.
Der Server kennt sie nicht.

- **Nutzer blockieren:** Postfach-Capability für diesen Kontakt wird widerrufen (serverseitig durchgesetzt,
  ohne dass der Server wissen muss, wer das ist) und zusätzlich clientseitig verworfen.
- **Server blockieren:** Der Client verwirft alle Nachrichten von Absendern dieser Domain. Optional liegt eine
  gehashte Domain-Liste beim Home-Server, die Nachrichten schon bei der Annahme nach Herkunfts-Server filtert (spart Bandbreite, gibt die Liste aber preis → optional).
- **Allowlist-Modus:** Es werden nur Kontakte und Server aus der eigenen Liste akzeptiert, das Intro-Postfach wird deaktiviert.
- Betreiber-Ebene: globale Block-/Allowlist für Föderation, Registrierungsmodus, Quoten.

## 9. Mehrere Geräte

- Jedes Gerät hat eigene Schlüssel (eigenes MLS-Leaf) und wird per QR-Code vom ersten Gerät hinzugefügt.
- Geräte eines Nutzers bilden eine private „Self-Gruppe“ zum Synchronisieren von Einstellungen/Blocklisten.
- **Phase 1:** ein Gerät pro Konto plus verschlüsselter Schlüsselexport als Backup.

## 10. API-Prinzipien

- REST (OpenAPI 3.1, `api/openapi.yaml`) + WebSocket für Echtzeit, versioniert unter `/v1`.
- Alle Payloads sind opaque Binärdaten, das Protokoll ist sprachunabhängig → Windows/Linux/Android/iOS-Clients möglich.
- Ein gemeinsamer Krypto-Kern (`core`, Rust) wird als WASM (Web), UniFFI (Android/iOS) und nativ (Desktop) eingebunden.
- `GET /v1/server-info` enthält den Hash des ausgelieferten Web-Bundles, damit native Clients/Add-ons ihn prüfen können.

## 11. Umsetzung: Abweichungen, Ergänzungen, Offenes

### Authentifizierung (implementiert)
`Authorization: Chat-Sig name=<n>,ts=<unix>,nonce=<b64>,sig=<b64>`; signiert wird
`"CHAT-REQ-V1\n<server-domain>\n<METHOD>\n<RequestURI>\n<ts>\n<nonce>\n<hex sha256(body)>"`.
Toleranz ±60 s, Nonce-Replay-Cache, Server-Domain im Signaturtext (kein Replay gegen andere Server).
Datei-Uploads verwenden `X-Body-Hash: UNSIGNED` (der Body ist ohnehin Ende-zu-Ende authentifiziert).
WebSocket: erste Nachricht `{name,ts,nonce,sig}` (Body-Hash `WS`).

### Äußere Umschlag-Schicht (neu gegenüber dem Entwurf)
MLS-Nachrichten enthalten die **Gruppen-ID im Klartext-Header**. Damit der Server Nachrichten derselben Gruppe
nicht verknüpfen kann, wird jeder Blob zusätzlich mit einem **Postfach-Schlüssel** (XChaCha20-Poly1305) umhüllt
(`core/src/envelope.rs`) und auf eine Größenklasse (Zweierpotenz, min. 256 B) aufgefüllt. Der Schlüssel ist Teil der
Postfach-Capability (`domain`, `mailbox_id`, `send_token`, `key`). Innen: `kind` (1 = Welcome, 2 = MLS), Gruppen-ID, Payload.

### Kontaktaufnahme
Der Kontaktlink `https://<host>/#/add/<base64url>` enthält Adresse und Intro-Capability (im URL-Fragment, wird nie an einen Server gesendet).
Ohne Link ist ein Nutzer nicht anschreibbar (Anti-Spam, Anonymität). Das Welcome landet als **Anfrage** beim Empfänger;
erst nach „Annehmen“ erfährt der Absender dessen Konversations-Postfach (Anfragen lassen sich ohne Rückmeldung ablehnen).

### Postfächer pro Unterhaltung, Verzeichnis
Jedes Mitglied hat pro Unterhaltung **ein** Empfangs-Postfach und kündigt es per MLS-Nachricht `directory` an.
Neu angekündigte Einträge werden von bereits verbundenen Mitgliedern **weitergereicht** (sonst kennen sich Mitglieder
ohne direkten Kontakt nicht). Regeln: Selbst-Ankündigungen überschreiben immer, fremde Einträge nur, wenn noch keiner bekannt ist.

### Blockieren
- *Nutzer (DM):* Postfach der Unterhaltung wird widerrufen (serverseitig durchgesetzt) und weitere Nachrichten werden verworfen.
- *Nutzer (Gruppe)* und *Server*: clientseitiges Verwerfen **nach** der MLS-Verarbeitung (der Zustand bleibt synchron).
- Absender erfahren nichts: der Server antwortet bei gefilterten Einwürfen mit `202` wie bei Erfolg.
- *Allowlist-Modus:* nur erlaubte Nutzer/Server und verifizierte Kontakte. Optional gehashte Domain-Liste beim Home-Server (`PUT /v1/filters`).

### Limits
Dateigröße, Kontingent, Aufbewahrung und Envelope-Größe erzwingt der **Server**. „Dateien pro Nachricht“, „Gesamtgröße pro Nachricht“
und „Text pro Nachricht“ erzwingt der **Client** (der Server sieht Nachrichten nicht); Werte stehen in `GET /v1/server-info`.

### Server ohne Domain (IP:PORT)
Adressen `name@1.2.3.4:8080` sind gültig. Server mit IP-Adresse werden per `http` angesprochen (Client und Föderation).
Ohne TLS ist der ausgelieferte Web-Client manipulierbar (siehe THREAT_MODEL); der Client nutzt deshalb für SHA-256 den WASM-Kern statt WebCrypto
(das in unsicheren Kontexten fehlt) und warnt in der Oberfläche. IPv6-Literale sind nicht unterstützt.

### Offen / bekannte Einschränkungen
- **Multi-Device** (Abschnitt 9): noch nicht umgesetzt (1 Gerät pro Konto, Backup-Datei).
- **Key Transparency** und **Post-Quanten-Ciphersuite**: nicht umgesetzt (Roadmap). Bis dahin: Safety Numbers vergleichen.
- **Lokale Schlüssel im Browser** liegen im IndexedDB verschlüsselt (Argon2id + XChaCha20-Poly1305), im Speicher aber entschlüsselt, solange die App entsperrt ist.
- **Gruppen:** jedes Mitglied darf Mitglieder hinzufügen/entfernen (kein Admin-Konzept); „Verlassen“ ist rein lokal.
- **Zustellung:** fehlgeschlagene Sendungen werden wiederholt (Outbox), es gibt aber keine Zustellbestätigungen.
- **Zeitstempel** sind sendergesteuert; **Reihenfolge** zwischen Absendern ist nicht kryptografisch garantiert.
- **Cover-Traffic**, Postfach-Rotation: nicht umgesetzt. Der Server sieht Zeitpunkt und (aufgefüllte) Größe jedes Einwurfs.
- **Datei-Downloads** gehen direkt zum Server des Absenders (nur nach Klick; zeigt dessen Server die IP des Empfängers).
- Dieser Code ist **nicht extern auditiert**.
