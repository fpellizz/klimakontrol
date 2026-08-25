# Onboarding Punto 2 — Config SoftAP — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dare a un modulo vergine (in modalità SoftAP) le credenziali WiFi di casa, costruendo e inviando il pacchetto di setup BroadLink ricostruito da `libNetworkAPI.so`.

**Architecture:** Un nuovo modulo `klimakontrol/provision.py` con due funzioni pure/semplici: `build_softap_packet()` (costruisce i 136 byte, coperto da **test dorato** byte-esatto) e `softap_config()` (invia via UDP a `192.168.10.1:80`, ripetuto). Più un comando CLI `provision`. Riusa `checksum`/`PORT`/`LocalError` da `local.py`. Tutto testato offline (socket sostituito).

**Tech Stack:** Python 3.8+ stdlib soltanto. `unittest`.

**Spec:** `docs/softap-apconfig.md` (pacchetto ricostruito) e `docs/superpowers/specs/2026-08-25-onboarding-provisioning-design.md` (§4, Punto 2).

## Global Constraints

- **Zero dipendenze a runtime**: solo stdlib. Vietato `requests`/`cryptography`/`pycryptodome`.
- **I test non toccano la rete**: mai. Il socket si sostituisce con un fake che cattura `sendto`. `python3 -m unittest discover -s tests -q` deve restare tutto verde.
- **Test dorato immutabile**: `build_softap_packet(b"TestNet", b"secret12", 0)` deve uscire **identico** ai 136 byte documentati (checksum `0xC482`). È l'unica prova che il pacchetto combacia col nativo. Non indebolirlo mai.
- **I segreti non nei log**: la **password WiFi è un segreto** → nel comando CLI si accetta anche via `getpass` (mai forzata su argv), come `bind`/`login`. Non stampare la password.
- **Lingua**: commenti/messaggi in **italiano**; identificatori in **inglese**.
- **Fatti del pacchetto** (da `docs/softap-apconfig.md`): 136 byte; comando `0x14` @`0x26`; SSID @`0x44` (32B); password @`0x64` (32B); `ssid_len` @`0x84`; `password_len` @`0x85`; `security` @`0x86`; checksum seed `0xBEAF` @`0x20` (LE) con `[0x20:0x22]` a zero durante il calcolo. Destinazione UDP `192.168.10.1:80`.

---

### Task 1: `build_softap_packet` + test dorato

**Files:**
- Create: `klimakontrol/provision.py`
- Test: `tests/test_provision.py`

**Interfaces:**
- Consumes (da `klimakontrol/local.py`): `checksum(data: bytes, seed: int = 0xBEAF) -> int`.
- Produces: `build_softap_packet(ssid: bytes, password: bytes, security: int = 0) -> bytes` (accetta anche `str`, che codifica in ascii/utf-8).

- [ ] **Step 1: Write the failing test (golden + edge)**

Crea `tests/test_provision.py`:

```python
import unittest
from klimakontrol.provision import build_softap_packet

# Pacchetto dorato ricostruito da libNetworkAPI.so (docs/softap-apconfig.md).
# 136 byte; checksum 0xC482 (LE 82 c4) @0x20; cmd 0x14 @0x26; "TestNet"@0x44; "secret12"@0x64.
GOLDEN = bytes.fromhex(
    "0000000000000000000000000000000000000000000000000000000000000000"
    "82c4000000001400000000000000000000000000000000000000000000000000"
    "00000000546573744e6574000000000000000000000000000000000000000000"
    "0000000073656372657431320000000000000000000000000000000000000000"
    "0000000007080000"
)

class BuildSoftApPacket(unittest.TestCase):
    def test_matches_golden_packet(self):
        pkt = build_softap_packet(b"TestNet", b"secret12", 0)
        self.assertEqual(len(pkt), 0x88)
        self.assertEqual(pkt, GOLDEN)                       # byte per byte

    def test_command_and_field_offsets(self):
        pkt = build_softap_packet(b"MyWifi", b"pw", 3)
        self.assertEqual(pkt[0x26], 0x14)                   # comando ap-config
        self.assertEqual(pkt[0x44:0x4a], b"MyWifi")
        self.assertEqual(pkt[0x64:0x66], b"pw")
        self.assertEqual(pkt[0x84], 6)                      # ssid_len
        self.assertEqual(pkt[0x85], 2)                      # password_len
        self.assertEqual(pkt[0x86], 3)                      # security verbatim

    def test_checksum_is_recomputed(self):
        from klimakontrol.local import checksum
        pkt = build_softap_packet(b"abc", b"defg", 0)
        body = bytearray(pkt)
        body[0x20] = 0; body[0x21] = 0
        c = checksum(bytes(body))
        self.assertEqual(pkt[0x20], c & 0xFF)
        self.assertEqual(pkt[0x21], c >> 8)

    def test_fields_truncate_at_32(self):
        pkt = build_softap_packet(b"S" * 40, b"P" * 40, 0)
        self.assertEqual(pkt[0x84], 0x20)                   # len capped a 32
        self.assertEqual(pkt[0x85], 0x20)
        self.assertEqual(pkt[0x44:0x64], b"S" * 32)         # non sfora nel campo password
        self.assertEqual(len(pkt), 0x88)

    def test_accepts_str(self):
        self.assertEqual(build_softap_packet("TestNet", "secret12", 0),
                         build_softap_packet(b"TestNet", b"secret12", 0))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest tests.test_provision -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'klimakontrol.provision'`.

- [ ] **Step 3: Write minimal implementation**

Crea `klimakontrol/provision.py`:

```python
"""Config SoftAP di un modulo vergine: pacchetto di setup BroadLink.

Ricostruito da libNetworkAPI.so (vedi docs/softap-apconfig.md): 136 byte, in chiaro,
comando 0x14, checksum seed 0xBEAF, inviato in UDP a 192.168.10.1:80.
"""
from __future__ import annotations

from .local import checksum, LocalError

#: Gateway della SoftAP del modulo e porta (hard-coded nel nativo).
SOFTAP_GATEWAY = "192.168.10.1"
SOFTAP_PORT = 80

#: Comando "ap-config" nell'header (cfr. 0x6a controllo, 0x65 auth, 0x06 discovery).
CMD_APCONFIG = 0x14

_FIELD = 0x20  # ogni campo (ssid/password) e' lungo 32 byte


def _as_bytes(s) -> bytes:
    return s if isinstance(s, bytes) else str(s).encode("utf-8")


def build_softap_packet(ssid, password, security: int = 0) -> bytes:
    """Costruisce il pacchetto SoftAP di setup (136 byte). Vedi docs/softap-apconfig.md."""
    ssid_b = _as_bytes(ssid)[:_FIELD]
    pw_b = _as_bytes(password)[:_FIELD]
    pkt = bytearray(0x88)
    pkt[0x26] = CMD_APCONFIG
    pkt[0x44:0x44 + len(ssid_b)] = ssid_b
    pkt[0x64:0x64 + len(pw_b)] = pw_b
    pkt[0x84] = len(ssid_b)
    pkt[0x85] = len(pw_b)
    pkt[0x86] = security & 0xFF
    c = checksum(bytes(pkt))               # [0x20:0x22] sono ancora zero
    pkt[0x20] = c & 0xFF
    pkt[0x21] = (c >> 8) & 0xFF
    return bytes(pkt)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m unittest tests.test_provision -q`
Expected: PASS (5 test verdi).

- [ ] **Step 5: Run the full suite**

Run: `python3 -m unittest discover -s tests -q`
Expected: tutti verdi.

- [ ] **Step 6: Commit**

```bash
git add klimakontrol/provision.py tests/test_provision.py
git commit -m "feat(provision): build_softap_packet — pacchetto setup SoftAP (golden test)

Ricostruito da libNetworkAPI.so (docs/softap-apconfig.md): 136 byte in chiaro,
comando 0x14, checksum 0xBEAF. Test dorato byte-esatto (TestNet/secret12 -> 0xC482)."
```

---

### Task 2: `softap_config` — invio UDP

**Files:**
- Modify: `klimakontrol/provision.py`
- Test: `tests/test_provision.py`

**Interfaces:**
- Consumes: `build_softap_packet` (Task 1); `SOFTAP_GATEWAY`, `SOFTAP_PORT`.
- Produces: `softap_config(ssid, password, security=0, gateway=SOFTAP_GATEWAY, port=SOFTAP_PORT, tries=3, recv_timeout=2.0) -> Optional[bytes]` — invia il pacchetto `tries` volte via UDP al gateway; prova a leggere una risposta breve; ritorna i byte grezzi della risposta o `None`.

- [ ] **Step 1: Write the failing test**

Aggiungi a `tests/test_provision.py`:

```python
class SoftApConfig(unittest.TestCase):
    def test_sends_golden_packet_to_gateway(self):
        import klimakontrol.provision as prov

        sent = []

        class FakeSock:
            def __init__(self, *a, **k): pass
            def __enter__(self): return self
            def __exit__(self, *a): return False
            def setsockopt(self, *a): pass
            def settimeout(self, *a): pass
            def sendto(self, data, dest): sent.append((data, dest))
            def recvfrom(self, n): raise prov.socket.timeout()

        orig = prov.socket.socket
        prov.socket.socket = lambda *a, **k: FakeSock()
        try:
            resp = prov.softap_config("TestNet", "secret12", 0, tries=3)
        finally:
            prov.socket.socket = orig

        self.assertIsNone(resp)                              # nessuna risposta -> None
        self.assertEqual(len(sent), 3)                       # inviato tries volte
        data, dest = sent[0]
        self.assertEqual(dest, ("192.168.10.1", 80))
        self.assertEqual(data, prov.build_softap_packet(b"TestNet", b"secret12", 0))

    def test_returns_response_bytes_when_module_replies(self):
        import klimakontrol.provision as prov

        class FakeSock:
            def __init__(self, *a, **k): self.n = 0
            def __enter__(self): return self
            def __exit__(self, *a): return False
            def setsockopt(self, *a): pass
            def settimeout(self, *a): pass
            def sendto(self, data, dest): pass
            def recvfrom(self, n): return (b"\x01\x02ok", ("192.168.10.1", 80))

        orig = prov.socket.socket
        prov.socket.socket = lambda *a, **k: FakeSock()
        try:
            resp = prov.softap_config("W", "P", 0, tries=1)
        finally:
            prov.socket.socket = orig
        self.assertEqual(resp, b"\x01\x02ok")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest tests.test_provision -q`
Expected: FAIL — `AttributeError: module 'klimakontrol.provision' has no attribute 'softap_config'`.

- [ ] **Step 3: Write minimal implementation**

Aggiungi in `klimakontrol/provision.py` (e l'import `socket`/`time`/`Optional` in cima):

```python
import socket
import time
from typing import Optional
```

```python
def softap_config(ssid, password, security: int = 0,
                  gateway: str = SOFTAP_GATEWAY, port: int = SOFTAP_PORT,
                  tries: int = 3, recv_timeout: float = 2.0) -> Optional[bytes]:
    """Manda al modulo (in SoftAP) le credenziali WiFi. Ritorna la risposta grezza o None.

    Prerequisito: il telefono/PC deve essere connesso all'hotspot del modulo
    (SSID `Broadlink_tcl_...`), così `192.168.10.1` è raggiungibile.
    """
    pkt = build_softap_packet(ssid, password, security)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(recv_timeout)
        for _ in range(max(1, tries)):
            sock.sendto(pkt, (gateway, port))
            time.sleep(0.2)                # come il nativo (sendto ripetuti con usleep)
        try:
            data, _ = sock.recvfrom(2048)
            return data
        except socket.timeout:
            return None
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m unittest tests.test_provision -q`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `python3 -m unittest discover -s tests -q`
Expected: tutti verdi.

- [ ] **Step 6: Commit**

```bash
git add klimakontrol/provision.py tests/test_provision.py
git commit -m "feat(provision): softap_config — invia le credenziali WiFi al modulo (UDP 192.168.10.1:80)

Invio ripetuto come il nativo; ritorna la risposta grezza (forma da confermare su HW)."
```

---

### Task 3: comando CLI `provision`

**Files:**
- Modify: `klimakontrol/cli.py`
- Test: `tests/test_cli.py`

**Interfaces:**
- Consumes: `provision.softap_config`; `getpass`; `session.mask` (non serve qui, ma non stampare la password).
- Produces: subcomando `klimakontrol provision --ssid … [--password …] [--security open|wep|wpa1|wpa2|wpa12|<int>]`.

- [ ] **Step 1: Write the failing test**

Aggiungi a `tests/test_cli.py`:

```python
class ProvisionCommand(unittest.TestCase):
    def test_cmd_provision_calls_softap_config(self):
        import types, contextlib, io
        from klimakontrol import cli
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
        import types, contextlib, io
        from klimakontrol import cli
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest tests.test_cli -q`
Expected: FAIL — `AttributeError: module 'klimakontrol.cli' has no attribute 'cmd_provision'`.

- [ ] **Step 3: Write minimal implementation**

In `klimakontrol/cli.py` aggiungi l'import del modulo (`from . import provision` o `from .provision import softap_config`) e la mappa sicurezza + il comando:

```python
_SECURITY = {"open": 0, "wep": 1, "wpa1": 2, "wpa2": 3, "wpa12": 4}


def cmd_provision(args) -> None:
    from . import provision
    sec = args.security
    security = _SECURITY.get(str(sec).lower(), None)
    if security is None:
        try:
            security = int(sec)
        except (TypeError, ValueError):
            sys.exit("Sicurezza sconosciuta: %r (usa open|wep|wpa1|wpa2|wpa12 o un intero)" % sec)
    password = args.password or getpass.getpass("Password del WiFi di casa: ")
    print("Invio credenziali al modulo in SoftAP (%s)..." % args.ssid)
    resp = provision.softap_config(args.ssid, password, security)
    if resp is None:
        print("Inviato. Nessuna conferma dal modulo (normale): ora dovrebbe connettersi al WiFi.")
    else:
        print("Inviato. Il modulo ha risposto (%d byte)." % len(resp))
    print("Poi: riconnetti il telefono al WiFi di casa e lancia `klimakontrol discover`.")
```

E registra il subcomando (dopo `bind`, ~riga 273):

```python
    sp = sub.add_parser("provision", help="configura un modulo vergine in SoftAP (credenziali WiFi)")
    sp.add_argument("--ssid", required=True, help="SSID del WiFi di casa")
    sp.add_argument("--password", help="password del WiFi (se omessa, viene chiesta)")
    sp.add_argument("--security", default="wpa2",
                    help="open|wep|wpa1|wpa2|wpa12 o un intero (default wpa2)")
    sp.set_defaults(func=cmd_provision)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m unittest tests.test_cli -q`
Expected: PASS.

- [ ] **Step 5: Run the full suite + smoke**

Run: `python3 -m unittest discover -s tests -q` → tutti verdi.
Run: `python3 -m klimakontrol provision --help` → mostra le opzioni.

- [ ] **Step 6: Commit**

```bash
git add klimakontrol/cli.py tests/test_cli.py
git commit -m "feat(cli): comando provision — config SoftAP di un modulo vergine

Manda le credenziali WiFi (password via getpass, mai su argv). Chiude il Punto 2:
il modulo entra in rete; poi discover+bind. Conferma HW al Punto 3."
```

---

## Note per l'esecutore

- Il **test dorato** (Task 1) è la prova che il pacchetto combacia col nativo. Se si rompe, hai
  cambiato il pacchetto, non il test.
- La destinazione `192.168.10.1:80` e il comando `0x14` vengono da `docs/softap-apconfig.md`
  (disassembly). La **conferma end-to-end** (il modulo entra davvero in rete) è HW → Punto 3.
- Fuori portata qui: ottenere `did/pid/mac/devkey` dopo la config (si fa con `discover`+auth di
  `local.py` o via cloud) e le varianti cifrate `protocol=1/2` (muro `tfb`).
