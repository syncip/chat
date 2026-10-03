# Architektur

## Komponenten

```
┌──────────────┐   REST/WS    ┌──────────────┐  signierte S2S-Calls  ┌──────────────┐
│ Web-Client   │◄────────────►│ Server A     │◄─────────────────────►│ Server B     │
│ (TS + WASM)  │              │ (Go)         │                       │ (Go)         │
└──────┬───────┘              └──────┬───────┘                       └──────────────┘
       │ nutzt                       │ speichert nur Blobs
┌──────▼───────┐              ┌──────▼───────┐
│ core (Rust)  │              │ SQLite/PG +  │
│ OpenMLS,     │              │ Blob-Storage │
│ Datei-Krypto │              └──────────────┘
└──────────────┘
```

- **server/** (Go): Registrierung, Auth, KeyPackages, Postfächer, Blobs, Föderation, Quoten. Keine Krypto außer Signaturprüfung.
- **core/** (Rust): MLS, Schlüsselverwaltung, Datei-Verschlüsselung, Nachrichtenformat. Wiederverwendet von allen Clients.
- **web/** (TypeScript, React, Vite): UI, PWA, lädt `core` als WASM.
- **api/**: OpenAPI-Spezifikation.
- **Dockerfile**, **docker-compose.yml**, **.env.example** im Repo-Root; **deploy/**: optionale Tunnel-Beispiele (Cloudflare, Pangolin), nicht Teil der App.

## Tech-Entscheidungen

| Thema | Wahl | Grund |
|---|---|---|
| Server | Go | ein statisches Binary, einfaches Docker-Image |
| Krypto-Kern | Rust + OpenMLS | geprüfte MLS-Implementierung, WASM/UniFFI-fähig |
| Web | TypeScript/React | Ökosystem, PWA |
| Datenbank | SQLite (Default), optional Postgres | einfaches Selbsthosten |
| Blobs | Dateisystem, optional S3-kompatibel | |
| Lizenz | AGPL-3.0 (Vorschlag) | Betreiber müssen Änderungen offenlegen |

## Hosting

- Ein Docker-Image, Konfiguration über Umgebungsvariablen (Limits siehe PROTOCOL.md §7).
- Der Server spricht HTTP und ist **unabhängig von jedem Tunnel/Proxy**. Für öffentlichen Betrieb muss TLS davorstehen;
  das kann ein beliebiger Reverse-Proxy oder ein **optionaler** Tunnel sein (Beispiele für Pangolin und Cloudflare in `deploy/`).
  WebSocket-Unterstützung ist erforderlich.
- Hinweis: Bei Cloudflare sieht Cloudflare Verkehrsmetadaten, siehe THREAT_MODEL.md.
- Keine IP-/Access-Logs per Default.

## Vorbereitung auf native Clients

- Protokoll und Krypto sind UI-unabhängig (`core`, OpenAPI).
- Das Web-Bundle wird reproduzierbar gebaut, der Hash steht in `/v1/server-info`.
- Native Clients (Windows, Android) verifizieren Server und Schlüssel selbst und umgehen damit das Webapp-Restrisiko.

## Repo-Layout (geplant)

```
chat/
├─ api/openapi.yaml
├─ core/        (Rust)
├─ server/      (Go)
├─ web/         (TS/React)
├─ Dockerfile, docker-compose.yml
├─ deploy/      (optionale Tunnel-Beispiele)
└─ docs/
```
