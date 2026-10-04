# Öffentliche Kanäle

Telegram-ähnliche Kanäle, die jeder mit dem **Einladungslink** betreten kann. Server-gestützt: der Server verteilt Chiffretext und
**erzwingt Rechte, Sperren, Timeouts und Beitrittsregeln**, sieht aber keine Inhalte.

## Schlüssel und Link

- Der Kanalschlüssel `K` (32 Byte) wird beim Erstellen im Client erzeugt und steht **nur im Link-Fragment**:
  `https://server/#/join/<base64url {s: Server, c: Kanal-ID, k: K}>`. Das Fragment wird nie an einen Server gesendet.
- Titel und Beiträge sind mit `K` verschlüsselt (XChaCha20-Poly1305, Größenklassen-Padding, Kennung „Kanal“ + Kanal-ID im Umschlag).
- Beiträge sind mit dem **Konto-Schlüssel (AIK)** des Autors signiert (`CHAT-POST-V1\n<kanal>\n<post-id>\n<ts>\n<epoche>\n<sha256(daten)>`).
  Clients prüfen die Signatur; der Server kann Beiträge weder lesen noch unbemerkt untereinander vertauschen.
- Mitglieder werden über ihren AIK identifiziert. Die angezeigte Adresse prüft der Server beim Beitritt beim Heimatserver des Nutzers
  (`GET /v1/users/{name}`), sodass niemand eine fremde Adresse vortäuschen kann.

## Beitrittsregeln (Betreiber/Besitzer, pro Kanal einstellbar)

| Modus | Ablauf |
|---|---|
| `open` | direkter Zugriff |
| `approval` | Beitritt wird „wartend“, die Moderation gibt frei oder lehnt ab |
| `pow` | Client löst `sha256(kanal ":" ik ":" nonce)` mit `pow_bits` führenden Null-Bits |
| `captcha` | zustandsloses Bild-Captcha (HMAC-Token, 10 Min gültig, an Kanal und Schlüssel gebunden) |

Zusätzlich: **Sperrfrist für Neue** (`probation_seconds`: erst nach x Minuten/Stunden/Tagen schreiben) und **Slow-Mode** (Mindestabstand zwischen Beiträgen).

## Rechte

- **Global** (Besitzer): `members_can_write` (aus = Kanal für normale Mitglieder „nur lesen“).
- **Individuell** je Mitglied: Rolle `read` (nur lesen), `member` (folgt der globalen Regel), `write` (lesen + schreiben, auch bei globalem „nur lesen“ und während der Sperrfrist),
  `mod` (Moderation), `owner`. **Timeout** (stumm bis Zeitpunkt) blockiert Schreiben für alle außer Moderation.
- **Moderation**: Beiträge löschen (Tombstone), Mitglieder sperren/entsperren/entfernen, Timeout, Rollen `read|member|write` vergeben, Beitritte freigeben/ablehnen.
  Moderatoren können weder Besitzer noch andere Moderatoren moderieren; Moderatoren ernennt nur der Besitzer.
- Alle Moderationsereignisse stehen im für Mitglieder sichtbaren Kanal-Protokoll.

## Server-API (Auszug)

Authentifizierung für Mitglieder: `Authorization: Chan-Sig ik=<b64>,ts=,nonce=,sig=`, signiert über
`CHAT-CHAN-V1\n<domain>\n<METHOD>\n<URI>\n<ts>\n<nonce>\n<hex sha256(body)>` mit dem AIK (Replay-Schutz wie bei Chat-Sig).

| Endpunkt | Wer |
|---|---|
| `POST /v1/channels` (Gerätesignatur) | Konto dieses Servers → Besitzer |
| `GET /v1/channels/{id}` | öffentlich: verschlüsselter Titel, Richtlinie, Mitgliederzahl |
| `GET /v1/channels/{id}/captcha?ik=` | öffentlich |
| `POST .../join`, `GET .../log?after=`, `POST .../posts`, `POST .../leave` | Mitglieder |
| `GET .../stream` (WebSocket, erste Nachricht = Signatur, Body-Hash `WS`) | Mitglieder, meldet neue Log-Einträge |
| `POST .../mod`, `GET .../members` | Moderation |
| `PUT .../settings`, `DELETE /v1/channels/{id}` | Besitzer |

Betreiber-Konfiguration: `CHAT_CHANNELS` (an/aus), `CHAT_MAX_CHANNELS` (je Nutzer), `CHAT_MAX_CHANNEL_MEMBERS`, `CHAT_MAX_POST_SIZE`, `CHAT_CHANNEL_RETENTION_DAYS`.

## Öffentliche Kanäle (ohne Konto lesbar)

Beim Erstellen lässt sich ein Kanal als **öffentlich** markieren (später nicht änderbar). Beiträge sind dann **unverschlüsselt** und für jeden lesbar, auch ohne Konto:
Web-Lese-Link `https://server/#/c/<…>`, `GET /v1/channels/{id}/public/log` und ein Live-Feed `GET /v1/channels/{id}/public/events` (Server-Sent Events, z. B. `curl -N`).
Die App weist an mehreren Stellen ausdrücklich darauf hin. Schreiben dürfen weiterhin nur Mitglieder mit Konto (signierte Beiträge). Webhooks siehe [NTFY.md](NTFY.md).

## Grenzen (ehrlich)

- Der Server sieht Metadaten: Mitglieder (AIK + Adresse), Beitragszeiten, wer schreibt, Rollen. Inhalte sieht er nicht.
- **Keine Schlüsselrotation (v1):** Wer den Link kennt, hat `K` dauerhaft. Eine Sperre sperrt Lesen/Schreiben **am Server**, aber ein gesperrter Nutzer,
  der Chiffretext von einem bösen Server erhält, könnte ihn entschlüsseln. Ein bösartiger Server kann außerdem Beiträge zurückhalten oder löschen.
  Für sensible Gruppen die privaten MLS-Gruppen nutzen. Rotation bei Sperren ist für v2 vorgesehen.
- Kanäle liegen auf dem Server, der sie erstellt hat (der Link nennt ihn); Mitglieder anderer Server treten per Link bei.
- Der Beitritt offenbart dem Kanal-Server die IP und die Adresse des Nutzers; Tor/VPN empfohlen.
- Dateianhänge in Kanälen (Bilder, Audio, Video, beliebige Dateien): Die Datei wird wie im Chat Ende-zu-Ende verschlüsselt auf den Heimserver des Absenders hochgeladen (Blob-Aufbewahrung und Kontingent gelten); der Dateischlüssel steht im (kanalverschlüsselten) Beitrag. In **öffentlichen** Kanälen liegt dieser Schlüssel im Klartext-Beitrag, die Datei ist damit für jeden lesbar. Webhooks können keine Dateien senden (nur Text).
- Der Besitzer ist ein Konto des Kanal-Servers; verliert er den AIK (Backup!), ist der Kanal nicht mehr verwaltbar.

## Sichtbarkeit nachträglich ändern (v0.2)

Der Besitzer kann einen Kanal in den Einstellungen von **privat** auf **öffentlich** und zurück stellen.

- **privat → öffentlich:** neue Beiträge sind unverschlüsselt und ohne Konto lesbar; ältere verschlüsselte Beiträge bleiben verschlüsselt (Mitglieder lesen sie mit dem früheren Schlüssel).
- **öffentlich → privat:** es entsteht ein **neuer Kanalschlüssel**. Der öffentliche Link funktioniert nicht mehr, Mitglieder fügen den neuen Einladungslink ein (Hinweis im Kanal). Ältere öffentliche Beiträge bleiben auf dem Server im Klartext, Mitglieder können sie weiter lesen. Webhooks erhalten den neuen Schlüssel automatisch.
- Gruppen sind immer privat (Ende-zu-Ende per MLS); ihr Name, Bild, Verschwinde-Timer und die Mitglieder lassen sich jederzeit ändern.

Wartende Beitrittsanfragen (Modus „Freigabe“) werden Besitzer und Moderation im Kanal als ausblendbarer Hinweis angezeigt.
