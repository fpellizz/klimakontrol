# Onboarding Step 1 — Bind cloud — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add to the library and to the CLI the ability to **register a device in the cloud account** (bind) given `did/pid/mac/devkey`, so that once configured via SoftAP (Step 2) the unit appears in `list`/`status` and is controllable.

**Architecture:** A single new method `CloudClient.bind_device()` that composes a cleartext JSON POST to `<lid>appservice.ibroadlink.com/appsync/group/dev/manage?operation=add` (host and path derived from the APK: `BLFamilyManager.addEndpoint`), with the device key in the `cookie` field (reuse of `build_cookie`, like `sdkcontrol`) and authentication in the headers (`_control_headers`). Plus a CLI command `bind`. All tested offline by replacing `_request`.

**Tech Stack:** Python 3.8+ stdlib only (no runtime dependencies). `unittest`.

**Spec:** `docs/superpowers/specs/2026-08-25-onboarding-provisioning-design.md` (Step 1, §3).

## Global Constraints

- **Zero runtime dependencies**: stdlib only. `requests`/`cryptography`/`pycryptodome` are forbidden.
- **The tests do not touch the network**: never. `CloudClient._request` is replaced with a fake that captures the arguments. `python3 -m unittest discover -s tests -q` must stay all green.
- **Secrets not in the logs nor in the output**: the `devkey` is an AES key → every print runs through `session.mask()`. Never print `devkey`, `did`, `mac`, `cookie` in cleartext.
- **Language**: comments and user messages in **Italian**; identifiers in **English**.
- **Bind host**: `https://<lid>appservice.ibroadlink.com` (== `self.base`). **Path**: `/appsync/group/dev/manage?operation=add`.
- **Bind body**: cleartext JSON (the app sends it as `text/plain`, NOT encrypted like `_ec4`).

---

### Task 1: `CloudClient.bind_device` (library)

**Files:**
- Modify: `klimakontrol/cloud.py` (add the method in the `CloudClient` class, near `sdk_control`, after line ~550)
- Test: `tests/test_cloud.py`

**Interfaces:**
- Consumes (already existing in `cloud.py`):
  - `CloudDevice(did, pid, mac, aeskey, name="", local_id=1, ...)` — dataclass
  - `CloudClient.build_cookie(dev: CloudDevice) -> str` — Base64 with the device key
  - `CloudClient._control_headers() -> Dict[str,str]` — headers with userid/loginsession/licenseid/lid
  - `CloudClient._request(url, headers, body: bytes) -> Dict` — POST, returns a JSON dict
  - `CloudClient._ensure_ok(resp, what) -> Dict` — raises on code != 0
  - `CloudClient.family_ids() -> List[str]` — the ids of the account's families
  - `_jd(obj) -> str` — compact `json.dumps` (module `cloud`)
  - `self.base` — host `https://<lid>appservice.ibroadlink.com`
- Produces (used by Task 2 and by Step 2):
  - `CloudClient.bind_device(dev: CloudDevice, name: str = "", family_id: Optional[str] = None, room_id: str = "") -> Dict[str, Any]`

- [ ] **Step 1: Write the failing test**

Add to `tests/test_cloud.py` (at the bottom, reusing the `_client()` helper already present at line 12):

```python
class BindDevice(unittest.TestCase):
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
```

Check that `CloudError` and `CloudDevice` are already imported at the top of `tests/test_cloud.py`; if `CloudError` is missing, add it to the existing import `from klimakontrol.cloud import ...`.

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest tests.test_cloud -q`
Expected: FAIL — `AttributeError: 'CloudClient' object has no attribute 'bind_device'`.

- [ ] **Step 3: Write minimal implementation**

In `klimakontrol/cloud.py`, inside `class CloudClient`, right after `sdk_control` (~line 550):

```python
    def bind_device(self, dev: CloudDevice, name: str = "",
                    family_id: Optional[str] = None, room_id: str = "") -> Dict[str, Any]:
        """Registra un dispositivo appena configurato nella famiglia (casa cloud) dell'account.

        `dev` porta did/pid/mac/aeskey — dalla config SoftAP (Punto 2) o inseriti a mano.
        Se `family_id` manca, usa la prima famiglia dell'account. La chiave viaggia nel
        campo `cookie` (Base64), come per `sdkcontrol`. Ricostruito da `BLFamilyManager.addEndpoint`.
        """
        self._require_session()
        family = family_id or (self.family_ids() or [""])[0]
        if not family:
            raise CloudError("bind: nessuna famiglia disponibile per l'account")
        endpoint = {
            "productId": dev.pid,
            "endpointId": dev.did,
            "mac": dev.mac,
            "friendlyName": name or dev.name or dev.did,
            "cookie": self.build_cookie(dev),
            "roomId": room_id,
            "order": 0,
        }
        body = {"familyId": family, "endpoints": [endpoint]}
        url = "%s/appsync/group/dev/manage?operation=add" % self.base
        headers = self._control_headers()
        headers["Content-type"] = "text/plain;charset=utf-8"   # come l'app: body JSON in chiaro
        return self._ensure_ok(self._request(url, headers, _jd(body).encode()), "bind dispositivo")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m unittest tests.test_cloud -q`
Expected: PASS (the 4 new tests green).

- [ ] **Step 5: Run the full suite**

Run: `python3 -m unittest discover -s tests -q`
Expected: all green (no regression; the count goes up by 4).

- [ ] **Step 6: Commit**

```bash
git add klimakontrol/cloud.py tests/test_cloud.py
git commit -m "feat(cloud): bind_device — registra un modulo nell'account (POST /appsync/.../add)

Ricostruito da BLFamilyManager.addEndpoint: POST JSON in chiaro all'host
appservice, chiave nel campo cookie come sdkcontrol, familyId dalla prima
famiglia. Coperto da test offline (nessuna rete)."
```

---

### Task 2: CLI command `bind`

**Files:**
- Modify: `klimakontrol/cli.py` (new `cmd_bind` near the other `cmd_*`, and an `add_parser("bind", ...)` in the argument builder, after `register` ~line 254)
- Test: `tests/test_cli.py` if it exists; otherwise add the test in `tests/test_cloud.py` as a function that invokes `cmd_bind` with a fake client.

**Interfaces:**
- Consumes:
  - `session.load() -> (CloudClient, List[CloudDevice])` (module `klimakontrol.session`, already used by `cmd_list`)
  - `session.mask(obj) -> obj` — masks secrets
  - `CloudClient.bind_device(...)` (Task 1)
  - `CloudDevice(...)` dataclass
- Produces:
  - subcommand `klimakontrol bind --did … --pid … --mac … --key … [--name …] [--family …]`

- [ ] **Step 1: Write the failing test**

Create `tests/test_cli.py` (if it is not there) with:

```python
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest tests.test_cli -q`
Expected: FAIL — `AttributeError: module 'klimakontrol.cli' has no attribute 'cmd_bind'`.

- [ ] **Step 3: Write minimal implementation**

In `klimakontrol/cli.py`, add the function (near `cmd_register`, ~line 145):

```python
def cmd_bind(args) -> None:
    cli, devices = session.load()
    dev = CloudDevice(did=args.did, pid=args.pid, mac=args.mac, aeskey=args.key,
                      name=args.name or "")
    resp = cli.bind_device(dev, name=args.name or "", family_id=args.family)
    print("Registrato %s (%s)" % (args.name or args.did, resp.get("msg", "ok")))
    print(json.dumps(session.mask(resp), indent=1, ensure_ascii=False))
    session.save(cli, devices)
```

And register the subcommand in the parser builder, after the `register` block (~line 254):

```python
    sp = sub.add_parser("bind", help="registra un modulo gia' configurato nell'account")
    sp.add_argument("--did", required=True, help="device id restituito dalla config SoftAP")
    sp.add_argument("--pid", required=True, help="product id del modello")
    sp.add_argument("--mac", required=True, help="MAC del modulo")
    sp.add_argument("--key", required=True, help="chiave AES del dispositivo (devkey)")
    sp.add_argument("--name", help="nome da dare all'unita'")
    sp.add_argument("--family", help="id famiglia (default: la prima dell'account)")
    sp.set_defaults(func=cmd_bind)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m unittest tests.test_cli -q`
Expected: PASS.

- [ ] **Step 5: Run the full suite + CLI smoke test**

Run: `python3 -m unittest discover -s tests -q` → all green.
Run: `python3 -m klimakontrol bind --help` → shows the options without errors.

- [ ] **Step 6: Commit**

```bash
git add klimakontrol/cli.py tests/test_cli.py
git commit -m "feat(cli): comando bind — registra un modulo nell'account da did/pid/mac/key

Chiude il Punto 1 dell'onboarding: dopo la config SoftAP (o con dati noti) si
registra l'unita' e compare in list/status. Output mascherato."
```

---

## Notes for the executor

- **`Optional` import**: `bind_device` uses `Optional[str]`. `cloud.py` already imports `Optional` from `typing` (used elsewhere); check and, if it is missing, add it.
- **Endpoint field names**: `productId/endpointId/mac/friendlyName/cookie/roomId/order` are derived from `BLEndpointInfo` (dex). They are our statically-verified hypothesis; the **confirmation on the real server** happens at Step 3 (HW) with `KLIMAKONTROL_DEBUG=1`. If the cloud rejects the bind, read the server message and correct the field set here — it is the only point to touch.
- This plan covers **only Step 1**. Step 2 (SoftAP config) first requires the disassembly of `apk/split_config.arm64_v8a.apk!libNetworkAPI.so` to derive the packet and write its **golden test**: it will have its own dedicated plan. Steps 3 (HW) and 4 (app wizard) follow.
