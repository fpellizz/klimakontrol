import base64
import json
import time
import unittest

from klimakontrol.cloud import (CloudClient, CloudDevice, ENERGY_REPORTS, REGIONS,
                                CloudError, DEVICE_TZ_OFFSET)


def _client():
    c = CloudClient("eu")
    c.restore_session("utente-1", "sessione-1")
    return c


def _device():
    return CloudDevice(did="D" * 32, pid="0" * 24 + "7c500000",
                       mac="aa:bb:cc:dd:ee:ff", aeskey="ab" * 16,
                       devtype=0x507C, name="Salotto")


class TestRegions(unittest.TestCase):
    def test_europe_base_url(self):
        self.assertEqual(
            REGIONS["eu"].base_url,
            "https://aae72184369e2fc3e6ded53a90612586appservice.ibroadlink.com")

    def test_unknown_region_refused(self):
        with self.assertRaises(CloudError):
            CloudClient("mars")

    def test_all_regions_have_distinct_license_ids(self):
        ids = [r.license_id for r in REGIONS.values()]
        self.assertEqual(len(ids), len(set(ids)))


class TestDirective(unittest.TestCase):
    def test_header_shape(self):
        d = _client().build_directive(_device(), {"act": "get"}, now=1700000000)
        header = d["directive"]["header"]
        self.assertEqual(header["namespace"], "DNA.KeyValueControl")
        self.assertEqual(header["name"], "KeyValueControl")
        self.assertEqual(header["interfaceVersion"], "2")
        self.assertEqual(header["messageId"], "%s-1700000000" % ("D" * 32))
        # il typo "timstamp" e' nell'SDK originale: replicarlo e' voluto
        self.assertIn("timstamp", header)
        self.assertNotIn("timestamp", header)

    def test_raw_passthrough_uses_transmission_namespace(self):
        d = _client().build_directive(_device(), {}, raw_passthrough=True, now=1)
        self.assertEqual(d["directive"]["header"]["namespace"], "DNA.TransmissionControl")
        self.assertEqual(d["directive"]["header"]["name"], "commonControl")

    def test_cookie_carries_the_device_key(self):
        dev = _device()
        d = _client().build_directive(dev, {}, now=1)
        cookie = d["directive"]["endpoint"]["devicePairedInfo"]["cookie"]
        decoded = json.loads(base64.b64decode(cookie))
        self.assertEqual(decoded["device"]["aeskey"], dev.aeskey)
        self.assertEqual(decoded["device"]["key"], dev.aeskey)
        self.assertEqual(decoded["device"]["did"], dev.did)
        self.assertEqual(decoded["device"]["id"], 1)

    def test_endpoint_id_is_the_did(self):
        dev = _device()
        d = _client().build_directive(dev, {}, now=1)
        self.assertEqual(d["directive"]["endpoint"]["endpointId"], dev.did)

    def test_short_dev_session_is_not_sent(self):
        dev = _device()
        dev.dev_session = "troppo-corta"
        d = _client().build_directive(dev, {}, now=1)
        self.assertNotIn("devSession", d["directive"]["endpoint"])

    def test_long_dev_session_is_sent(self):
        dev = _device()
        dev.dev_session = "x" * 112
        d = _client().build_directive(dev, {}, now=1)
        self.assertEqual(d["directive"]["endpoint"]["devSession"], "x" * 112)

    def test_payload_is_passed_through_untouched(self):
        payload = {"act": "set", "params": ["pwr"], "vals": [[{"val": 1, "idx": 1}]]}
        d = _client().build_directive(_device(), payload, now=1)
        self.assertEqual(d["directive"]["payload"], payload)


class TestQueryState(unittest.TestCase):
    def test_batch_payload_lists_every_device(self):
        c = _client()
        devices = [_device(), CloudDevice(did="E" * 32, pid="p", mac="m",
                                          aeskey="a", devtype=0x507A)]
        captured = {}

        def fake_request(url, headers, body=None):
            captured["url"] = url
            captured["headers"] = headers
            captured["body"] = json.loads(body)
            return {"event": {}}

        c._request = fake_request
        c.query_state(devices, now=1700000000)
        payload = captured["body"]["directive"]["payload"]
        self.assertEqual(payload["msgtype"], "batch")
        self.assertEqual([e["did"] for e in payload["studata"]], ["D" * 32, "E" * 32])
        self.assertEqual(payload["studata"][1]["devtype"], 0x507A)
        self.assertTrue(captured["url"].endswith("/device/control/v2/querystate"))
        # querystate e' l'unica chiamata di controllo che vuole anche companyid
        self.assertIn("companyid", captured["headers"])

    def test_control_headers_carry_the_session(self):
        c = _client()
        headers = c._control_headers()
        self.assertEqual(headers["userid"], "utente-1")
        self.assertEqual(headers["loginsession"], "sessione-1")
        self.assertNotIn("companyid", headers)


class TestSdkControl(unittest.TestCase):
    def test_url_and_session_rotation(self):
        c, dev = _client(), _device()
        captured = {}

        def fake_request(url, headers, body=None):
            captured["url"] = url
            return {"event": {"endpoint": {"endpointId": dev.did, "devSession": "s" * 120},
                              "payload": {"status": 0, "msg": "success"}}}

        c._request = fake_request
        out = c.sdk_control(dev, {"act": "get"})
        self.assertEqual(out["status"], 0)
        self.assertIn("/device/control/v2/sdkcontrol?license=", captured["url"])
        self.assertEqual(dev.dev_session, "s" * 120)   # la sessione va conservata

    def test_missing_event_is_an_error(self):
        c = _client()
        c._request = lambda *a, **k: {"status": -1012, "msg": "device offline"}
        with self.assertRaises(CloudError):
            c.sdk_control(_device(), {"act": "get"})

    def test_set_state_builds_dna_payload(self):
        c, dev = _client(), _device()
        captured = {}

        def fake_control(device, payload):
            captured["payload"] = payload
            return {"data": {"params": ["pwr"], "vals": [[{"val": 1, "idx": 1}]]}}

        c.sdk_control = fake_control
        out = c.set_state(dev, {"pwr": 1})
        self.assertEqual(captured["payload"]["act"], "set")
        self.assertEqual(captured["payload"]["params"], ["pwr"])
        self.assertEqual(out, {"pwr": 1})


class TestEnergy(unittest.TestCase):
    def test_reports_match_the_app(self):
        self.assertEqual(ENERGY_REPORTS["hour"], "fw_tcldaystatus_v1")
        self.assertEqual(ENERGY_REPORTS["day"], "fw_tclmonthstatus_v1")
        self.assertEqual(ENERGY_REPORTS["month"], "fw_tclyearstatus_v1")

    def test_times_are_expressed_in_device_timezone(self):
        c = _client()
        captured = {}
        c._request = lambda url, headers, body=None: captured.update(
            url=url, body=json.loads(body)) or {"table": []}
        c.energy(_device(), "hour", start=0, end=0)
        dev_time = captured["body"]["device"][0]["start"]
        self.assertEqual(dev_time, time.strftime("%Y-%m-%d_%H:%M:%S",
                                                 time.gmtime(DEVICE_TZ_OFFSET)))
        self.assertTrue(captured["url"].endswith("/dataservice/v1/device/stats"))

    def test_invalid_granularity_refused(self):
        with self.assertRaises(CloudError):
            _client().energy(_device(), "settimana")

    def test_status_history_endpoint(self):
        c = _client()
        captured = {}
        c._request = lambda url, headers, body=None: captured.update(url=url) or {}
        c.status_history(_device())
        self.assertTrue(captured["url"].endswith("/dataservice/v1/device/status"))


class TestSessionGuard(unittest.TestCase):
    def test_control_without_login_is_refused(self):
        with self.assertRaises(CloudError):
            CloudClient("eu").sdk_control(_device(), {})

    def test_device_parsing_from_cloud_payload(self):
        raw = {"did": "abc", "pid": "PID", "mac": "AA:BB", "aeskey": "FF" * 16,
               "lanaddr": "192.168.1.9", "devtype": 20604, "id": 1}
        dev = CloudDevice.from_cloud(raw, name="Camera")
        self.assertEqual(dev.name, "Camera")
        self.assertEqual(dev.pid, "pid")          # normalizzato minuscolo
        self.assertEqual(dev.aeskey, "ff" * 16)
        self.assertEqual(dev.lanaddr, "192.168.1.9")
        self.assertEqual(dev.devtype, 20604)


if __name__ == "__main__":
    unittest.main()
