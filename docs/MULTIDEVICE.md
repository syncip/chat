# Multi-Device (Entwurf und Umsetzung)

Ziel: Ein Konto (`name@server`) kann auf mehreren Geräten **gleichzeitig** aktiv sein. Die Backup-Datei dient als Anmeldung auf einem neuen Gerät.

## Schlüssel

| Schlüssel | Wo | Zweck |
|---|---|---|
| **Account Identity Key (AIK)**, Ed25519 | in der Backup-Datei und auf jedem Gerät | Identität des Kontos (Safety Numbers, Kontakt-Pinning), beglaubigt Geräte, signiert Registrierung/Geräte-Hinzufügen |
| **Device Signing Key (DSK)**, Ed25519 | nur auf dem jeweiligen Gerät | MLS-Signaturschlüssel des Geräts (jedes Gerät ist ein eigenes MLS-Blatt), signiert API-Anfragen |
| **Gerätezertifikat** | im MLS-Credential | `sign_AIK("CHAT-DEVICE-V1\n<adresse>\n<geräte-id>\n<DSK-hex>")` |

MLS verlangt je Blatt einen eigenen Signaturschlüssel, deshalb reicht der AIK allein nicht.

Das **BasicCredential** enthält JSON `{a: adresse, k: AIK-hex, d: geräte-id, s: zertifikat-hex}`. Jeder Client prüft das Zertifikat bei KeyPackages,
beim Beitritt und nach jedem Commit (Blätter dürfen ihre Identität nicht ändern). Der AIK wird pro Kontakt per TOFU angepinnt.

## Server

- `devices(user, id, dpk, cert, revoked)`; Anfragen werden mit dem DSK signiert (`Authorization: Chat-Sig name=…,dev=…`).
- **Postfächer sind kontoweit.** Beim Einwurf kopiert der Server die Nachricht in die Warteschlange **jedes** aktiven Geräts (eigene Cursor/Acks pro Gerät).
  Der MLS-Chiffretext ist für alle Blätter derselbe, der Absender sendet also weiterhin nur **einmal pro Mitglieds-Konto**.
- **Geräte-Postfach („Inbox“)**: je Gerät ein Postfach nur für dieses Gerät, dessen ID/Token/Schlüssel **deterministisch aus dem AIK abgeleitet** werden
  (HKDF). Jedes Gerät desselben Kontos kann so ein Welcome an ein neues Gerät schicken, ohne dass der Server Geheimnisse sieht.
- `POST /v1/devices` (neues Gerät, Beglaubigung durch den AIK, keine Altgeräte nötig), `GET /v1/devices`, `DELETE /v1/devices/{id}`.
- `GET /v1/users/{name}/keypackages` liefert je aktivem Gerät ein KeyPackage.

## Clients

- **Gerät hinzufügen:** Backup-Datei → AIK → neues DSK + Zertifikat → `POST /v1/devices`. Das neue Gerät hat zunächst keine Gespräche.
- **Abgleich (Reconcile):** Bei Verbindung und bei `devices`-Ereignis vergleicht jedes Gerät die Geräteliste des Servers mit den Blättern des eigenen Kontos in jeder Gruppe.
  Das Gerät mit der kleinsten ID unter den bereits beteiligten fügt fehlende Geräte hinzu (Add-Commit, Welcome an deren Inbox, plus Verzeichnis)
  bzw. entfernt widerrufene. So braucht ein neues Gerät keinen Mitmenschen.
- **Eigene Nachrichten** gehen zusätzlich an das eigene Konto-Postfach, damit andere eigene Geräte sie sehen. Im Umschlag steht die Geräte-ID des Absenders,
  das sendende Gerät ignoriert seine eigene Kopie.
- Verlauf: neue Geräte sehen nur Nachrichten ab ihrem Beitritt (Forward Secrecy).
- Einstellungen, Blocklisten und Kontakte stehen im Backup (Stand der letzten Sicherung) und werden **nicht laufend synchronisiert** (offen).

## Backup-Datei

Verschlüsselt mit Passphrase (Argon2id): Adresse, AIK, Einstellungen/Kontakte (Schnappschuss), Intro-Postfach. **Kein MLS-Zustand** (deshalb kein Zustandsfork).
Pflicht nach der Registrierung; jederzeit neu speicherbar. Verlust aller Geräte: Backup einspielen → neues Gerät → alte Geräte widerrufen.

## Bekannte Einschränkungen

- Gleichzeitige Commits verschiedener Geräte können Epochen-Forks erzeugen (wie bei gleichzeitigen Commits mehrerer Mitglieder). Das Reconcile wählt deterministisch ein Gerät, verhindert es aber nicht vollständig.
- Ein gestohlenes Backup (plus Passphrase) erlaubt es, ein Gerät hinzuzufügen. Widerruf entfernt es, bereits gelesene Nachrichten bleiben gelesen.
