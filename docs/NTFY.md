# Webhooks für Kanäle (ntfy-kompatibel)

Mit einem Webhook können andere Programme **Benachrichtigungen in einen Kanal schicken**, so wie man es von [ntfy](https://ntfy.sh) kennt:
ein einfacher HTTP-Aufruf genügt – kein Konto, keine Bibliothek. Typische Absender: Skripte und Cronjobs, Monitoring (Uptime Kuma, Grafana, Prometheus Alertmanager),
Router/NAS, Home Assistant, CI/CD-Pipelines, Backup-Tools.

## So richtest du es ein

1. Öffne den Kanal → **ⓘ Details** (du musst Besitzer oder Moderator sein) → Abschnitt **Webhooks**.
2. Namen eingeben (z. B. „Monitoring“) → **Webhook anlegen**.
3. Die Adresse (`https://dein-server/h/<TOKEN>`) wird **nur jetzt** angezeigt. Kopiere sie sofort.
4. Trage sie in der sendenden Anwendung ein. Zum **Löschen** klickst du beim Webhook auf „Löschen“; die Adresse ist dann sofort wertlos.

Das Token ist das Geheimnis: Wer die Adresse kennt, kann in den Kanal schreiben. Der Server speichert nur einen Hash davon. Pro Kanal sind standardmäßig 5 Webhooks möglich (Admin einstellbar).

## Beispiele

```bash
# einfache Nachricht
curl -d "Backup fertig" https://dein-server/h/<TOKEN>

# mit Titel, Priorität und Tags – wie bei ntfy
curl -H "Title: Nachtlauf" -H "Priority: high" -H "Tags: white_check_mark,backup" \
     -d "Alles gesichert" https://dein-server/h/<TOKEN>

# JSON
curl -H "Content-Type: application/json" \
     -d '{"title":"Alarm","message":"Server down","priority":5,"tags":["rotating_light"],"click":"https://status.example.org"}' \
     https://dein-server/h/<TOKEN>

# per GET (z. B. für einfache Dienste)
curl "https://dein-server/h/<TOKEN>/publish?message=Tür+offen&title=Haus&tags=house"
```

## Was unterstützt wird

| ntfy-Feld | Verhalten |
|---|---|
| Nachricht (Body, `message`/`m`) | Text der Nachricht (max. 4 KiB) |
| `Title` / `X-Title` / `t` | wird fett dargestellt |
| `Priority` / `X-Priority` / `p` (1–5, `min`…`urgent`) | 4 = ❗, 5 = 🚨 vor dem Titel |
| `Tags` / `X-Tags` / `ta` | bekannte Emoji-Namen (`warning`, `white_check_mark`, `rotating_light`, `tada`, `skull` …) werden zu Symbolen, andere zu `#tag` |
| `Click` / `X-Click` | `https://`-Link wird angehängt |
| JSON-Body (`title`, `message`, `priority`, `tags`, `click`) | wie ntfy; `topic` wird ignoriert (das Token bestimmt den Kanal) |
| `GET /h/<TOKEN>/publish`, `/send`, `/trigger` | wie bei ntfy |

**Nicht unterstützt:** Anhänge (`Attach`, Dateiupload), Aktionsknöpfe (`Actions`), Zeitplanung (`Delay`), `Icon`, Markdown-Umschalter, Abonnieren/Streamen über ntfy-Clients (die Nachrichten liest man im Kanal der Chat-App).
Die Antwort entspricht dem ntfy-Format (`{"id":…,"event":"message","topic":…,"message":…}`), sodass bestehende Tools den Erfolg erkennen.

## Sicherheit – bitte lesen

- Webhook-Nachrichten kommen vom Absender **im Klartext per HTTP(S)** beim Server an. Nutze für externe Absender TLS (https).
- **Öffentliche Kanäle** sind ohnehin unverschlüsselt und für jeden lesbar.
- **Nicht öffentliche Kanäle** sind Ende-zu-Ende-verschlüsselt. Damit der Server Webhook-Nachrichten verschlüsseln kann, **erhält er beim Anlegen des Webhooks den Kanalschlüssel**.
  Der Server (und jeder, der den Server kontrolliert) kann dann diesen Kanal mitlesen. Wer das nicht will, legt keinen Webhook an oder nutzt einen eigenen, öffentlichen Kanal nur für Benachrichtigungen.
  Löschst du alle Webhooks eines Kanals, speichert der Server keinen Schlüssel mehr (bereits gelesene Inhalte bleiben natürlich bekannt).
- Webhook-Beiträge sind in der App mit 🔔 und dem Namen des Webhooks gekennzeichnet und **nicht** von einem Mitglied signiert (der Server verfasst sie). Moderatoren können sie wie andere Beiträge löschen.
