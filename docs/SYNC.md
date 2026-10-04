# Konto-Sync zwischen Geräten

Alle Geräte eines Kontos (Web, Android …) teilen sich: **Kanäle** (inkl. Schlüssel), **Einstellungen** (Filter, Bestätigungen, Direktversand …),
**Block-/Erlaubnislisten** und **Kontakte** (angepinnter Schlüssel, Verifizierung). Nicht synchronisiert werden Chats und Verlauf (das macht MLS Gerät für Gerät),
geräteeigene Einstellungen (Auto-Sperre, Passphrase-Länge, Tonschalter) und die Sitzung.

## Mechanismus

- Ein einziger Blob je Konto beim Home-Server: `GET/PUT /v1/sync` (Geräte-Signatur). Der Inhalt ist mit einem aus dem Konto-Schlüssel (AIK) abgeleiteten Schlüssel
  (`HKDF-SHA256(salt="chat-sync-v1", ikm=AIK-Bytes, info="account-sync")`) im Umschlag-Format (Art 4, XChaCha20-Poly1305, aufgefüllt) verschlüsselt. Der Server sieht nur Chiffretext (≤ 512 KiB).
- **Versioniert (Compare-and-Swap):** `PUT {base_version, data}` gelingt nur auf der aktuellen Version, sonst `409` mit der aktuellen Fassung → neu zusammenführen.
- **Zusammenführung je Eintrag, „last writer wins“:** Schlüssel `chan:<id>`, `set:<name>`, `blockU:`/`blockS:`/`allowU:`/`allowS:<wert>`, `contact:<adresse>`.
  Jeder Eintrag trägt einen Zeitstempel; Löschungen bleiben 30 Tage als Grabstein. Ein Gerät, das erstmals abgleicht, überschreibt nichts (Zeitstempel 0).
- Nach jedem `PUT` schickt der Server den anderen Geräten das WebSocket-Ereignis `sync`. Zusätzlich gleicht jedes Gerät beim Verbinden und alle 60 s ab.

Die Backup-Datei enthält weiterhin einen Schnappschuss (für die Anmeldung eines neuen Geräts); danach hält der Sync die Geräte aktuell.
