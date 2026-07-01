#!/usr/bin/env python3
import base64
import hashlib
import json
import os
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


VERSION = "1.2.1"
RETRY_DELAY_SECONDS = 300
ACTION_DISALLOW_WAN_BY_IP = "DisallowWANAccessByIP"
ACTION_GET_WAN_BY_IP = "GetWANAccessByIP"


def blank(value):
    return value is None or str(value).strip() == ""


def now_ms():
    return int(time.time() * 1000)


def compact(value, limit=180):
    if blank(value):
        return "Keine Antwort"
    result = " ".join(str(value).split())
    return result if len(result) <= limit else result[:limit] + "..."


def xml_text(root, local_name):
    for element in root.iter():
        if element.tag.split("}")[-1].split(":")[-1] == local_name:
            return (element.text or "").strip()
    return ""


def child_text(element, local_name):
    for child in list(element):
        if child.tag.split("}")[-1].split(":")[-1] == local_name:
            return (child.text or "").strip()
    return ""


def parse_xml(xml):
    upper = xml.upper()
    if "<!DOCTYPE" in upper or "<!ENTITY" in upper:
        raise RuntimeError("Unsichere XML-Antwort von der FRITZ!Box abgelehnt.")
    return ET.fromstring(xml)


def normalize_base_url(value):
    candidate = (value or "fritz.box").strip()
    if not candidate.startswith(("http://", "https://")):
        candidate = "http://" + candidate
    parsed = urllib.parse.urlparse(candidate)
    if blank(parsed.hostname):
        raise RuntimeError("Ungueltige Router-Adresse.")
    port = parsed.port
    if port is None:
        port = 49443 if parsed.scheme == "https" else 49000
    return f"{parsed.scheme}://{parsed.hostname}:{port}"


class FritzClient:
    def __init__(self, host, username, password):
        self.base_url = normalize_base_url(host)
        password_mgr = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        password_mgr.add_password(None, self.base_url, username, password)
        self.opener = urllib.request.build_opener(urllib.request.HTTPDigestAuthHandler(password_mgr))
        self.hosts_service = None
        self.host_filter_service = None

    def load_devices(self):
        self._discover_services()
        count_response = self._soap(self.hosts_service, "GetHostNumberOfEntries", {})
        try:
            count = min(512, int(xml_text(count_response, "NewHostNumberOfEntries")))
        except ValueError:
            count = 0
        devices = []
        for index in range(count):
            try:
                response = self._soap(
                    self.hosts_service,
                    "GetGenericHostEntry",
                    {"NewIndex": str(index)},
                )
                ip = xml_text(response, "NewIPAddress")
                if blank(ip):
                    continue
                blocked = False
                try:
                    blocked = self._get_access_state(ip)["blocked"]
                except Exception:
                    pass
                devices.append(
                    {
                        "name": xml_text(response, "NewHostName") or "UNBEKANNTES GERAET",
                        "ipAddress": ip,
                        "macAddress": xml_text(response, "NewMACAddress"),
                        "interfaceType": xml_text(response, "NewInterfaceType"),
                        "active": xml_text(response, "NewActive") == "1",
                        "blocked": blocked,
                    }
                )
            except Exception:
                pass
        devices.sort(key=lambda item: (not item["active"], item["name"].lower()))
        return devices

    def set_blocked(self, device, blocked):
        self._discover_services()
        self._require_action(self.host_filter_service, ACTION_DISALLOW_WAN_BY_IP)
        self._require_action(self.host_filter_service, ACTION_GET_WAN_BY_IP)
        target_ip = self._resolve_current_ip(device)
        if blank(target_ip):
            raise RuntimeError("Das Geraet hat keine IPv4-Adresse.")
        self._soap(
            self.host_filter_service,
            ACTION_DISALLOW_WAN_BY_IP,
            {"NewIPv4Address": target_ip, "NewDisallow": "1" if blocked else "0"},
        )

        deadline = time.time() + 20
        last_state = None
        while time.time() < deadline:
            time.sleep(0.75)
            last_state = self._get_access_state(target_ip)
            if last_state["disallowed"] == blocked and (last_state["blocked"] if blocked else not last_state["blocked"]):
                return
        if last_state and not blocked and not last_state["disallowed"] and last_state["blocked"]:
            raise RuntimeError(
                "Die manuelle Sperre wurde aufgehoben, aber das Zugangsprofil der FRITZ!Box blockiert das Geraet weiterhin."
            )
        raise RuntimeError(
            "Die FRITZ!Box hat die Internetsperre nicht bestaetigt."
            if blocked
            else "Die FRITZ!Box hat die Freigabe nicht bestaetigt."
        )

    def _resolve_current_ip(self, device):
        mac = device.get("macAddress", "")
        if blank(mac):
            return device.get("ipAddress", "")
        try:
            response = self._soap(self.hosts_service, "GetSpecificHostEntry", {"NewMACAddress": mac})
            current_ip = xml_text(response, "NewIPAddress")
            return current_ip or device.get("ipAddress", "")
        except Exception:
            return device.get("ipAddress", "")

    def _get_access_state(self, ip_address):
        self._require_action(self.host_filter_service, ACTION_GET_WAN_BY_IP)
        response = self._soap(
            self.host_filter_service,
            ACTION_GET_WAN_BY_IP,
            {"NewIPv4Address": ip_address},
        )
        wan_access = xml_text(response, "NewWANAccess")
        if wan_access.lower() == "error":
            raise RuntimeError("Die FRITZ!Box konnte den WAN-Status noch nicht ermitteln.")
        disallow = xml_text(response, "NewDisallow")
        return {
            "disallowed": disallow == "1" or disallow.lower() == "true",
            "blocked": wan_access.lower() == "denied",
        }

    def _discover_services(self):
        if self.hosts_service and self.host_filter_service:
            return
        description = parse_xml(self._request("GET", self.base_url + "/tr64desc.xml", None, None))
        for service in description.iter():
            if service.tag.split("}")[-1] != "service":
                continue
            service_type = child_text(service, "serviceType")
            control_url = child_text(service, "controlURL")
            scpd_url = child_text(service, "SCPDURL")
            item = {
                "type": service_type,
                "controlUrl": urllib.parse.urljoin(self.base_url + "/", control_url.lstrip("/")),
                "scpdUrl": ""
                if blank(scpd_url)
                else urllib.parse.urljoin(self.base_url + "/", scpd_url.lstrip("/")),
                "actions": None,
            }
            if ":Hosts:" in service_type:
                self.hosts_service = item
            elif "X_AVM-DE_HostFilter" in service_type:
                self.host_filter_service = item
        if not self.hosts_service or not self.host_filter_service:
            raise RuntimeError("Die benoetigten TR-064-Dienste wurden nicht gefunden.")

    def _soap(self, service, action, arguments):
        argument_xml = "".join(
            f"<{key}>{escape_xml(value)}</{key}>" for key, value in arguments.items()
        )
        body = (
            '<?xml version="1.0" encoding="utf-8"?>'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
            f'<s:Body><u:{action} xmlns:u="{service["type"]}">'
            f"{argument_xml}</u:{action}></s:Body></s:Envelope>"
        )
        response = self._request(
            "POST",
            service["controlUrl"],
            body.encode("utf-8"),
            {
                "Content-Type": 'text/xml; charset="utf-8"',
                "SOAPAction": f'"{service["type"]}#{action}"',
            },
            action,
        )
        root = parse_xml(response)
        error_code = xml_text(root, "errorCode")
        if not blank(error_code):
            raise RuntimeError(action_failure(error_code, xml_text(root, "errorDescription"), action))
        return root

    def _require_action(self, service, action):
        if service.get("actions") is None:
            service["actions"] = self._load_action_names(service)
        if service["actions"] and action not in service["actions"]:
            raise RuntimeError(unsupported_action_message(action))

    def _load_action_names(self, service):
        scpd_url = service.get("scpdUrl", "")
        if blank(scpd_url):
            return []
        try:
            root = parse_xml(self._request("GET", scpd_url, None, None))
            actions = []
            for element in root.iter():
                if element.tag.split("}")[-1].split(":")[-1] == "action":
                    name = child_text(element, "name")
                    if not blank(name):
                        actions.append(name)
            return actions
        except Exception:
            return []

    def _request(self, method, url, body, headers, fault_action=None):
        request = urllib.request.Request(url, data=body, method=method)
        request.add_header("Accept", "text/xml, application/xml, */*")
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        try:
            with self.opener.open(request, timeout=20) as response:
                return response.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as error:
            body_text = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(soap_fault(error.code, body_text, fault_action))


def escape_xml(value):
    return (
        str(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )


def soap_fault(http_code, body, action=None):
    try:
        root = parse_xml(body)
        code = xml_text(root, "errorCode")
        description = xml_text(root, "errorDescription")
        if not blank(code):
            return action_failure(code, description, action)
    except Exception:
        pass
    return f"HTTP {http_code}: {compact(body)}"


def action_failure(code, description, action=None):
    if code == "401":
        return unsupported_action_message(action)
    if code == "606":
        return (
            "FRITZ!Box-Fehler 606: Action Not Authorized. Der FRITZ!Box-Benutzer des Backends "
            "darf diese Sperraktion nicht ausfuehren."
        )
    if code == "501":
        return "FRITZ!Box-Fehler 501: Internetsperre im IP-Client-/Bridge-Modus nicht verfuegbar."
    if code == "714":
        return "FRITZ!Box-Fehler 714: Geraet unter dieser IPv4-Adresse nicht gefunden."
    if code == "880":
        return "FRITZ!Box-Fehler 880: Dieses FRITZ!OS-/Powerline-Geraet darf nicht gesperrt werden."
    if code == "402":
        return "FRITZ!Box-Fehler 402: Ungueltige Sperranfrage."
    if code == "820":
        return "FRITZ!Box-Fehler 820: Interner Fehler der FRITZ!Box."
    return "FRITZ!Box-Fehler " + code + ("" if blank(description) else ": " + description)


def unsupported_action_message(action=None):
    action_text = (
        "die benoetigte TR-064-HostFilter-Aktion"
        if blank(action)
        else f"die TR-064-Aktion {action}"
    )
    return (
        "FRITZ!Box-Fehler 401: Invalid Action. Diese FRITZ!Box/Firmware stellt "
        + action_text
        + " nicht bereit. Aktualisiere FRITZ!OS oder nutze eine FRITZ!Box, deren "
        + "X_AVM-DE_HostFilter-Dienst DisallowWANAccessByIP und GetWANAccessByIP anbietet."
    )


def device_key(device):
    mac = device.get("macAddress", "")
    return mac if not blank(mac) else device.get("ipAddress", "")


class ScheduleStore:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        self._init()

    def _connect(self):
        return sqlite3.connect(self.path, timeout=30)

    def _init(self):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        with self._connect() as db:
            db.execute(
                """
                create table if not exists schedules (
                    device_key text primary key,
                    name text not null,
                    ip_address text not null,
                    mac_address text not null,
                    interface_type text not null,
                    unlock_at integer not null
                )
                """
            )

    def schedule(self, device, unlock_at):
        with self.lock, self._connect() as db:
            db.execute(
                """
                insert into schedules(device_key, name, ip_address, mac_address, interface_type, unlock_at)
                values(?, ?, ?, ?, ?, ?)
                on conflict(device_key) do update set
                    name=excluded.name,
                    ip_address=excluded.ip_address,
                    mac_address=excluded.mac_address,
                    interface_type=excluded.interface_type,
                    unlock_at=excluded.unlock_at
                """,
                (
                    device_key(device),
                    device.get("name", ""),
                    device.get("ipAddress", ""),
                    device.get("macAddress", ""),
                    device.get("interfaceType", ""),
                    int(unlock_at),
                ),
            )

    def cancel(self, key):
        with self.lock, self._connect() as db:
            db.execute("delete from schedules where device_key = ?", (key,))

    def retry(self, key):
        with self.lock, self._connect() as db:
            db.execute(
                "update schedules set unlock_at = ? where device_key = ?",
                (now_ms() + RETRY_DELAY_SECONDS * 1000, key),
            )

    def due(self):
        with self.lock, self._connect() as db:
            rows = db.execute(
                """
                select device_key, name, ip_address, mac_address, interface_type, unlock_at
                from schedules
                where unlock_at <= ?
                order by unlock_at asc
                """,
                (now_ms(),),
            ).fetchall()
        return [self._row_to_device(row) for row in rows]

    def unlock_map(self):
        with self.lock, self._connect() as db:
            rows = db.execute("select device_key, unlock_at from schedules").fetchall()
        return {row[0]: row[1] for row in rows}

    @staticmethod
    def _row_to_device(row):
        return {
            "key": row[0],
            "name": row[1],
            "ipAddress": row[2],
            "macAddress": row[3],
            "interfaceType": row[4],
            "unlockAt": row[5],
        }


class AppState:
    def __init__(self):
        self.token = required_env("RKS_TOKEN")
        self.fritz_host = os.environ.get("RKS_FRITZ_HOST", "fritz.box")
        self.fritz_user = required_env("RKS_FRITZ_USER")
        self.fritz_password = required_env("RKS_FRITZ_PASSWORD")
        self.store = ScheduleStore(os.environ.get("RKS_DB", "/data/router-kill-switch.db"))

    def client(self):
        return FritzClient(self.fritz_host, self.fritz_user, self.fritz_password)

    def load_devices(self):
        devices = self.client().load_devices()
        unlocks = self.store.unlock_map()
        for device in devices:
            device["key"] = device_key(device)
            device["unlockAt"] = unlocks.get(device["key"], 0)
        return devices

    def set_devices(self, keys, blocked, unlock_at):
        devices = {device_key(device): device for device in self.client().load_devices()}
        succeeded = []
        failures = []
        client = self.client()
        for key in keys:
            device = devices.get(key)
            if not device:
                failures.append(f"{key}: Geraet nicht gefunden. Bitte neu scannen.")
                continue
            try:
                if device.get("blocked") != blocked:
                    client.set_blocked(device, blocked)
                if blocked and unlock_at > 0:
                    self.store.schedule(device, unlock_at)
                else:
                    self.store.cancel(key)
                succeeded.append(key)
            except Exception as error:
                failures.append(f"{device.get('name', key)}: {error}")
        return {"succeededKeys": succeeded, "failures": failures}


def required_env(name):
    value = os.environ.get(name, "")
    if blank(value):
        raise RuntimeError(f"Umgebungsvariable {name} fehlt.")
    return value


def unlock_worker(state):
    while True:
        for device in state.store.due():
            key = device["key"]
            try:
                state.client().set_blocked(device, False)
                state.store.cancel(key)
                print(f"scheduled unlock completed for {key}", flush=True)
            except Exception as error:
                print(f"scheduled unlock failed for {key}: {error}", file=sys.stderr, flush=True)
                state.store.retry(key)
        time.sleep(10)


class Handler(BaseHTTPRequestHandler):
    state = None

    def do_GET(self):
        if self.path == "/health":
            self.json_response({"ok": True, "version": VERSION})
            return
        if not self.authorized():
            self.error_response(401, "Nicht autorisiert.")
            return
        if self.path == "/devices":
            try:
                self.json_response({"devices": self.state.load_devices()})
            except Exception as error:
                self.error_response(502, str(error))
            return
        self.error_response(404, "Nicht gefunden.")

    def do_POST(self):
        if not self.authorized():
            self.error_response(401, "Nicht autorisiert.")
            return
        if self.path == "/devices/actions":
            try:
                body = self.read_json()
                keys = body.get("keys") or []
                blocked = bool(body.get("blocked"))
                unlock_at = int(body.get("unlockAt") or 0)
                if not isinstance(keys, list) or not keys:
                    self.error_response(400, "Keine Geraete ausgewaehlt.")
                    return
                self.json_response(self.state.set_devices(keys, blocked, unlock_at))
            except Exception as error:
                self.error_response(502, str(error))
            return
        self.error_response(404, "Nicht gefunden.")

    def authorized(self):
        expected = "Bearer " + self.state.token
        return self.headers.get("Authorization", "") == expected

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def json_response(self, body, status=200):
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def error_response(self, status, message):
        self.json_response({"error": message}, status=status)

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args), flush=True)


def main():
    state = AppState()
    Handler.state = state
    threading.Thread(target=unlock_worker, args=(state,), daemon=True).start()
    bind = os.environ.get("RKS_BIND", "0.0.0.0")
    port = int(os.environ.get("RKS_PORT", "8765"))
    server = ThreadingHTTPServer((bind, port), Handler)
    print(f"Router Kill Switch backend v{VERSION} listening on {bind}:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
