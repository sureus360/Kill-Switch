# Router Kill Switch Backend

Dieses Backend laeuft auf einem Proxmox-Server, einer VM, einem LXC-Container
oder per Docker Compose. Es uebernimmt FRITZ!Box-Scan, Sperren, Entsperren und
Timer. Die Android-App steuert dann nur noch dieses Backend.

## Warum

Android kann Hintergrund-Jobs, Alarme und Netzwerkzugriff verzögern oder
beenden. Ein Proxmox-Server ist dauerhaft an und kann geplante Freigaben
zuverlaessiger ausfuehren.

## Schnellstart mit Docker Compose

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

Healthcheck:

```bash
curl http://SERVER-IP:8765/health
```

Geraete pruefen:

```bash
curl -H "Authorization: Bearer DEIN_TOKEN" http://SERVER-IP:8765/devices
```

## Android-App konfigurieren

In der App `[ CONFIG ]` oeffnen und eintragen:

- `Backend-URL`: `http://SERVER-IP:8765`
- `Backend API-Token`: Wert aus `RKS_TOKEN`

Wenn eine Backend-URL gesetzt ist, nutzt die App das Backend und ignoriert die
lokalen FRITZ!Box-Zugangsdaten auf dem Handy.

## Sicherheit

Das Backend sollte nur im Heimnetz oder ueber VPN erreichbar sein. Nicht ohne
Reverse Proxy, TLS und starke Zugriffskontrolle direkt ins Internet freigeben.
