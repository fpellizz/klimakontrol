# Onboarding Punto 1 — Bind cloud — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aggiungere alla libreria e alla CLI la capacità di **registrare un dispositivo nell'account cloud** (bind) dati `did/pid/mac/devkey`, così che una volta configurato via SoftAP (Punto 2) l'unità compaia in `list`/`status` e sia controllabile.

**Architecture:** Un solo nuovo metodo `CloudClient.bind_device()` che compone un POST JSON in chiaro a `<lid>appservice.ibroadlink.com/appsync/group/dev/manage?operation=add` (host e percorso ricavati dall'APK: `BLFamilyManager.addEndpoint`), con la chiave del dispositivo nel campo `cookie` (riuso di `build_cookie`, come `sdkcontrol`) e autenticazione negli header (`_control_headers`). Più un comando CLI `bind`. Tutto testato offline sostituendo `_request`.

**Tech Stack:** Python 3.8+ stdlib soltanto (nessuna dipendenza a runtime). `unittest`.

**Spec:** `docs/superpowers/specs/2026-08-25-onboarding-provisioning-design.md` (Punto 1, §3).

## Global Constraints

- **Zero dipendenze a runtime**: solo la stdlib. Vietato `requests`/`cryptography`/`pycryptodome`.
- **I test non toccano la rete**: mai. Si sostituisce `CloudClient._request` con un fake che cattura gli argomenti. `python3 -m unittest discover -s tests -q` deve restare tutto verde.
- **I segreti non nei log né nell'output**: la `devkey` è una chiave AES → ogni stampa passa da `session.mask()`. Mai stampare `devkey`, `did`, `mac`, `cookie` in chiaro.
- **Lingua**: commenti e messaggi utente in **italiano**; identificatori in **inglese**.
- **Host del bind**: `https://<lid>appservice.ibroadlink.com` (== `self.base`). **Percorso**: `/appsync/group/dev/manage?operation=add`.
- **Corpo del bind**: JSON in chiaro (l'app lo manda come `text/plain`, NON cifrato come `_ec4`).

---

### Task 1: `CloudClient.bind_device` (libreria)

**Files:**
- Modify: `klimakontrol/cloud.py` (aggiungere il metodo nella classe `CloudClient`, vicino a `sdk_control`, dopo la riga ~550)
- Test: `tests/test_cloud.py`

**Interfaces:**
- Consumes (già esistenti in `cloud.py`):
  - `CloudDevice(did, pid, mac, aeskey, name="", local_id=1, ...)` — dataclass
  - `CloudClient.build_cookie(dev: CloudDevice) -> str` — Base64 con la chiave del device
  - `CloudClient._control_headers() -> Dict[str,str]` — header con userid/loginsession/licenseid/lid
  - `CloudClient._request(url, headers, body: bytes) -> Dict` — POST, ritorna JSON dict
  - `CloudClient._ensure_ok(resp, what) -> Dict` — solleva su codice != 0
  - `CloudClient.family_ids() -> List[str]` — id delle famiglie dell'account
  - `_jd(obj) -> str` — `json.dumps` compatto (modulo `cloud`)
  - `self.base` — host `https://<lid>appservice.ibroadlink.com`
- Produces (usato da Task 2 e dal Punto 2):
  - `CloudClient.bind_device(dev: CloudDevice, name: str = "", family_id: Optional[str] = None, room_id: str = "") -> Dict[str, Any]`

- [ ] **Step 1: Write the failing test**

Aggiungi in `tests/test_cloud.py` (in fondo, riusa l'helper `_client()` già presente a riga 12):

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

Verifica che `CloudError` e `CloudDevice` siano già importati in cima a `tests/test_cloud.py`; se manca `CloudError`, aggiungilo all'import esistente `from klimakontrol.cloud import ...`.

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest tests.test_cloud -q`
Expected: FAIL — `AttributeError: 'CloudClient' object has no attribute 'bind_device'`.

- [ ] **Step 3: Write minimal implementation**

In `klimakontrol/cloud.py`, dentro `class CloudClient`, subito dopo `sdk_control` (~riga 550):

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
Expected: PASS (i 4 nuovi test verdi).

- [ ] **Step 5: Run the full suite**

Run: `python3 -m unittest discover -s tests -q`
Expected: tutti verdi (nessuna regressione; il conteggio sale di 4).

- [ ] **Step 6: Commit**

```bash
git add klimakontrol/cloud.py tests/test_cloud.py
git commit -m "feat(cloud): bind_device — registra un modulo nell'account (POST /appsync/.../add)

Ricostruito da BLFamilyManager.addEndpoint: POST JSON in chiaro all'host
appservice, chiave nel campo cookie come sdkcontrol, familyId dalla prima
famiglia. Coperto da test offline (nessuna rete)."
```

---

### Task 2: comando CLI `bind`

**Files:**
- Modify: `klimakontrol/cli.py` (nuova `cmd_bind` vicino alle altre `cmd_*`, e un `add_parser("bind", ...)` nel costruttore degli argomenti, dopo `register` ~riga 254)
- Test: `tests/test_cli.py` se esiste; altrimenti aggiungi il test in `tests/test_cloud.py` come funzione che invoca `cmd_bind` con un client fittizio.

**Interfaces:**
- Consumes:
  - `session.load() -> (CloudClient, List[CloudDevice])` (modulo `klimakontrol.session`, già usato da `cmd_list`)
  - `session.mask(obj) -> obj` — maschera segreti
  - `CloudClient.bind_device(...)` (Task 1)
  - `CloudDevice(...)` dataclass
- Produces:
  - subcomando `klimakontrol bind --did … --pid … --mac … --key … [--name …] [--family …]`

- [ ] **Step 1: Write the failing test**

Crea `tests/test_cli.py` (se non c'è) con:

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

In `klimakontrol/cli.py`, aggiungi la funzione (vicino a `cmd_register`, ~riga 145):

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

E registra il subcomando nel costruttore del parser, dopo il blocco `register` (~riga 254):

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

- [ ] **Step 5: Run the full suite + smoke della CLI**

Run: `python3 -m unittest discover -s tests -q` → tutti verdi.
Run: `python3 -m klimakontrol bind --help` → mostra le opzioni senza errori.

- [ ] **Step 6: Commit**

```bash
git add klimakontrol/cli.py tests/test_cli.py
git commit -m "feat(cli): comando bind — registra un modulo nell'account da did/pid/mac/key

Chiude il Punto 1 dell'onboarding: dopo la config SoftAP (o con dati noti) si
registra l'unita' e compare in list/status. Output mascherato."
```

---

## Note per l'esecutore

- **`Optional` import**: `bind_device` usa `Optional[str]`. `cloud.py` importa già `Optional` da `typing` (usato altrove); verifica e, se mancasse, aggiungilo.
- **Nomi dei campi endpoint**: `productId/endpointId/mac/friendlyName/cookie/roomId/order` sono ricavati da `BLEndpointInfo` (dex). Sono la nostra ipotesi verificata staticamente; la **conferma sul server reale** avviene al Punto 3 (HW) con `KLIMAKONTROL_DEBUG=1`. Se il cloud rifiuta il bind, leggere il messaggio del server e correggere il set di campi qui — è l'unico punto da toccare.
- Questo piano copre **solo il Punto 1**. Il Punto 2 (config SoftAP) richiede prima il disassembly di `apk/split_config.arm64_v8a.apk!libNetworkAPI.so` per ricavare il pacchetto e scriverne il **test dorato**: avrà il suo piano dedicato. Punti 3 (HW) e 4 (wizard app) seguono.
