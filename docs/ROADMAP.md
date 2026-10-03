# Roadmap

| Phase | Inhalt | Stand |
|---|---|---|
| 0 | Spezifikation (dieses Verzeichnis) | ✅ |
| 1 | Repo-Skelett (Go-Server, Rust-Kern, Web), Docker, CI | ✅ (Dockerfile ungetestet im Build, siehe README) |
| 2 | Einzelserver-MVP: Einladung, Schlüssel-Login, 1:1-Chat (MLS) mit Text, Codeblöcken, Zitaten | ✅ |
| 3 | Gruppen, verschlüsselte Dateien, Quoten/Limits, Reaktionen/Bearbeiten/Löschen, verschwindende Nachrichten | ✅ |
| 4 | Föderation: Discovery, S2S-Zustellung, Relay, Registrierung auf fremden Servern | ✅ (getestet mit zwei Servern) |
| 5 | Blockieren (Nutzer/Server), Allowlist-Modus | ✅ · Postfach-Rotation ⬜ |
| 6 | Härtung | Padding ✅, Umschlag-Schicht ✅, SSRF-/Replay-Schutz ✅ · Multi-Device ⬜, Key Transparency ⬜, PQ-Hybrid ⬜, externer Review ⬜ |
| 7 | Native Clients | Android: Engine ✅ (getestet, Interop mit Web ✅), App-Hülle geschrieben, **nicht kompiliert** ⚠ · Windows ⬜ · Linux ⬜ · iOS ⬜ |

## Offene Fragen

1. Gruppengröße und Fan-out-Last für große Gruppen (aktuell: Client sendet an jedes Mitglied einzeln).
2. Dateien: Download-Proxy über den Home-Server des Empfängers (verbirgt dessen IP vor dem Sender-Server)?
3. Lesebestätigungen und Tipp-Anzeigen: aktuell nicht vorhanden (weniger Metadaten).
4. Cover-Traffic / Zufalls-Verzögerungen gegen Timing-Korrelation: ab wann?
5. Lizenz AGPL-3.0 bestätigen und `LICENSE` ergänzen (Volltext konnte in dieser Umgebung nicht geladen werden).
6. BIP39-Recovery-Phrase statt Backup-Datei.
7. Mehr Geräte pro Konto (MLS-Leaf pro Gerät, Self-Gruppe).
8. Admin-Rollen in Gruppen.
