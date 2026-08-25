import contextlib
import getpass as getpass_module
import io
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
            with contextlib.redirect_stdout(io.StringIO()) as output:
                cli.cmd_bind(args)
            printed = output.getvalue()
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
        self.assertEqual(len(saved_devices["devices"]), 1)
        self.assertEqual(saved_devices["devices"][0].did, "D1")
        # Verifica output: AES key non deve apparire, did deve essere troncato
        # (Fix C - asserzioni stdout)
        self.assertNotIn("KEYHEX", printed)
        self.assertIn("Camera", printed)  # il nome completo appare se fornito

    def test_cmd_bind_no_name_truncates_did(self):
        """Test: cmd_bind senza --name tronca il did a 8 caratteri in output (I3)."""
        class FakeClient:
            userid = "u"
            def bind_device(self, dev, name="", family_id=None, room_id=""):
                return {"status": 0, "msg": "ok"}

            def devices(self):
                return []

        orig_load = cli.session.load
        orig_mask = cli.session.mask
        orig_save = cli.session.save

        cli.session.load = lambda: (FakeClient(), [])
        cli.session.mask = lambda o: o
        cli.session.save = lambda *a, **k: None
        try:
            long_did = "0123456789ABCDEF0123456789ABCDEF"  # 32 char did
            args = types.SimpleNamespace(
                did=long_did, pid="P1", mac="M1", key="KEYHEX", name=None, family=None)
            with contextlib.redirect_stdout(io.StringIO()) as output:
                cli.cmd_bind(args)
            printed = output.getvalue()
        finally:
            cli.session.load = orig_load
            cli.session.mask = orig_mask
            cli.session.save = orig_save

        # Verifica che il did sia troncato a 8 caratteri
        self.assertIn("01234567", printed)
        # Verifica che il did completo NON appaia
        self.assertNotIn(long_did, printed)
        # Verifica che la chiave AES non appaia
        self.assertNotIn("KEYHEX", printed)

    def test_cmd_bind_no_session(self):
        """Test: cmd_bind con nessuna sessione fa sys.exit (I2)."""
        orig_load = cli.session.load
        cli.session.load = lambda: (None, [])
        try:
            args = types.SimpleNamespace(
                did="D1", pid="P1", mac="M1", key="KEYHEX", name="Camera", family=None)
            with contextlib.redirect_stdout(io.StringIO()):
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
            with contextlib.redirect_stdout(io.StringIO()):
                cli.cmd_bind(args)
        finally:
            cli.session.load = orig_load
            cli.session.mask = orig_mask
            cli.session.save = orig_save
            getpass_module.getpass = orig_getpass

        # Verifica che chiave venga dal prompt
        self.assertEqual(calls["key"], "PROMPTED")


class ProvisionCommand(unittest.TestCase):
    def test_cmd_provision_calls_softap_config(self):
        """Test: cmd_provision chiama softap_config con i parametri corretti."""
        import klimakontrol.provision as prov

        calls = {}
        orig = prov.softap_config
        prov.softap_config = lambda ssid, password, security=0, **k: calls.update(
            ssid=ssid, password=password, security=security) or None
        try:
            args = types.SimpleNamespace(ssid="CasaWifi", password="segreta1", security="wpa2")
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                cli.cmd_provision(args)
        finally:
            prov.softap_config = orig

        self.assertEqual(calls["ssid"], "CasaWifi")
        self.assertEqual(calls["password"], "segreta1")
        self.assertEqual(calls["security"], 3)              # wpa2 -> 3
        self.assertNotIn("segreta1", buf.getvalue())        # password mai stampata

    def test_cmd_provision_prompts_password_when_omitted(self):
        """Test: cmd_provision con password=None chiede il prompt."""
        import klimakontrol.provision as prov

        seen = {}
        orig_cfg, orig_gp = prov.softap_config, cli.getpass.getpass
        prov.softap_config = lambda ssid, password, security=0, **k: seen.update(pw=password)
        cli.getpass.getpass = lambda *a, **k: "dal-prompt"
        try:
            args = types.SimpleNamespace(ssid="W", password=None, security="open")
            with contextlib.redirect_stdout(io.StringIO()):
                cli.cmd_provision(args)
        finally:
            prov.softap_config, cli.getpass.getpass = orig_cfg, orig_gp
        self.assertEqual(seen["pw"], "dal-prompt")

    def test_cmd_provision_oserror_exit_no_password_in_message(self):
        """Test: cmd_provision con OSError da softap_config fa SystemExit senza stampare la password."""
        import klimakontrol.provision as prov

        orig = prov.softap_config
        prov.softap_config = lambda ssid, password, security=0, **k: (_ for _ in ()).throw(
            OSError("Network unreachable"))
        try:
            args = types.SimpleNamespace(ssid="W", password="segretaPassword", security="open")
            with contextlib.redirect_stdout(io.StringIO()) as buf:
                with self.assertRaises(SystemExit):
                    cli.cmd_provision(args)
            # La password non deve apparire nemmeno nel messaggio d'errore catturato
            # (SystemExit stampa il messaggio su stderr, quindi non lo catturiamo qui,
            # ma verifichiamo che la funzione non abbia stampato nulla su stdout)
            self.assertNotIn("segretaPassword", buf.getvalue())
        finally:
            prov.softap_config = orig
