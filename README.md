# Chat

Ende-zu-Ende-verschlüsselter, föderierter Chat mit Fokus auf Sicherheit und Anonymität.
Erste Ausbaustufe: Webapp, per Docker selbst hostbar. Das Protokoll ist so gebaut, dass später Windows-, Linux-,
Android- und iOS-Clients dieselbe API und denselben Krypto-Kern nutzen können.

> **Status:** funktionsfähiger Prototyp (Phasen 0–5 umgesetzt, siehe [docs/ROADMAP.md](docs/ROADMAP.md)).
> **Nicht extern auditiert.** Nicht für Hochrisiko-Einsatz ohne Sicherheitsreview.

## Features

- **MLS (RFC 9420)** über OpenMLS: Forward Secrecy, Post-Compromise Security, Gruppen
- Text, Markdown-Subset, **Codeblöcke**, **Zitate**, **Dateien** (Bilder, exe/doc/… beliebig), Reaktionen, Bearbeiten, Löschen, verschwindende Nachrichten
- Alles verschlüsselt, auch Metadaten (Dateinamen, Typen, Größen, Gruppen); Server sieht nur aufgefüllte Blobs
- Identität = Ed25519-Schlüsselpaar, kein Telefon/E-Mail; Login per Signatur; Registrierung per Einladung
- **Föderation:** eigene Server betreiben, auf fremden Servern registrieren, serverübergreifend chatten
- **Blockieren** (Nutzer/Server), Allowlist-Modus, Anfragen-Prinzip für Erstkontakte
- Dateilimits (100 MB/Datei, 10 GB/Nutzer, pro Nachricht) vom Betreiber einstellbar

## Schnellstart

```bash
cp .env.example .env            # CHAT_DOMAIN und CHAT_ADMIN_KEY anpassen
docker compose up -d --build
docker compose logs chat | grep Einladung   # erste Einladung (24 h gültig)
```

Weitere Einladungen: `curl -X POST -H "X-Admin-Key: $CHAT_ADMIN_KEY" https://DEINE-DOMAIN/v1/admin/invites`.
Für öffentlichen Betrieb muss TLS davorstehen (beliebiger Reverse-Proxy). Pangolin und Cloudflare Tunnel sind **optionale
Zusatzdateien** in [deploy/](deploy/README.md), kein Teil der App. Zu Cloudflare siehe [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).

> Das Dockerfile konnte in der Entwicklungsumgebung nicht gebaut werden (kein Docker-Daemon). Die Einzelschritte
> (Rust→WASM, Web-Build, Go-Build) sind aber getestet; bitte den ersten Image-Build prüfen.

## Betrieb ohne Domain (IP:PORT)

Der Chat läuft auch ohne Domain und ohne TLS, z. B. im LAN oder VPN. Adressen lauten dann `alice@192.168.1.10:8080`.

```bash
# .env
CHAT_DOMAIN=192.168.1.10:8080      # IP:PORT, exakt so, wie die Nutzer den Server erreichen (Port = veröffentlichter Port)
CHAT_BIND=0.0.0.0:8080             # im LAN erreichbar (Standard ist nur 127.0.0.1)
CHAT_FEDERATION_ALLOW_PRIVATE=true # nur nötig, wenn mehrere Server in privaten Netzen föderieren sollen
```

- Server mit IP-Adresse werden automatisch über `http` angesprochen (für IPs gibt es keine TLS-Zertifikate).
- **Ohne TLS ist die App selbst angreifbar:** Nachrichten bleiben Ende-zu-Ende verschlüsselt, aber jemand im Netzwerk kann
  den ausgelieferten Client manipulieren und Schlüssel abgreifen. Die App zeigt dazu einen Warnhinweis. Nur in vertrauenswürdigen Netzen nutzen.
- Die Adresse ist Teil der Identität: Ändert sich die IP oder der Port, ändern sich alle Adressen (Konten ziehen nicht automatisch um).
- IPv6-Adressen werden noch nicht unterstützt. Hinter NAT/Port-Mapping muss `CHAT_DOMAIN` mit dem von außen erreichbaren `IP:PORT` übereinstimmen.

## Android-App (Kotlin, nativ)

`android/` enthält die native App (Jetpack Compose) mit dem Rust-Kern über UniFFI. Die Client-Logik liegt im reinen JVM-Modul
`android/engine` und wird gegen echte Server getestet (`./gradlew :engine:test -Pchatd=<pfad/chatd>`), inklusive Interop mit dem Web-Client
(`e2e/e2e-android-interop.mjs`). Sicherheit: Keystore-Zweitverschlüsselung (StrongBox), Passphrase (Argon2id), optional Biometrie,
`FLAG_SECURE`, kein Cloud-Backup, nur System-Zertifikate, Benachrichtigungen ohne Inhalt, Auto-Sperre.
Bauen: `scripts/build-android-core.sh` (NDK, cargo-ndk), dann `cd android && ./gradlew :app:assembleDebug`.
**Das Modul `:app` (UI, Keystore, Biometrie, Service) wurde noch nicht kompiliert** (kein Android SDK in der Entwicklungsumgebung);
der Workflow `.github/workflows/android.yml` baut es und zeigt eventuelle Fehler.

## Entwicklung

```bash
cd core   && cargo test                         # Krypto-Kern (Rust)
cd server && go test -race ./...                # Server (Go)
cd web    && npm run build:wasm && npm ci && npm test && npm run build
# Server mit Web-Client lokal:
cd server && CHAT_DOMAIN=localhost:8080 CHAT_WEB_DIR=../web/dist go run ./cmd/chatd
# End-to-End (zwei föderierte Server, drei Browser): siehe e2e/ und .github/workflows/ci.yml
```

Benötigt: Rust (+ `wasm32-unknown-unknown`, `wasm-bindgen-cli 0.2.129`), Go ≥ 1.26, Node ≥ 22.

## Konfiguration (Auszug, Umgebungsvariablen)

| Variable | Default | Bedeutung |
|---|---|---|
| `CHAT_DOMAIN` | `localhost:8080` | öffentlicher Name des Servers (Teil der Adressen) |
| `CHAT_REGISTRATION` | `invite` | `invite` / `open` (mit Proof-of-Work) / `closed` |
| `CHAT_FEDERATION` | `open` | `open` / `allowlist` / `closed`; `CHAT_FEDERATION_ALLOW`, `CHAT_FEDERATION_BLOCK` |
| `CHAT_MAX_FILE_SIZE` | 100 MB | pro Datei |
| `CHAT_USER_QUOTA` | 10 GB | pro Nutzer |
| `CHAT_MAX_MESSAGE_ATTACHMENTS` / `…_TOTAL_SIZE` | 10 / 500 MB | pro Nachricht (clientseitig durchgesetzt) |
| `CHAT_BLOB_RETENTION_DAYS`, `CHAT_MESSAGE_RETENTION_DAYS` | 30 | Aufbewahrung |
| `CHAT_FEDERATION_ALLOW_PRIVATE` | `false` | Föderation mit privaten/lokalen Adressen (LAN) erlauben |
| `CHAT_TRUST_PROXY_HEADER` | – | Header mit Client-IP hinter Tunnel/Proxy (nur für Rate-Limits) |

## Dokumente

| Dokument | Inhalt |
|---|---|
| [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) | Wogegen der Chat schützt und wogegen nicht |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Identität, MLS, Postfächer, Föderation, Dateien, Blockieren, Abweichungen/Offenes (§11) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Komponenten, Tech-Stack, Hosting |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Phasen, Stand, offene Fragen |

Lizenz: AGPL-3.0 vorgesehen (LICENSE-Datei folgt nach Bestätigung).
