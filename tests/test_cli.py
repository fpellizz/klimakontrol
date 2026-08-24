import getpass as getpass_module
import json
import types
import unittest

from klimakontrol import cli
from klimakontrol.cloud import CloudDevice


class TestBindCommand(unittest.TestCase):
    def test_cmd_bind_calls_bind_device_with_args(self):
        """Test: cmd_bind passa i parametri a bind_device e rilegge i dispositivi (I1)."""
        calls = {}
        saved_devices = {}

        class FakeClient:
            userid = "u"
            def bind_device(self, dev, name="", family_id=None, room_id=""):
                calls["did"] = dev.did
                calls["pid"] = dev.pid
                calls["mac"] = dev.mac
                calls["key"] = dev.aeskey
                calls["name"] = name
                calls["family"] = family_id
                return {"status": 0, "msg": "ok"}

            def devices(self):
                # Simulazione: ritorna una lista sentinella per verificare che sia stata riletta
                return [CloudDevice(did="D1", pid="P1", mac="M1", aeskey="KEYHEX", name="Camera")]

        # niente rete: sostituisci session.load e session.mask e session.save
        orig_load = cli.session.load
        orig_mask = cli.session.mask
        orig_save = cli.session.save

        def fake_save(client, devices):
            saved_devices["devices"] = devices

        cli.session.load = lambda: (FakeClient(), [])
        cli.session.mask = lambda o: o
        cli.session.save = fake_save
        try:
            args = types.SimpleNamespace(
                did="D1", pid="P1", mac="M1", key="KEYHEX", name="Camera", family=None)
            cli.cmd_bind(args)
        finally:
            cli.session.load = orig_load
            cli.session.mask = orig_mask
            cli.session.save = orig_save

        # Verifica passaggio parametri
        self.assertEqual(calls["did"], "D1")
        self.assertEqual(calls["pid"], "P1")
        self.assertEqual(calls["mac"], "M1")
        self.assertEqual(calls["key"], "KEYHEX")
        self.assertEqual(calls["name"], "Camera")
        # Verifica mapping --family → family_id (fix minore)
        self.assertIsNone(calls["family"])
        # Verifica che dispositivi siano stati riletti (I1)
        self.assertIsNotNone(saved_devices.get("devices"))

    def test_cmd_bind_no_session(self):
        """Test: cmd_bind con nessuna sessione fa sys.exit (I2)."""
        orig_load = cli.session.load
        cli.session.load = lambda: (None, [])
        try:
            args = types.SimpleNamespace(
                did="D1", pid="P1", mac="M1", key="KEYHEX", name="Camera", family=None)
            with self.assertRaises(SystemExit):
                cli.cmd_bind(args)
        finally:
            cli.session.load = orig_load

    def test_cmd_bind_getpass_key(self):
        """Test: cmd_bind con key=None chiede il prompt (I4)."""
        calls = {}

        class FakeClient:
            userid = "u"
            def bind_device(self, dev, name="", family_id=None, room_id=""):
                calls["key"] = dev.aeskey
                return {"status": 0, "msg": "ok"}

            def devices(self):
                return []

        orig_load = cli.session.load
        orig_mask = cli.session.mask
        orig_save = cli.session.save
        orig_getpass = getpass_module.getpass

        cli.session.load = lambda: (FakeClient(), [])
        cli.session.mask = lambda o: o
        cli.session.save = lambda *a, **k: None
        getpass_module.getpass = lambda prompt: "PROMPTED"
        try:
            args = types.SimpleNamespace(
                did="D1", pid="P1", mac="M1", key=None, name="Camera", family=None)
            cli.cmd_bind(args)
        finally:
            cli.session.load = orig_load
            cli.session.mask = orig_mask
            cli.session.save = orig_save
            getpass_module.getpass = orig_getpass

        # Verifica che chiave venga dal prompt
        self.assertEqual(calls["key"], "PROMPTED")
