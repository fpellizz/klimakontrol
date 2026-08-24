import json
import types
import unittest

from klimakontrol import cli
from klimakontrol.cloud import CloudDevice


class BindCommand(unittest.TestCase):
    def test_cmd_bind_calls_bind_device_with_args(self):
        calls = {}

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

        # niente rete: sostituisci session.load e session.mask
        orig_load, orig_mask, orig_save = cli.session.load, cli.session.mask, cli.session.save
        cli.session.load = lambda: (FakeClient(), [])
        cli.session.mask = lambda o: o
        cli.session.save = lambda *a, **k: None
        try:
            args = types.SimpleNamespace(
                did="D1", pid="P1", mac="M1", key="KEYHEX", name="Camera", family=None)
            cli.cmd_bind(args)
        finally:
            cli.session.load, cli.session.mask, cli.session.save = orig_load, orig_mask, orig_save

        self.assertEqual(calls["did"], "D1")
        self.assertEqual(calls["pid"], "P1")
        self.assertEqual(calls["mac"], "M1")
        self.assertEqual(calls["key"], "KEYHEX")
        self.assertEqual(calls["name"], "Camera")
