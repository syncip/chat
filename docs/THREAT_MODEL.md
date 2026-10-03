# Bedrohungsmodell

## Schutzziele

1. **Vertraulichkeit gegenüber Servern (a):** Ein Server (eigener, fremder, kompromittierter oder
   durch Behörden gezwungener) sieht nie Klartext: keine Texte, Dateien, Dateinamen, Typen, Größen,
   Gruppenzugehörigkeiten oder Reaktionen.
2. **Staatliche Angreifer / Gerätebeschlagnahme (c):**
   - *Forward Secrecy:* gestohlene Langzeitschlüssel entschlüsseln keine alten Nachrichten.
   - *Post-Compromise Security:* nach einer Kompromittierung heilt sich die Sitzung (MLS-Updates).
   - Lokaler Speicher ist mit einer Passphrase verschlüsselt (Argon2id), optional verschwindende Nachrichten.
   - *Harvest-now-decrypt-later:* Hybrides Post-Quanten-Schlüsselaustausch-Verfahren ist eingeplant,
     sobald eine geprüfte MLS-Ciphersuite verfügbar ist (siehe Roadmap).
3. **Kompromittierter Server (d):**
   - Kann keine Nachrichten lesen oder unbemerkt verändern (Authenticated Encryption + MLS-Signaturen).
   - Kann Nachrichten **verzögern, verwerfen oder Metadaten zur Zustellung beobachten** (siehe unten).
   - Kann versuchen, falsche Schlüssel auszuliefern (MITM) → Schutz durch Safety Numbers /
     Schlüsselverifikation, Key-Pinning (TOFU) und später Key Transparency.

## Metadaten

| Information | Wer sieht sie | Gegenmaßnahme |
|---|---|---|
| Absender einer Nachricht | niemand außer Empfänger (Sealed Sender / Capability-Postfächer) | Absender steht nur im verschlüsselten Payload |
| Empfänger-Postfach | Server des Empfängers | pro-Kontakt-Postfächer mit zufälligen IDs, rotierbar |
| Nachrichtengröße | Server, Netzwerk | Padding in Größenklassen |
| Zeitpunkt | Server, Netzwerk | nicht vollständig verdeckbar; optional Cover-Traffic (später) |
| IP-Adresse des Clients | eigener Server, ggf. Remote-Server | Relay über Home-Server; Tor/Pangolin/VPN empfohlen |
| Zugehörigkeit zu Server | Nutzer-Home-Server | prinzipbedingt (Postfach muss existieren) |

## Nicht-Ziele / Restrisiken

- **Webapp-Code-Vertrauen:** Bei einer Webapp liefert der Server den Client aus. Ein böser Betreiber
  könnte manipulierten Code ausliefern und Schlüssel abgreifen. Für Phase 1 akzeptiert. Mildern:
  strikte CSP, SRI, reproduzierbare Builds mit veröffentlichtem Bundle-Hash, keine Fremd-Skripte.
  Das **eigentliche Ziel (d) erreichen native Clients** (Windows/Android) mit signierten Releases.
  Die Architektur trennt dafür den Krypto-Kern (`core`) von der UI, sodass er unverändert wiederverwendet wird.
- **Globaler passiver Angreifer** mit Netzwerk-Korrelation (Timing) wird nicht vollständig abgewehrt.
- **Kompromittiertes Endgerät** mit laufender App (Malware, Keylogger) ist außerhalb des Schutzes.
- **Cloudflare Tunnel:** Cloudflare terminiert TLS und sieht Verkehrsmetadaten (IP, Zeiten, Größen).
  Inhalte bleiben durch E2E geschützt. Wegen Ziel (a)/(c) wird **Pangolin** (selbst gehosteter Tunnel)
  oder ein Tor-Onion-Service empfohlen, Cloudflare Tunnel ist als Option dokumentiert.
- **Rechtliche/organisatorische Angriffe** (Zwang zur Herausgabe) liefern dem Server-Betreiber
  nur verschlüsselte Blobs und minimale Zustellmetadaten.

## Grundprinzipien

- Keine eigene Kryptografie: nur standardisierte Protokolle (MLS RFC 9420) und geprüfte Bibliotheken.
- Server ist „dumm“: speichert und leitet Blobs weiter, prüft nur Signaturen und Quoten.
- Keine IP-/Zugriffs-Logs per Default.
- Vor produktivem Einsatz: externer Krypto- und Sicherheitsreview.
