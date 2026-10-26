"""End-to-end smoke test for the LOCAL staging stack (deploy/local). Evidence level: staging with simulator.

Acts as the door node (same acceptance rules as the firmware via CommandProcessor) and as an admin browser.
Creates a throwaway PIN profile. Restarts the local broker and Core containers. Never targets a real door.

    python deploy/local/smoke_test.py          (needs paho-mqtt==2.1.0)
"""
import http.cookiejar
import json
import ssl
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zlib
import secrets as rnd
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent / "tools" / "device-simulator"))
from device_simulator import CommandProcessor  # noqa: E402
import paho.mqtt.client as mqtt  # noqa: E402

API = "https://localhost:8443"
CA = str(HERE / "certs" / "ca.pem")
SECRET = {name: (HERE / "secrets" / f"{name}.txt").read_text().strip() for name in ("token", "mqtt", "admin")}
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
        request = urllib.request.Request(API + path, data=data, method=method, headers=headers)
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
    request = urllib.request.Request(API + "/api/device" + path, data=body, method="POST", headers={
        "Authorization": "Bearer " + (token or SECRET["token"]), "X-Device-Id": "door-01",
        "X-Request-Id": request_id or uuid.uuid4().hex, "X-Request-Time": str(int(time.time())),
        "Content-Type": content_type})
    try:
        with urllib.request.urlopen(request, timeout=40, context=TLS) as response:
            return response.status, json.loads(response.read().decode() or "{}")
    except urllib.error.HTTPError as error:
        text = error.read().decode()
        return error.code, json.loads(text) if text.startswith("{") else {}


def pin(value, **kw):
    return device("/pin", json.dumps({"pinCode": value}).encode(), "application/json", **kw)


class Door:
    """In-process door node over MQTT TLS with the device's own credentials."""

    def __init__(self, state_file):
        self.processor = CommandProcessor("door-01", state_file)
        self.acks, self.connects = [], 0
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="door-01", clean_session=True)
        self.client.username_pw_set("door-01", SECRET["mqtt"])
        self.client.tls_set(ca_certs=CA)
        self.client.will_set("smartlock/door-01/status", json.dumps({"v": 1, "deviceId": "door-01", "online": False}), 1, True)
        self.client.on_connect = self.on_connect
        self.client.on_message = self.on_message
        self.client.reconnect_delay_set(1, 5)

    def on_connect(self, client, _u, _f, reason, _p):
        if reason.is_failure:
            return
        self.connects += 1
        client.subscribe("smartlock/door-01/cmd", qos=1)
        client.publish("smartlock/door-01/status", json.dumps({"v": 1, "deviceId": "door-01", "online": True,
                       "lockdown": self.processor.lockdown, "fw": "smoke"}), retain=True)

    def on_message(self, client, _u, message):
        for ack in self.processor.handle(message.payload.decode()):
            self.acks.append(ack)
            client.publish("smartlock/door-01/ack", json.dumps(ack))

    def start(self):
        self.client.connect("localhost", 8883, keepalive=30)
        self.client.loop_start()
        return wait_for(lambda: self.connects > 0, 15)

    def acked(self, command_id, kind):
        return any(a["commandId"] == command_id and a["ack"] == kind for a in self.acks)


def mqtt_connect(user=None, password=None, cafile=CA):
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="probe-" + rnd.token_hex(3))
    outcome = {}
    client.on_connect = lambda c, u, f, reason, p: outcome.setdefault("reason", reason)
    if user:
        client.username_pw_set(user, password)
    client.tls_set(ca_certs=cafile)
    try:
        client.connect("localhost", 8883, keepalive=10)
    except ssl.SSLError as error:
        return "tls-error:" + error.reason if error.reason else "tls-error"
    client.loop_start()
    wait_for(lambda: "reason" in outcome, 8)
    client.loop_stop()
    client.disconnect()
    return str(outcome.get("reason"))


def command_status(admin, command_id):
    door = json.loads(admin.send("GET", "/api/admin/door")[1])
    return next((c["status"] for c in door["commands"] if c["commandId"] == command_id), None)


def main():
    admin = Browser()
    status, _ = admin.send("POST", "/api/auth/login", {"username": "localadmin", "password": SECRET["admin"]})
    check("admin login over HTTPS (same origin, CSRF)", status == 200, f"HTTP {status}")
    test_pin = f"{rnd.randbelow(10**6):06d}"
    body, ctype = form({"username": "smoke-" + rnd.token_hex(3), "fullName": "Smoke Test", "pinCode": test_pin})
    status, _ = admin.send("POST", "/api/register", body, ctype)
    check("admin creates throwaway PIN profile", status == 201, f"HTTP {status}")

    check("broker refuses anonymous clients", "Not authorized" in mqtt_connect() or "Bad user" in mqtt_connect())
    check("broker refuses wrong device password", mqtt_connect("door-01", "wrong") != "Success")
    untrusted = mqtt_connect("door-01", SECRET["mqtt"], cafile=None)
    check("TLS is verified (system trust store rejects the test CA)", untrusted.startswith("tls-error"), untrusted)

    state = HERE / "secrets" / "smoke-device-state.json"
    state.unlink(missing_ok=True)
    door = Door(state)
    check("device connects with TLS + own credentials", door.start())
    time.sleep(1)

    door.client.publish("smartlock/door-01/cmd", json.dumps({"v": 1, "type": "UNLOCK", "deviceId": "door-01",
                        "commandId": "forged", "seq": 2**60, "issuedAt": int(time.time()), "expiresAt": int(time.time()) + 15}), qos=1)
    time.sleep(2)
    check("ACL: a device cannot publish commands (forged UNLOCK never delivered)", not door.processor.executions)

    browser = Browser()
    status, _ = browser.send("POST", "/api/verify-pin", {"pinCode": test_pin})
    check("browser kiosk cannot unlock by PIN even with CSRF", status >= 400, f"HTTP {status}")
    status, _ = device("/pin", json.dumps({"pinCode": test_pin}).encode(), "application/json", token="0" * 64)
    check("wrong device token is rejected", status == 401, f"HTTP {status}")

    request_id = uuid.uuid4().hex
    status, granted = pin(test_pin, request_id=request_id)
    command_id = granted.get("commandId")
    check("device PIN -> 202 GRANTED with commandId", status == 202 and granted.get("decision") == "GRANTED" and command_id,
          f"HTTP {status} {granted.get('commandStatus')}")
    check("door receives command and ACKs RECEIVED then EXECUTED",
          wait_for(lambda: door.acked(command_id, "EXECUTED")) and door.acked(command_id, "RECEIVED"))
    check("Core records EXECUTED from the device ACK", wait_for(lambda: command_status(admin, command_id) == "EXECUTED"))
    check("exactly one actuation for one request", door.processor.executions == [command_id])
    status, _ = pin(test_pin, request_id=request_id)
    check("replayed request ID is rejected (409)", status == 409, f"HTTP {status}")
    door.processor.unlocked_until = 0  # simulated relock

    status, denied = pin("000000" if test_pin != "000000" else "111111")
    check("wrong PIN is denied without a command", status == 401 and "commandId" not in denied, f"HTTP {status}")

    status, face = device("/face", *form_file(gray_png()))
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

    before = door.connects
    subprocess.run(COMPOSE + ["restart", "mosquitto"], check=True, capture_output=True)
    check("device reconnects after broker restart", wait_for(lambda: door.connects > before, 40))
    time.sleep(8)  # Core reconnect backoff
    status, granted = pin(test_pin)
    command_id = granted.get("commandId")
    check("after broker restart Core resubscribed: command + EXECUTED ACK flow again",
          status == 202 and wait_for(lambda: command_status(admin, command_id) == "EXECUTED", 30), f"HTTP {status}")
    door.processor.unlocked_until = 0

    subprocess.run(COMPOSE + ["restart", "core"], check=True, capture_output=True)
    wait_for(lambda: admin.send("GET", "/api/door-status", csrf=False)[0] == 200, 120)
    admin = Browser()
    admin.send("POST", "/api/auth/login", {"username": "localadmin", "password": SECRET["admin"]})
    check("data persists across Core restart (profile still opens, history kept)",
          command_status(admin, command_id) == "EXECUTED" and pin(test_pin)[0] == 202)

    public = json.loads(Browser().send("GET", "/api/door-status", csrf=False)[1])
    check("anonymous kiosk status has no personal data", "Smoke Test" not in json.dumps(public) and "connection" in public)

    output = subprocess.run(COMPOSE + ["ps", "--format", "json"], capture_output=True, text=True).stdout.strip()
    services = json.loads(output) if output.startswith("[") else [json.loads(line) for line in output.splitlines()]
    exposed = sorted({f"{s['Service']}:{p['PublishedPort']}" for s in services
                      for p in s.get("Publishers") or [] if p.get("PublishedPort")})
    check("only web and mosquitto publish host ports (DB/AI/Core private)",
          exposed and all(e.split(":")[0] in ("web", "mosquitto") for e in exposed), ", ".join(exposed))

    door.client.loop_stop()
    door.client.disconnect()
    failed = [name for name, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    sys.exit(1 if failed else 0)


def form_file(data):
    boundary = "smoke" + rnd.token_hex(6)
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"door.png\"\r\n"
            f"Content-Type: image/png\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    return body, "multipart/form-data; boundary=" + boundary


if __name__ == "__main__":
    main()
