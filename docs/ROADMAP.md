# Roadmap

| Phase | Inhalt | Stand |
|---|---|---|
| 0 | Spezifikation (dieses Verzeichnis) | ✅ |
| 1 | Repo-Skelett (Go-Server, Rust-Kern, Web), Docker, CI | ✅ (Dockerfile ungetestet im Build, siehe README) |
| 2 | Einzelserver-MVP: Einladung, Schlüssel-Login, 1:1-Chat (MLS) mit Text, Codeblöcken, Zitaten | ✅ |
| 3 | Gruppen, verschlüsselte Dateien, Quoten/Limits, Reaktionen/Bearbeiten/Löschen, verschwindende Nachrichten | ✅ |
| 4 | Föderation: Discovery, S2S-Zustellung, Relay, Registrierung auf fremden Servern | ✅ (getestet mit zwei Servern) |
| 5 | Blockieren (Nutzer/Server), Allowlist-Modus | ✅ · Postfach-Rotation ⬜ |
| 6 | Härtung | Padding ✅, Umschlag-Schicht ✅, SSRF-/Replay-Schutz ✅ · Multi-Device ✅, Key Transparency ⬜, PQ-Hybrid ⬜, externer Review ⬜ |
| 6b | Öffentliche Kanäle (Link-Beitritt, Rechte, Moderation), Bestätigungen, Einmal-Nachrichten, einklappbarer Code | Server ✅, Web ✅, Kotlin-Engine ⬜, Android-UI ⬜ (siehe docs/CHANNELS.md) |
| 7 | Native Clients | Android: Engine ✅ (getestet, Interop mit Web ✅), App-Hülle geschrieben, **nicht kompiliert** ⚠ · Windows ⬜ · Linux ⬜ · iOS ⬜ |

## Offene Fragen

1. Gruppengröße und Fan-out-Last für große Gruppen (aktuell: Client sendet an jedes Mitglied einzeln).
2. Dateien: Download-Proxy über den Home-Server des Empfängers (verbirgt dessen IP vor dem Sender-Server)?
3. Tipp-Anzeigen: nicht vorhanden (weniger Metadaten). Zustell-/Lesebestätigungen sind optional (Standard aus, gegenseitig).
4. Cover-Traffic / Zufalls-Verzögerungen gegen Timing-Korrelation: ab wann?
5. Lizenz AGPL-3.0 bestätigen und `LICENSE` ergänzen (Volltext konnte in dieser Umgebung nicht geladen werden).
6. BIP39-Recovery-Phrase statt Backup-Datei.
7. Laufende Synchronisation von Einstellungen/Kontakten zwischen Geräten (Self-Gruppe).
8. Admin-Rollen in privaten Gruppen.
9. Kanäle: Schlüsselrotation bei Sperren, Dateianhänge, Mitglieder-Anzeigenamen.
