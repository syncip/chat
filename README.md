# Chat

Ende-zu-Ende-verschlüsselter, föderierter Chat mit Fokus auf Sicherheit und Anonymität.
Erste Ausbaustufe: Webapp, per Docker selbst hostbar. Das Protokoll ist so gebaut,
dass später Windows-, Linux-, Android- und iOS-Clients dieselbe API nutzen können.

> Status: **Phase 0 – Spezifikation.** Es gibt noch keinen Code. Die Dokumente unten
> sind der Entwurf, der vor der Implementierung geprüft und abgestimmt wird.

## Ziele

- Alles ist Ende-zu-Ende verschlüsselt: Texte, Codeblöcke, Zitate, Dateien **und** Metadaten
  (Dateinamen, Typen, Größen, Gruppen, Absender).
- Server und Betreiber gelten als **nicht vertrauenswürdig** (auch kompromittiert oder staatlich gezwungen).
- Kein Zwang zu Telefonnummer, E-Mail oder Klarnamen. Identität = Schlüsselpaar.
- Föderation: Jeder kann einen eigenen Server betreiben. Nutzer können sich auch auf fremden Servern registrieren.
- Nutzer können andere Nutzer und ganze Server blockieren oder einen Allowlist-Modus nutzen.

## Dokumente

| Dokument | Inhalt |
|---|---|
| [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) | Wogegen der Chat schützt und wogegen nicht |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Identität, MLS, Postfächer, Föderation, Dateien, Blockieren |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Komponenten, Tech-Stack, Repo-Layout, Hosting |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Phasen und offene Fragen |

## Tech-Stack (geplant)

Server in **Go**, gemeinsamer Krypto-Kern in **Rust** (OpenMLS, als WASM für das Web),
Web-Client in **TypeScript/React**, Speicher **SQLite** (optional Postgres), Auslieferung per **Docker**.
Lizenz: **AGPL-3.0** (vorgeschlagen, LICENSE-Datei folgt mit der Bestätigung).
