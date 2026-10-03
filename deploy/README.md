# Optionale Tunnel

Der Chat-Server ist **unabhängig von jedem Tunnel**: Er spricht HTTP auf Port 8080 und kennt weder Cloudflare noch
Pangolin. Die Dateien hier sind reine Beispiele, die du bei Bedarf zusätzlich einbindest. Ohne sie läuft alles wie gehabt.

| Datei | Zweck |
|---|---|
| `docker-compose.cloudflare.yml` | Cloudflare Tunnel (`cloudflared`) |
| `docker-compose.pangolin.yml` | Pangolin (`newt`) |

```bash
docker compose -f docker-compose.yml -f deploy/docker-compose.pangolin.yml up -d
```

Hinter einem Tunnel/Proxy sieht der Server nur dessen IP. Damit Rate-Limits pro Nutzer-IP greifen, kann optional
`CHAT_TRUST_PROXY_HEADER` auf den Header mit der Client-IP gesetzt werden (`X-Forwarded-For`, bei Cloudflare `CF-Connecting-IP`).
Ohne Proxy leer lassen.
