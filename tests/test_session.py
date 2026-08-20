import json
import os
import tempfile
import unittest

from klimakontrol import session
from klimakontrol.cloud import CloudClient, CloudDevice


class TestPersistence(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "session.json")

    def test_save_and_load_round_trip(self):
        client = CloudClient("eu")
        client.restore_session("u1", "s1")
        dev = CloudDevice(did="D" * 32, pid="p", mac="aa:bb:cc:dd:ee:ff",
                          aeskey="ab" * 16, name="Salotto", lanaddr="192.168.1.9",
                          devtype=0x507C)
        session.save(client, [dev], self.path)
        loaded, devices = session.load(self.path)
        self.assertEqual(loaded.userid, "u1")
        self.assertEqual(loaded.loginsession, "s1")
        self.assertEqual(loaded.region.code, "eu")
        self.assertEqual(len(devices), 1)
        self.assertEqual(devices[0].name, "Salotto")
        self.assertEqual(devices[0].aeskey, "ab" * 16)

    def test_file_is_not_world_readable(self):
        client = CloudClient("eu")
        client.restore_session("u1", "s1")
        session.save(client, [], self.path)
        self.assertEqual(os.stat(self.path).st_mode & 0o077, 0)

    def test_missing_file_gives_empty_state(self):
        client, devices = session.load(os.path.join(self.dir, "assente.json"))
        self.assertIsNone(client)
        self.assertEqual(devices, [])

    def test_no_password_is_ever_written(self):
        client = CloudClient("eu")
        client.restore_session("u1", "s1")
        session.save(client, [], self.path)
        self.assertNotIn("password", open(self.path).read().lower())


class TestMasking(unittest.TestCase):
    def test_secrets_are_replaced_by_their_length(self):
        out = session.mask({"aeskey": "ab" * 16, "temp": 230})
        self.assertEqual(out["temp"], 230)
        self.assertNotIn("ab", out["aeskey"])
        self.assertIn("32 caratteri", out["aeskey"])

    def test_masking_is_recursive(self):
        out = session.mask({"event": {"endpoint": {"did": "D" * 32}}})
        self.assertIn("caratteri", out["event"]["endpoint"]["did"])

    def test_lists_are_handled(self):
        out = session.mask([{"mac": "aa:bb:cc:dd:ee:ff"}])
        self.assertIn("caratteri", out[0]["mac"])

    def test_empty_values_are_left_alone(self):
        self.assertEqual(session.mask({"aeskey": ""}), {"aeskey": ""})


if __name__ == "__main__":
    unittest.main()
