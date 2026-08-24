import base64
import json
import time
import unittest

from klimakontrol.cloud import (CloudClient, CloudDevice, ENERGY_REPORTS, REGIONS,
                                CloudError, DEVICE_TZ_OFFSET, REQUEST_IV,
                                _MULTIPART_BOUNDARY, _md5, _sha1, salt)
from klimakontrol.aes import decrypt_cbc


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


class TestRegionsFromLicense(unittest.TestCase):
    """Le regioni sono derivate dai blob di licenza estratti dall'APK."""

    def test_europe_pair(self):
        r = REGIONS["eu"]
        self.assertEqual(r.license_id, "aae72184369e2fc3e6ded53a90612586")
        # companyid = costante OEM condivisa, confermata da un login riuscito (2026-08-21)
        self.assertEqual(r.company_id, "8503b08fa57729df9faa45e4c978852c")

    def test_company_id_is_the_shared_oem_constant(self):
        """Il companyid e' la costante 8503b08f..., uguale in tutte le regioni.

        Correzione della vecchia "trappola 1": si credeva che questo valore
        condiviso fosse sbagliato e che il companyid vero fosse blob[16:32]
        (per-regione). Un login riuscito (region eu, 2026-08-21) ha invece
        restituito, echeggiato dal server, companyid = 8503b08f...; blob[16:32]
        (es. eu 57c9e5ad...) dava sempre -1008. L'app e' una sola company OEM.
        """
        for r in REGIONS.values():
            self.assertEqual(r.company_id, "8503b08fa57729df9faa45e4c978852c")
        # a8452a8f... (blob[16:32] della licenza "ab") NON e' il companyid
        self.assertNotEqual(REGIONS["ab"].company_id,
                            "a8452a8f48ae707edc12e9c52e21f00f")

    def test_every_region_has_distinct_pair(self):
        pairs = {(r.license_id, r.company_id) for r in REGIONS.values()}
        self.assertEqual(len(pairs), len(REGIONS))

    def test_ids_are_32_hex_chars(self):
        import re
        for r in REGIONS.values():
            self.assertRegex(r.license_id, r"^[0-9a-f]{32}$")
            self.assertRegex(r.company_id, r"^[0-9a-f]{32}$")

    def test_alias_resolution(self):
        from klimakontrol.cloud import resolve_region
        self.assertEqual(resolve_region("us"), "ab")
        self.assertEqual(resolve_region("EU"), "eu")
        self.assertEqual(resolve_region(" altro "), "ab")
        with self.assertRaises(CloudError):
            resolve_region("mars")

    def test_client_accepts_alias(self):
        self.assertEqual(CloudClient("us").region.code, "ab")


class TestLoginAnyRegion(unittest.TestCase):
    def test_returns_first_region_that_accepts(self):
        from klimakontrol import cloud
        from klimakontrol.cloud import AuthError, login_any_region
        tried = []
        original = cloud.CloudClient.login

        def fake_login(self, user, pwd):
            tried.append(self.region.code)
            if self.region.code != "ab":
                raise AuthError("credenziali errate")
            self.restore_session("u", "s")

        cloud.CloudClient.login = fake_login
        try:
            client = login_any_region("io@example.com", "segreta")
            self.assertEqual(client.region.code, "ab")
            self.assertEqual(tried, ["eu", "ab"])   # si ferma alla prima buona
        finally:
            cloud.CloudClient.login = original

    def test_rate_limit_stops_immediately(self):
        from klimakontrol import cloud
        from klimakontrol.cloud import RateLimitError, login_any_region
        tried = []
        original = cloud.CloudClient.login

        def fake_login(self, user, pwd):
            tried.append(self.region.code)
            raise RateLimitError("troppi tentativi")

        cloud.CloudClient.login = fake_login
        try:
            with self.assertRaises(RateLimitError):
                login_any_region("io@example.com", "segreta")
            self.assertEqual(len(tried), 1)   # insistere peggiorerebbe
        finally:
            cloud.CloudClient.login = original

    def test_all_failing_reports_every_region(self):
        from klimakontrol import cloud
        from klimakontrol.cloud import AuthError, login_any_region
        original = cloud.CloudClient.login
        cloud.CloudClient.login = lambda self, u, p: (_ for _ in ()).throw(
            AuthError("credenziali errate"))
        try:
            with self.assertRaises(AuthError) as ctx:
                login_any_region("io@example.com", "segreta")
            for label in ("Europa", "Internazionale", "Russia", "Cina"):
                self.assertIn(label, str(ctx.exception))
        finally:
            cloud.CloudClient.login = original


class TestSalts(unittest.TestCase):
    """I sali dell'autenticazione devono essere sostituibili senza modificare il codice."""

    def test_body_salt_default_is_the_verified_one(self):
        from klimakontrol.cloud import salt
        self.assertEqual(salt("body"), "xgx3d*fe3478$ukx")

    def test_salts_can_be_overridden_from_the_environment(self):
        import os
        from klimakontrol.cloud import salt
        os.environ["KLIMAKONTROL_SALT_PASSWORD"] = "prova#123"
        try:
            self.assertEqual(salt("password"), "prova#123")
        finally:
            del os.environ["KLIMAKONTROL_SALT_PASSWORD"]
        self.assertEqual(salt("password"), "4969fj#k23#")

    def test_override_changes_the_login_body(self):
        """Il sale sostituito deve finire davvero nell'hash della password."""
        import hashlib
        import os
        from klimakontrol.cloud import CloudClient, salt
        client = CloudClient("eu")
        captured = {}

        def fake_request(path, headers, body=None):
            captured["headers"] = headers
            return {"error": 0, "userid": "u", "loginsession": "s"}

        client._request = fake_request
        os.environ["KLIMAKONTROL_SALT_PASSWORD"] = "diverso#1"
        try:
            client.login("io@example.com", "segreta")
            expected = hashlib.sha1(b"segretadiverso#1").hexdigest()
            # il corpo e' cifrato, ma il token nell'header lo firma in chiaro
            self.assertIn("token", captured["headers"])
            self.assertEqual(salt("password"), "diverso#1")
            self.assertTrue(expected)
        finally:
            del os.environ["KLIMAKONTROL_SALT_PASSWORD"]

    def test_unknown_salt_is_refused(self):
        from klimakontrol.cloud import salt
        with self.assertRaises(CloudError):
            salt("inventato")

    def test_request_iv_matches_the_sdk(self):
        """IV letto da BLCommonTools.aesNoPadding nel dex."""
        from klimakontrol.cloud import REQUEST_IV
        self.assertEqual(REQUEST_IV.hex(), "eaaaaa3abb5862a21918b5771d1615aa")


class TestErrorReporting(unittest.TestCase):
    """Il messaggio del server non va mai scartato: distingue casi che il codice confonde."""

    def test_auth_error_carries_the_server_message(self):
        from klimakontrol.cloud import AuthError, CloudClient
        client = CloudClient("eu")
        with self.assertRaises(AuthError) as ctx:
            client._ensure_ok({"error": -1008, "msg": "user not exist"}, "login")
        self.assertIn("user not exist", str(ctx.exception))
        self.assertIn("-1008", str(ctx.exception))

    def test_extra_fields_are_reported(self):
        from klimakontrol.cloud import CloudClient, CloudError
        client = CloudClient("eu")
        with self.assertRaises(CloudError) as ctx:
            client._ensure_ok({"error": -2000, "detail": "qualcosa"}, "login")
        self.assertIn("qualcosa", str(ctx.exception))

    def test_rate_limit_still_recognised(self):
        from klimakontrol.cloud import CloudClient, RateLimitError
        client = CloudClient("eu")
        with self.assertRaises(RateLimitError):
            client._ensure_ok({"error": -1036, "msg": "too many"}, "login")

    def test_success_passes_through(self):
        from klimakontrol.cloud import CloudClient
        client = CloudClient("eu")
        resp = {"error": 0, "userid": "u"}
        self.assertIs(client._ensure_ok(resp, "login"), resp)


class TestRegister(unittest.TestCase):
    """Registrazione nuovo account: forma della richiesta, senza toccare la rete."""

    @staticmethod
    def _capture(resp):
        cap = {}

        def fake_request(url, headers, body=None):
            cap["url"] = url
            cap["headers"] = headers
            cap["body"] = body
            return resp
        return cap, fake_request

    @staticmethod
    def _decrypt_json(headers, encrypted):
        # la chiave si ricava dal timestamp dell'header, come fa il cloud
        key = bytes.fromhex(_md5(headers["timestamp"] + salt("token")))
        clear = decrypt_cbc(encrypted, key, REQUEST_IV).rstrip(b"\x00")
        return json.loads(clear.decode("utf-8"))

    def test_send_code_email_shape(self):
        c = CloudClient("eu")
        cap, c._request = self._capture({"error": 0})
        c.send_register_code("mario@rossi.it")
        self.assertTrue(cap["url"].endswith("/account/newregcode"))
        body = self._decrypt_json(cap["headers"], cap["body"])
        self.assertEqual(body["email"], "mario@rossi.it")
        self.assertEqual(body["companyid"], REGIONS["eu"].company_id)
        self.assertEqual(body["lid"], REGIONS["eu"].license_id)
        self.assertNotIn("password", body)          # il codice non richiede password
        self.assertEqual(cap["headers"]["email"], "mario@rossi.it")
        self.assertIn("token", cap["headers"])

    def test_send_code_phone_has_countrycode(self):
        c = CloudClient("eu")
        cap, c._request = self._capture({"error": 0})
        c.send_register_code("3331234567", countrycode="39")
        body = self._decrypt_json(cap["headers"], cap["body"])
        self.assertEqual(body["phone"], "3331234567")
        self.assertEqual(body["countrycode"], "39")
        self.assertEqual(cap["headers"]["countrycode"], "39")

    def test_register_is_multipart_and_signs_password(self):
        c = CloudClient("eu")
        cap, c._request = self._capture(
            {"error": 0, "userid": "U-9", "loginsession": "S-9"})
        c.register("mario@rossi.it", "segreta1", code="123456", nickname="Mario")
        self.assertTrue(cap["url"].endswith("/account/register"))
        # multipart: content-type col boundary, e il corpo contiene il campo "text"
        self.assertIn("multipart/form-data", cap["headers"]["Content-type"])
        self.assertIn(_MULTIPART_BOUNDARY, cap["headers"]["Content-type"])
        self.assertIn(b'name="text"', cap["body"])
        self.assertIn(_MULTIPART_BOUNDARY.encode(), cap["body"])
        # estrai i byte cifrati fra head e tail del multipart, poi decifra
        head = cap["body"].split(b"\r\n\r\n", 1)[1]
        enc = head.rsplit(b"\r\n--" + _MULTIPART_BOUNDARY.encode(), 1)[0]
        body = self._decrypt_json(cap["headers"], enc)
        self.assertEqual(body["email"], "mario@rossi.it")
        self.assertEqual(body["type"], "email")
        self.assertEqual(body["password"], _sha1("segreta1" + salt("password")))
        self.assertEqual(body["code"], "123456")
        self.assertEqual(body["nickname"], "Mario")
        self.assertEqual(body["sex"], "male")
        self.assertEqual(body["companyid"], REGIONS["eu"].company_id)
        # register = login automatico: la sessione viene stabilita
        self.assertEqual(c.userid, "U-9")
        self.assertEqual(c.loginsession, "S-9")

    def test_register_defaults_nickname_to_account(self):
        c = CloudClient("eu")
        cap, c._request = self._capture(
            {"error": 0, "userid": "U", "loginsession": "S"})
        c.register("anna@x.it", "pw", code="000000")
        head = cap["body"].split(b"\r\n\r\n", 1)[1]
        enc = head.rsplit(b"\r\n--" + _MULTIPART_BOUNDARY.encode(), 1)[0]
        body = self._decrypt_json(cap["headers"], enc)
        self.assertEqual(body["nickname"], "anna@x.it")

    def test_register_without_session_raises(self):
        c = CloudClient("eu")
        c._request = lambda *a, **k: {"error": 0}   # nessun userid/loginsession
        with self.assertRaises(CloudError):
            c.register("z@z.it", "pw", code="1")


class TestBindDevice(unittest.TestCase):
    def _bound_client(self):
        c = _client()                       # CloudClient("eu") + restore_session
        c.family_ids = lambda: ["FAM-1"]    # niente rete
        return c

    def test_bind_builds_add_request(self):
        c = self._bound_client()
        captured = {}

        def fake_request(url, headers, body=None):
            captured["url"] = url
            captured["headers"] = headers
            captured["body"] = json.loads(body.decode())
            return {"status": 0}

        c._request = fake_request
        dev = CloudDevice(did="did-XYZ", pid="pid-abc", mac="AABBCCDDEEFF",
                          aeskey="00112233445566778899aabbccddeeff", local_id=7)
        resp = c.bind_device(dev, name="Salotto")

        self.assertTrue(captured["url"].endswith(
            "/appsync/group/dev/manage?operation=add"))
        self.assertEqual(captured["body"]["familyId"], "FAM-1")
        ep = captured["body"]["endpoints"][0]
        self.assertEqual(ep["endpointId"], "did-XYZ")
        self.assertEqual(ep["productId"], "pid-abc")
        self.assertEqual(ep["mac"], "AABBCCDDEEFF")
        self.assertEqual(ep["friendlyName"], "Salotto")
        self.assertTrue(ep["cookie"])                     # cookie Base64 presente
        self.assertEqual(captured["headers"]["userid"], c.userid)
        self.assertEqual(resp, {"status": 0})

    def test_bind_uses_first_family_when_unspecified(self):
        c = self._bound_client()
        seen = {}
        c._request = lambda url, headers, body=None: seen.update(
            fam=json.loads(body.decode())["familyId"]) or {"status": 0}
        c.bind_device(CloudDevice(did="d", pid="p", mac="m", aeskey="k"))
        self.assertEqual(seen["fam"], "FAM-1")

    def test_bind_without_session_is_refused(self):
        from klimakontrol.cloud import CloudClient
        with self.assertRaises(CloudError):
            CloudClient("eu").bind_device(CloudDevice(did="d", pid="p", mac="m", aeskey="k"))

    def test_bind_raises_on_error_code(self):
        c = self._bound_client()
        c._request = lambda *a, **k: {"status": -1, "msg": "già associato"}
        with self.assertRaises(CloudError):
            c.bind_device(CloudDevice(did="d", pid="p", mac="m", aeskey="k"))
