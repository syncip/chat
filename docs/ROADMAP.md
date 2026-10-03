# Roadmap

| Phase | Inhalt |
|---|---|
| 0 | Spezifikation (dieses Verzeichnis), Review und Abstimmung |
| 1 | Repo-Skelett (Go-Server, Rust-Core, Web), Docker, CI, OpenAPI-Grundgerüst |
| 2 | Single-Server-MVP: Einladung, Registrierung, Schlüssel-Login, 1:1-Chat (MLS) mit Text, Codeblöcken, Zitaten |
| 3 | Gruppen, verschlüsselte Dateien (Chunks), Quoten und Limits, Reaktionen/Edit/Delete, verschwindende Nachrichten |
| 4 | Föderation: Discovery, S2S-Zustellung, Relay, Registrierung auf fremden Servern |
| 5 | Blockieren (Nutzer/Server), Allowlist-Modus, Postfach-Rotation |
| 6 | Härtung: Padding, Multi-Device, Key Transparency, PQ-Hybrid-Ciphersuite, externer Review |
| 7 | Native Clients (Windows, Android, danach Linux, iOS) mit Client-Verifikation |

## Offene Fragen

1. Gruppengröße (Start 200) und Fan-out-Last für große Gruppen.
2. Dateien: Upload auf Sender-Server (Entwurf) oder Empfänger-Server? Proxy-Option standardmäßig an?
3. Lesebestätigungen und Tipp-Anzeigen: Default aus (weniger Metadaten) – okay?
4. Cover-Traffic / Zufalls-Verzögerungen gegen Timing-Korrelation: ab wann?
5. Lizenz AGPL-3.0 bestätigen.
