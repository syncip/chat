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
export CHAT_ADMIN_KEY=$(openssl rand -hex 24)
# deploy/docker-compose.yml: CHAT_DOMAIN auf den öffentlichen Hostnamen setzen
docker compose -f deploy/docker-compose.yml up -d --build
docker compose -f deploy/docker-compose.yml logs chat | grep Einladung   # erste Einladung (24 h gültig)
```

Weitere Einladungen: `curl -X POST -H "X-Admin-Key: $CHAT_ADMIN_KEY" https://DEINE-DOMAIN/v1/admin/invites`.
Betrieb hinter Pangolin oder Cloudflare Tunnel: siehe Kommentare in [deploy/docker-compose.yml](deploy/docker-compose.yml)
und den Hinweis zu Cloudflare in [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).

> Das Dockerfile konnte in der Entwicklungsumgebung nicht gebaut werden (kein Docker-Daemon). Die Einzelschritte
> (Rust→WASM, Web-Build, Go-Build) sind aber getestet; bitte den ersten Image-Build prüfen.

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
| `CHAT_TRUST_PROXY_HEADER` | – | Header mit Client-IP hinter Tunnel/Proxy (nur für Rate-Limits) |

## Dokumente

| Dokument | Inhalt |
|---|---|
| [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) | Wogegen der Chat schützt und wogegen nicht |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Identität, MLS, Postfächer, Föderation, Dateien, Blockieren, Abweichungen/Offenes (§11) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Komponenten, Tech-Stack, Hosting |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Phasen, Stand, offene Fragen |

Lizenz: AGPL-3.0 vorgesehen (LICENSE-Datei folgt nach Bestätigung).
