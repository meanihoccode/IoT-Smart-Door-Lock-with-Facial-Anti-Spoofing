"""ESP32 door-node simulator speaking docs/DEVICE_CONTRACT.md v2 (REST over HTTPS + WebSocket over TLS).

Evidence produced with it is "cloud staging with simulator", never "real hardware".

    pip install websocket-client==1.8.0
    python device_simulator.py --cloud https://smartlock.example.com --device-id door-01 \
        --token-file token.txt pin 123456
    ... face path/to/photo.jpg | listen

The token is read from a file so it never appears in shell history or process listings.
"""
import argparse
import json
import secrets
import ssl
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

MAX_UNLOCK_MS = 15000
CLOCK_SKEW_S = 5


class CommandProcessor:
    """Same acceptance rules as the firmware: identity, seq replay guard, TTL, lockdown, no extension."""

    def __init__(self, device_id, state_file=None, clock=time.time):
        self.device_id = device_id
        self.state_file = Path(state_file) if state_file else None
        self.clock = clock
        self.last_seq, self.last_command_id, self.last_ack = 0, "", None
        self.lockdown = False
        self.unlocked_until = 0.0
        self.executions = []
        if self.state_file and self.state_file.exists():
            state = json.loads(self.state_file.read_text())
            self.last_seq, self.last_command_id = state["lastSeq"], state["lastCommandId"]
            self.lockdown, self.last_ack = state["lockdown"], state.get("lastAck")

    def _persist(self):
        if self.state_file:
            self.state_file.write_text(json.dumps({"lastSeq": self.last_seq, "lastCommandId": self.last_command_id,
                                                   "lockdown": self.lockdown, "lastAck": self.last_ack}))

    def _ack(self, command_id, seq, ack, reason=None):
        body = {"v": 1, "kind": "ack", "deviceId": self.device_id, "commandId": command_id, "seq": seq, "ack": ack,
                "eventId": secrets.token_hex(8), "ts": int(self.clock())}
        if reason:
            body["reason"] = reason
        # Only the accepted command's final ACK is kept for re-sending; replay/malformed rejections are not.
        if ack != "RECEIVED" and command_id == self.last_command_id:
            self.last_ack = body
            self._persist()
        return body

    def handle(self, payload):
        """Returns the list of ACK payloads to send back, in order. Only "cmd" frames are commands."""
        try:
            msg = json.loads(payload)
        except ValueError:
            return []
        if not isinstance(msg, dict) or msg.get("kind") != "cmd":
            return []
        command_id = msg.get("commandId") if isinstance(msg.get("commandId"), str) else ""
        seq = msg.get("seq")
        required = ("type", "issuedAt", "expiresAt")
        if (msg.get("v") != 1 or msg.get("deviceId") != self.device_id or not command_id
                or not isinstance(seq, int) or seq <= 0 or any(k not in msg for k in required)):
            return [self._ack(command_id, seq or 0, "REJECTED", "MALFORMED")] if command_id else []
        if command_id == self.last_command_id:
            return [self.last_ack] if self.last_ack else []
        if seq <= self.last_seq:
            return [self._ack(command_id, seq, "REJECTED", "REPLAY")]
        self.last_seq, self.last_command_id, self.last_ack = seq, command_id, None
        self._persist()
        acks = [self._ack(command_id, seq, "RECEIVED")]
        now = self.clock()
        if now > msg["expiresAt"] + CLOCK_SKEW_S:
            return acks + [self._ack(command_id, seq, "REJECTED", "EXPIRED")]
        if msg["issuedAt"] > now + 30:
            return acks + [self._ack(command_id, seq, "REJECTED", "CLOCK_SKEW")]
        kind = msg["type"]
        if kind == "UNLOCK":
            if self.lockdown:
                return acks + [self._ack(command_id, seq, "REJECTED", "LOCKDOWN")]
            if now < self.unlocked_until:
                return acks + [self._ack(command_id, seq, "REJECTED", "ALREADY_UNLOCKED")]
            unlock_ms = min(max(int(msg.get("unlockMs", 7000)), 1000), MAX_UNLOCK_MS)
            self.unlocked_until = now + unlock_ms / 1000
            self.executions.append(command_id)
            return acks + [self._ack(command_id, seq, "EXECUTED")]
        if kind in ("LOCKDOWN_ON", "LOCKDOWN_OFF"):
            self.lockdown = kind == "LOCKDOWN_ON"
            if self.lockdown:
                self.unlocked_until = 0.0
            self._persist()
            return acks + [self._ack(command_id, seq, "EXECUTED")]
        return acks + [self._ack(command_id, seq, "REJECTED", "UNKNOWN_TYPE")]


class DoorNode:
    """The door's command channel: one wss connection that reconnects by itself, like the firmware."""

    def __init__(self, cloud, device_id, token, ca=None, state_file=None, log=print):
        import websocket  # imported lazily so CommandProcessor tests need no WebSocket library
        self.processor = CommandProcessor(device_id, state_file)
        self.device_id, self.log = device_id, log
        self.acks, self.connects, self.connected = [], 0, threading.Event()
        url = cloud.replace("https://", "wss://").rstrip("/") + "/ws/device"
        self.app = websocket.WebSocketApp(url, header=[f"Authorization: Bearer {token}", f"X-Device-Id: {device_id}"],
                                          on_open=self._open, on_message=self._message, on_close=self._close)
        # Verifies chain and hostname against the given CA (or the system store).
        self.sslopt = {"cert_reqs": ssl.CERT_REQUIRED, "ca_certs": ca} if ca else {"cert_reqs": ssl.CERT_REQUIRED}

    def send(self, body):
        try:
            self.app.send(json.dumps(body))
            return True
        except Exception:  # noqa: BLE001 - closed socket; the ACK is simply lost, as on the device
            return False

    def status(self):
        self.send({"v": 1, "kind": "status", "deviceId": self.device_id, "lockdown": self.processor.lockdown, "fw": "simulator"})

    def _open(self, _ws):
        self.connects += 1
        self.connected.set()
        self.log("[ws] connected")
        self.status()

    def _close(self, _ws, code, _reason):
        self.connected.clear()
        self.log(f"[ws] closed {code}")

    def _message(self, _ws, text):
        self.log(f"[cmd] {text}")
        for ack in self.processor.handle(text):
            self.acks.append(ack)
            self.log(f"[ack] {ack['ack']} {ack.get('reason', '')}")
            self.send(ack)
        if self.processor.executions and self.processor.unlocked_until > time.time():
            command_id = self.processor.executions[-1]
            threading.Timer(self.processor.unlocked_until - time.time(), lambda: self.send({
                "v": 1, "kind": "event", "deviceId": self.device_id, "eventId": secrets.token_hex(8),
                "type": "DOOR_LOCKED", "commandId": command_id, "ts": int(time.time())})).start()

    def start(self, timeout=15):
        # suppress_origin: like the firmware (which replaces the library's default Origin), send no Origin header.
        # Core refuses browser origins on the device socket even with a valid token.
        threading.Thread(target=self.app.run_forever, kwargs={"sslopt": self.sslopt, "ping_interval": 15,
                                                              "ping_timeout": 5, "reconnect": 2,
                                                              "suppress_origin": True}, daemon=True).start()
        return self.connected.wait(timeout)

    def stop(self):
        self.app.keep_running = False
        self.app.close()

    def acked(self, command_id, kind):
        return any(a["commandId"] == command_id and a["ack"] == kind for a in self.acks)


def device_request(cloud, device_id, token, path, body, content_type, ca=None, request_id=None):
    request = urllib.request.Request(cloud.rstrip("/") + path, data=body, method="POST", headers={
        "Authorization": "Bearer " + token, "X-Device-Id": device_id,
        "X-Request-Id": request_id or uuid.uuid4().hex, "X-Request-Time": str(int(time.time())),
        "Content-Type": content_type})
    context = ssl.create_default_context(cafile=ca) if ca else ssl.create_default_context()
    try:
        with urllib.request.urlopen(request, timeout=40, context=context) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def multipart(data, filename="door.jpg", content_type="image/jpeg"):
    boundary = "sim" + secrets.token_hex(8)
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
            f"Content-Type: {content_type}\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    return body, "multipart/form-data; boundary=" + boundary


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cloud", required=True, help="https://<domain>[:port]")
    parser.add_argument("--ca", help="CA bundle; default system trust store")
    parser.add_argument("--device-id", default="door-01")
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--state-file", default=".simulator-state.json")
    parser.add_argument("action", choices=["pin", "face", "listen"])
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    token = Path(args.token_file).read_text().strip()

    door = DoorNode(args.cloud, args.device_id, token, args.ca, args.state_file)
    if not door.start():
        raise SystemExit("WebSocket connection failed")
    if args.action == "pin":
        body = json.dumps({"pinCode": args.value}).encode()
        print("[https]", *device_request(args.cloud, args.device_id, token, "/api/device/pin", body, "application/json", args.ca))
    elif args.action == "face":
        body, content_type = multipart(Path(args.value).read_bytes())
        print("[https]", *device_request(args.cloud, args.device_id, token, "/api/device/face", body, content_type, args.ca))
    try:
        time.sleep(20 if args.action != "listen" else 1e9)
    except KeyboardInterrupt:
        pass
    door.stop()


if __name__ == "__main__":
    main()
