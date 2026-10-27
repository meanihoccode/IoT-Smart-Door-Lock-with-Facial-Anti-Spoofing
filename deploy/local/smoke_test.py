"""End-to-end smoke test for the LOCAL staging stack (deploy/local). Evidence level: staging with simulator.

Acts as the door node over REST + WebSocket (same acceptance rules as the firmware via the simulator) and as an
admin browser. Creates a throwaway PIN profile and restarts the local Core container. Never targets a real door.

    python deploy/local/smoke_test.py          (needs websocket-client==1.8.0)
"""
import http.cookiejar
import json
import secrets as rnd
import ssl
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent / "tools" / "device-simulator"))
from device_simulator import DoorNode, device_request, multipart  # noqa: E402
import websocket  # noqa: E402

CLOUD = "https://localhost:8443"
CA = str(HERE / "certs" / "ca.pem")
SECRET = {name: (HERE / "secrets" / f"{name}.txt").read_text().strip() for name in ("token", "admin")}
COMPOSE = ["docker", "compose", "-p", "smartlock-local", "-f", str(HERE.parent / "docker-compose.yml"),
           "-f", str(HERE / "docker-compose.local.yml"), "--env-file", str(HERE / ".env.local")]
TLS = ssl.create_default_context(cafile=CA)
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""), flush=True)


def wait_for(predicate, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        if predicate():
            return True
        time.sleep(0.3)
    return False


class Browser:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
                                                  urllib.request.HTTPSHandler(context=TLS))

    def send(self, method, path, body=None, content_type="application/json", csrf=True):
        headers = {"Content-Type": content_type}
        if csrf and method != "GET":
            token = json.loads(self.send("GET", "/api/auth/csrf")[1])
            headers[token["headerName"]] = token["token"]
        data = body if isinstance(body, (bytes, type(None))) else json.dumps(body).encode()
        request = urllib.request.Request(CLOUD + path, data=data, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=40) as response:
                return response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode()


def form(fields):
    boundary = "smoke" + rnd.token_hex(6)
    body = "".join(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n" for k, v in fields.items())
    return (body + f"--{boundary}--\r\n").encode(), "multipart/form-data; boundary=" + boundary


def gray_png(size=96):
    row = b"\x00" + b"\x80" * size
    chunk = lambda kind, data: struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 0, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * size)) + chunk(b"IEND", b""))


def device(path, body, content_type, request_id=None, token=None):
    status, text = device_request(CLOUD, "door-01", token or SECRET["token"], "/api/device" + path, body, content_type,
                                  CA, request_id)
    return status, json.loads(text) if text.startswith("{") else {}


def pin(value, **kw):
    return device("/pin", json.dumps({"pinCode": value}).encode(), "application/json", **kw)


def handshake(headers, ca=CA, origin=None):
    """Returns the HTTP status (or TLS error) of a WebSocket handshake without keeping the connection.
    By default no Origin header is sent, like the firmware; pass origin to act like a browser page."""
    try:
        sslopt = {"cert_reqs": ssl.CERT_REQUIRED, "ca_certs": ca} if ca else {"cert_reqs": ssl.CERT_REQUIRED}
        extra = {"origin": origin} if origin else {"suppress_origin": True}
        websocket.create_connection("wss://localhost:8443/ws/device", header=headers, sslopt=sslopt, timeout=10, **extra).close()
        return "101"
    except websocket.WebSocketBadStatusException as error:
        return str(error.status_code)
    except ssl.SSLError as error:
        return "tls-error:" + (error.reason or "")


def command_status(admin, command_id):
    door = json.loads(admin.send("GET", "/api/admin/door")[1])
    return next((c["status"] for c in door["commands"] if c["commandId"] == command_id), None)


def connection(admin):
    return json.loads(admin.send("GET", "/api/admin/door")[1]).get("connection")


def main():
    admin = Browser()
    status, _ = admin.send("POST", "/api/auth/login", {"username": "localadmin", "password": SECRET["admin"]})
    check("admin login over HTTPS (same origin, CSRF)", status == 200, f"HTTP {status}")
    test_pin = f"{rnd.randbelow(10**6):06d}"
    body, ctype = form({"username": "smoke-" + rnd.token_hex(3), "fullName": "Smoke Test", "pinCode": test_pin})
    status, _ = admin.send("POST", "/api/register", body, ctype)
    check("admin creates throwaway PIN profile", status == 201, f"HTTP {status}")

    check("WebSocket handshake without device token is refused", handshake([]) == "401", handshake([]))
    wrong = handshake(["Authorization: Bearer " + "0" * 64, "X-Device-Id: door-01"])
    check("WebSocket handshake with wrong token is refused", wrong == "401", wrong)
    good = ["Authorization: Bearer " + SECRET["token"], "X-Device-Id: door-01"]
    browser_origin = handshake(good, origin="https://evil.example")
    check("a browser page cannot open the device socket even with the token (Origin refused)",
          browser_origin == "403", browser_origin)
    untrusted = handshake(good, ca=None)
    check("wss is verified (system trust store rejects the test CA)", untrusted.startswith("tls-error"), untrusted)

    state = HERE / "secrets" / "smoke-device-state.json"
    state.unlink(missing_ok=True)
    door = DoorNode(CLOUD, "door-01", SECRET["token"], CA, state, log=lambda *_: None)
    check("door connects over wss with its token", door.start())
    check("Core shows the door ONLINE while its socket is open", wait_for(lambda: connection(admin) == "ONLINE"))

    before = len(json.loads(admin.send("GET", "/api/admin/door")[1])["commands"])
    door.send({"v": 1, "kind": "cmd", "type": "UNLOCK", "deviceId": "door-01", "commandId": "forged",
               "seq": 2**60, "issuedAt": int(time.time()), "expiresAt": int(time.time()) + 15})
    time.sleep(2)
    after = len(json.loads(admin.send("GET", "/api/admin/door")[1])["commands"])
    check("a device cannot issue commands over its socket", not door.processor.executions and after == before)

    browser = Browser()
    status, _ = browser.send("POST", "/api/verify-pin", {"pinCode": test_pin})
    check("browser kiosk cannot unlock by PIN even with CSRF", status >= 400, f"HTTP {status}")
    status, _ = pin(test_pin, token="0" * 64)
    check("wrong device token is rejected on REST", status == 401, f"HTTP {status}")

    request_id = rnd.token_hex(16)
    status, granted = pin(test_pin, request_id=request_id)
    command_id = granted.get("commandId")
    check("REST: device PIN -> 202 GRANTED with commandId", status == 202 and granted.get("decision") == "GRANTED" and command_id,
          f"HTTP {status} {granted.get('commandStatus')}")
    check("WebSocket: door receives command and ACKs RECEIVED then EXECUTED",
          wait_for(lambda: door.acked(command_id, "EXECUTED")) and door.acked(command_id, "RECEIVED"))
    check("Core records EXECUTED from the device ACK", wait_for(lambda: command_status(admin, command_id) == "EXECUTED"))
    check("exactly one actuation for one request", door.processor.executions == [command_id])
    status, _ = pin(test_pin, request_id=request_id)
    check("replayed request ID is rejected (409)", status == 409, f"HTTP {status}")
    door.processor.unlocked_until = 0  # simulated relock

    status, denied = pin("000000" if test_pin != "000000" else "111111")
    check("wrong PIN is denied without a command", status == 401 and "commandId" not in denied, f"HTTP {status}")

    status, face = device("/face", *multipart(gray_png(), "door.png", "image/png"))
    check("face upload is processed by the AI container (not MODEL_UNAVAILABLE)",
          face.get("aiRequestId") and face.get("reasonCode") not in (None, "MODEL_UNAVAILABLE", "INVALID_AI_RESPONSE"),
          f"HTTP {status} {face.get('reasonCode')}")

    status, toggled = admin.send("POST", "/api/lockdown/toggle", {"enabled": True})
    lock_id = json.loads(toggled).get("commandId")
    check("lockdown command reaches device and is ACKed", wait_for(lambda: door.acked(lock_id, "EXECUTED")) and door.processor.lockdown)
    check("lockdown shown as applied only after ACK",
          wait_for(lambda: json.loads(admin.send("GET", "/api/lockdown/status")[1]).get("lockdownApplied") is True))
    status, _ = pin(test_pin)
    check("unlock refused during lockdown", status == 403, f"HTTP {status}")
    _, toggled = admin.send("POST", "/api/lockdown/toggle", {"enabled": False})
    off_id = json.loads(toggled).get("commandId")
    check("lockdown off is ACKed", wait_for(lambda: door.acked(off_id, "EXECUTED")) and not door.processor.lockdown)

    connects = door.connects
    subprocess.run(COMPOSE + ["restart", "core"], check=True, capture_output=True)
    wait_for(lambda: Browser().send("GET", "/api/door-status", csrf=False)[0] == 200, 120)
    check("door reconnects its WebSocket by itself after Core restarts", wait_for(lambda: door.connects > connects, 60))
    admin = Browser()
    admin.send("POST", "/api/auth/login", {"username": "localadmin", "password": SECRET["admin"]})
    check("history persists across Core restart", command_status(admin, command_id) == "EXECUTED")
    status, granted = pin(test_pin)
    command_id = granted.get("commandId")
    check("after restart: command + EXECUTED ACK flow again",
          status == 202 and wait_for(lambda: command_status(admin, command_id) == "EXECUTED", 30), f"HTTP {status}")

    door.stop()
    check("closing the socket marks the door OFFLINE", wait_for(lambda: connection(admin) == "OFFLINE", 30))

    public = json.loads(Browser().send("GET", "/api/door-status", csrf=False)[1])
    check("anonymous kiosk status has no personal data", "Smoke Test" not in json.dumps(public) and "connection" in public)

    output = subprocess.run(COMPOSE + ["ps", "--format", "json"], capture_output=True, text=True).stdout.strip()
    services = json.loads(output) if output.startswith("[") else [json.loads(line) for line in output.splitlines()]
    exposed = sorted({f"{s['Service']}:{p['PublishedPort']}" for s in services
                      for p in s.get("Publishers") or [] if p.get("PublishedPort")})
    check("only the HTTPS proxy publishes a host port (DB/AI/Core private, no broker)",
          exposed and all(e.split(":")[0] == "web" for e in exposed), ", ".join(exposed))

    failed = [name for name, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
