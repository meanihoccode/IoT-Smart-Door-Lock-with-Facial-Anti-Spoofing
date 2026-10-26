"""Contract rules for command acceptance (mirrors the firmware). Run: python -m unittest -v (in this folder)."""
import json
import tempfile
import unittest
from pathlib import Path

from device_simulator import CommandProcessor

NOW = 1_800_000_000


def command(seq, command_id=None, kind="UNLOCK", issued=NOW, expires=NOW + 15, **extra):
    body = {"v": 1, "type": kind, "deviceId": "door-01", "commandId": command_id or f"cmd-{seq}", "seq": seq,
            "issuedAt": issued, "expiresAt": expires, "unlockMs": 7000}
    body.update(extra)
    return json.dumps(body)


class CommandProcessorTests(unittest.TestCase):
    def setUp(self):
        self.now = NOW
        self.dir = tempfile.TemporaryDirectory()
        self.state = Path(self.dir.name) / "state.json"
        self.p = CommandProcessor("door-01", self.state, clock=lambda: self.now)

    def tearDown(self):
        self.dir.cleanup()

    def acks(self, payload, processor=None):
        return [(a["ack"], a.get("reason")) for a in (processor or self.p).handle(payload)]

    def test_valid_unlock_is_received_then_executed_once(self):
        self.assertEqual(self.acks(command(10)), [("RECEIVED", None), ("EXECUTED", None)])
        self.assertEqual(self.p.executions, ["cmd-10"])

    def test_duplicate_is_reacked_without_actuating_or_extending(self):
        self.acks(command(10))
        until = self.p.unlocked_until
        self.now += 3
        self.assertEqual(self.acks(command(10)), [("EXECUTED", None)])
        self.assertEqual(self.p.executions, ["cmd-10"])
        self.assertEqual(self.p.unlocked_until, until)

    def test_new_command_while_open_does_not_extend(self):
        self.acks(command(10))
        self.assertEqual(self.acks(command(11))[-1], ("REJECTED", "ALREADY_UNLOCKED"))

    def test_older_seq_is_replay_even_after_reboot(self):
        self.acks(command(10))
        rebooted = CommandProcessor("door-01", self.state, clock=lambda: self.now + 60)
        self.assertEqual(self.acks(command(9, "other"), rebooted), [("REJECTED", "REPLAY")])
        self.assertEqual(self.acks(command(10), rebooted), [("EXECUTED", None)])
        self.assertEqual(rebooted.executions, [])

    def test_expired_and_future_commands_are_rejected(self):
        self.now = NOW + 30
        self.assertEqual(self.acks(command(10))[-1], ("REJECTED", "EXPIRED"))
        self.now = NOW
        self.assertEqual(self.acks(command(11, issued=NOW + 120, expires=NOW + 135))[-1], ("REJECTED", "CLOCK_SKEW"))
        self.assertEqual(self.p.executions, [])

    def test_wrong_device_or_malformed_never_actuates(self):
        self.assertEqual(self.acks(command(10, deviceId="door-02")), [("REJECTED", "MALFORMED")])
        self.assertEqual(self.acks("not json"), [])
        self.assertEqual(self.acks(json.dumps({"v": 1, "deviceId": "door-01"})), [])
        self.assertEqual(self.p.executions, [])

    def test_lockdown_blocks_unlock_and_survives_reboot(self):
        self.acks(command(10, kind="LOCKDOWN_ON", expires=NOW + 60))
        rebooted = CommandProcessor("door-01", self.state, clock=lambda: self.now)
        self.assertEqual(self.acks(command(11), rebooted)[-1], ("REJECTED", "LOCKDOWN"))
        self.acks(command(12, kind="LOCKDOWN_OFF", expires=NOW + 60), rebooted)
        self.assertEqual(self.acks(command(13), rebooted)[-1], ("EXECUTED", None))

    def test_unlock_duration_is_clamped(self):
        self.acks(command(10, unlockMs=600000))
        self.assertLessEqual(self.p.unlocked_until - NOW, 15)


if __name__ == "__main__":
    unittest.main()
