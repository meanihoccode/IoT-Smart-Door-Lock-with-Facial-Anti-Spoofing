"""ESP32 door-node simulator speaking docs/DEVICE_CONTRACT.md v1 (HTTPS + MQTT over TLS).

Evidence produced with it is "cloud staging with simulator", never "real hardware".

    pip install paho-mqtt==2.1.0
    python device_simulator.py --api https://smartlock.example.com --mqtt-host smartlock.example.com \
        --device-id door-01 --token-file token.txt --mqtt-password-file mqtt.txt pin 123456
    ... face path/to/photo.jpg | listen

Secrets are read from files so they never appear in shell history or process listings.
"""
import argparse
import json
import secrets
import ssl
import sys
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
        body = {"v": 1, "deviceId": self.device_id, "commandId": command_id, "seq": seq, "ack": ack,
                "eventId": secrets.token_hex(8), "ts": int(self.clock())}
        if reason:
            body["reason"] = reason
        # Only the accepted command's final ACK is kept for re-sending; replay/malformed rejections are not.
        if ack != "RECEIVED" and command_id == self.last_command_id:
            self.last_ack = body
            self._persist()
        return body

    def handle(self, payload):
        """Returns the list of ACK payloads to publish, in order."""
        try:
            msg = json.loads(payload)
        except ValueError:
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


def device_request(args, path, body, content_type):
    request = urllib.request.Request(args.api.rstrip("/") + path, data=body, method="POST", headers={
        "Authorization": "Bearer " + args.token, "X-Device-Id": args.device_id,
        "X-Request-Id": uuid.uuid4().hex, "X-Request-Time": str(int(time.time())), "Content-Type": content_type})
    context = ssl.create_default_context(cafile=args.ca) if args.ca else ssl.create_default_context()
    try:
        with urllib.request.urlopen(request, timeout=30, context=context) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def multipart(path):
    boundary = "sim" + secrets.token_hex(8)
    data = Path(path).read_bytes()
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"door.jpg\"\r\n"
            f"Content-Type: image/jpeg\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    return body, "multipart/form-data; boundary=" + boundary


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--api", required=True)
    parser.add_argument("--mqtt-host", required=True)
    parser.add_argument("--mqtt-port", type=int, default=8883)
    parser.add_argument("--ca", help="CA bundle; default system trust store")
    parser.add_argument("--device-id", default="door-01")
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--mqtt-password-file", required=True)
    parser.add_argument("--state-file", default=".simulator-state.json")
    parser.add_argument("--prefix", default="smartlock")
    parser.add_argument("action", choices=["pin", "face", "listen"])
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    args.token = Path(args.token_file).read_text().strip()
    mqtt_password = Path(args.mqtt_password_file).read_text().strip()

    import paho.mqtt.client as mqtt  # imported lazily so CommandProcessor tests need no broker library

    processor = CommandProcessor(args.device_id, args.state_file)
    base = f"{args.prefix}/{args.device_id}"
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=args.device_id, clean_session=True)
    client.username_pw_set(args.device_id, mqtt_password)
    client.tls_set(ca_certs=args.ca)  # verifies the certificate chain and hostname
    client.will_set(base + "/status", json.dumps({"v": 1, "deviceId": args.device_id, "online": False}), qos=1, retain=True)
    connected = threading.Event()

    def status():
        client.publish(base + "/status", json.dumps({"v": 1, "deviceId": args.device_id, "online": True,
                                                     "lockdown": processor.lockdown, "fw": "simulator"}), qos=0, retain=True)

    def on_connect(c, _userdata, _flags, reason, _props):
        print(f"[mqtt] connected: {reason}")
        c.subscribe(base + "/cmd", qos=1)
        status()
        connected.set()

    def on_message(c, _userdata, message):
        print(f"[cmd] {message.payload.decode()}")
        for ack in processor.handle(message.payload.decode()):
            print(f"[ack] {ack['ack']} {ack.get('reason', '')}")
            c.publish(base + "/ack", json.dumps(ack), qos=0)
        if processor.executions and processor.unlocked_until > time.time():
            command_id = processor.executions[-1]
            delay = processor.unlocked_until - time.time()
            threading.Timer(delay, lambda: c.publish(base + "/event", json.dumps({
                "v": 1, "deviceId": args.device_id, "eventId": secrets.token_hex(8), "type": "DOOR_LOCKED",
                "commandId": command_id, "ts": int(time.time())}))).start()

    client.on_connect, client.on_message = on_connect, on_message
    client.connect(args.mqtt_host, args.mqtt_port, keepalive=30)
    client.loop_start()
    if not connected.wait(15):
        sys.exit("MQTT connection failed")
    if args.action == "pin":
        body = json.dumps({"pinCode": args.value}).encode()
        print("[https]", *device_request(args, "/api/device/pin", body, "application/json"))
    elif args.action == "face":
        print("[https]", *device_request(args, "/api/device/face", *multipart(args.value)))
    try:
        time.sleep(20 if args.action != "listen" else 1e9)
    except KeyboardInterrupt:
        pass
    client.disconnect()
    client.loop_stop()


if __name__ == "__main__":
    main()
