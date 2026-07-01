# Router Kill Switch Backend

Dieses Backend laeuft auf einem Proxmox-Server, einer VM, einem LXC-Container
oder per Docker Compose. Es uebernimmt FRITZ!Box-Scan, Sperren, Entsperren und
Timer. Die Android-App steuert dann nur noch dieses Backend.

## Warum

Android kann Hintergrund-Jobs, Alarme und Netzwerkzugriff verzögern oder
beenden. Ein Proxmox-Server ist dauerhaft an und kann geplante Freigaben
zuverlaessiger ausfuehren.

## Schnellstart mit Docker Compose

Voraussetzungen:

- Docker Engine mit Compose-Plugin (`docker compose version`)
- Der Server muss die FRITZ!Box lokal erreichen koennen, z.B. per LAN, WLAN,
  VM-Bridge oder LXC-Bridge im Heimnetz.
- In der FRITZ!Box muss `Zugriff fuer Anwendungen` aktiviert sein.

```bash
cd backend
cp .env.example .env
nano .env
docker compose up -d --build
```

Wichtige Variablen:

- `RKS_TOKEN`: langer zufaelliger API-Token fuer die Handy-App
- `RKS_FRITZ_HOST`: z.B. `fritz.box` oder `192.168.178.1`
- `RKS_FRITZ_USER`: FRITZ!Box-Benutzer mit App-/TR-064-Rechten
- `RKS_FRITZ_PASSWORD`: Passwort dieses FRITZ!Box-Benutzers
- `RKS_PORT`: Standard `8765`

Empfohlenes Token erzeugen:

```bash
openssl rand -hex 32
```

Healthcheck:

```bash
curl http://SERVER-IP:8765/health
```

Geraete pruefen:

```bash
curl -H "Authorization: Bearer DEIN_TOKEN" http://SERVER-IP:8765/devices
```

Logs ansehen:

```bash
docker compose logs -f router-kill-switch
```

Backend aktualisieren:

```bash
git pull
cd backend
docker compose up -d --build
```

Backend stoppen:

```bash
cd backend
docker compose down
```

Die SQLite-Datenbank fuer geplante Freigaben liegt persistent unter
`backend/data/router-kill-switch.db`. Dieser Ordner wird durch
`docker-compose.yml` als Volume in den Container gemountet.

Wenn Sperren/Freigeben mit `FRITZ!Box-Fehler 401: Invalid Action` fehlschlaegt,
stellt die FRITZ!Box/Firmware die benoetigte TR-064-HostFilter-Aktion nicht
bereit. Dann FRITZ!OS aktualisieren oder ein Modell/Firmwarestand nutzen, dessen
HostFilter-Dienst `DisallowWANAccessByIP` und `GetWANAccessByIP` anbietet.

## Android-App konfigurieren

In der App `[ CONFIG ]` oeffnen und eintragen:

- `Backend-URL`: `http://SERVER-IP:8765`
- `Backend API-Token`: Wert aus `RKS_TOKEN`

Wenn eine Backend-URL gesetzt ist, nutzt die App das Backend und ignoriert die
lokalen FRITZ!Box-Zugangsdaten auf dem Handy.

## Sicherheit

Das Backend sollte nur im Heimnetz oder ueber VPN erreichbar sein. Nicht ohne
Reverse Proxy, TLS und starke Zugriffskontrolle direkt ins Internet freigeben.
