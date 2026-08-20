"""Cloud BroadLink app-service: login, dispositivi, controllo remoto, consumi.

E' la via che funziona da fuori casa. Il cloud fa da postino: il client gli
passa la chiave AES del dispositivo a ogni comando (campo `cookie`), e lui
inoltra. Ricostruito da `cn.com.broadlink.sdk` nel dex dell'app.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from .aes import encrypt_cbc

# --------------------------------------------------------------------------
# I tre "sali" dell'autenticazione.
#
# L'app non li tiene in Java: li chiede a tre funzioni native di
# libBLAccountEncryptAPI.so
#
#     blAccountPasswordEncrypt()   -> sale della password
#     blAccountTokenEncrypt()      -> sale della chiave AES del corpo
#     blAccountBodyEncrypt()       -> sale della firma del corpo
#
# Solo BODY_SALT e' verificato: la stringa e' presente nel dex, usata anche
# dalle chiamate /ec4 e dataservice. Gli altri due sono i valori che circolano
# nei progetti open source, e NON compaiono in questo APK: vengono da un altro
# build dell'SDK e potrebbero non valere piu'. Con un sale sbagliato il cloud
# risponde -1008, cioe' "credenziali errate" anche a credenziali giuste.
#
# Si possono sostituire senza toccare il codice:
#     export KLIMAKONTROL_SALT_PASSWORD='...'
#     export KLIMAKONTROL_SALT_TOKEN='...'
#     export KLIMAKONTROL_SALT_BODY='...'
# --------------------------------------------------------------------------

DEFAULT_SALTS = {
    "body": "xgx3d*fe3478$ukx",      # verificato: presente nel dex
    "token": "kdixkdqp54545^#*",     # da verificare: assente da questo APK
    "password": "4969fj#k23#",       # da verificare: assente da questo APK
}


def salt(kind: str) -> str:
    """Il sale in uso, con precedenza all'ambiente (letto a ogni chiamata)."""
    try:
        default = DEFAULT_SALTS[kind]
    except KeyError:
        raise CloudError("sale sconosciuto: %s" % kind)
    return os.environ.get("KLIMAKONTROL_SALT_" + kind.upper(), default)
REQUEST_IV = bytes.fromhex("eaaaaa3abb5862a21918b5771d1615aa")
APP_VERSION = os.environ.get("KLIMAKONTROL_APP_VERSION", "1.0.12")
TIMEOUT = 30.0

#: con KLIMAKONTROL_DEBUG=1 stampa su stderr richieste e risposte grezze.
#: Serve quando il codice di errore da solo non basta a capire cosa rifiuta il server.
DEBUG = os.environ.get("KLIMAKONTROL_DEBUG", "") not in ("", "0", "no")


#: Licenze BroadLink estratte da `com.tcl.smartdevice.AirApplication.initData`.
#: Il blob e' in chiaro: i primi 16 byte sono il licenseId, i 16 successivi il
#: companyid. Derivarli da qui invece di ricopiarli a mano evita l'errore che ci
#: e' costato un pomeriggio: il valore che gira nei progetti della community come
#: company id della regione internazionale (8503b08f...) e' in realta' una
#: costante presente in tutte e quattro le licenze, non il companyid di nessuna.
LICENSE_BLOBS = {
    "ab": ("Internazionale / altro",
           "9uniFWbhCaKHl6ulodjtfqhFKo9IrnB+3BLpxS4h8A8fL+VJIdyF+2ILFTC0PblZKVhhWwAAAABO"
           "Pz4Zb6oe0ZlL1zmKVmrY2G6JnyY5iP/MgbRVK4EGBNngbjBXIrjugvbdRX/Eo+jEFLPwoaW2G+W/"
           "0h0q6kkGhQOwj6V3Kd+fqkXkyXiFLAAAAAA="),
    "cn": ("Cina",
           "v/1NcC7FOTjDHrEMwBlLSrhnHVwBG6ur22sGiccKtlb88NLmRXYBBuBZzN0ftmg9g7rzWwAAAADX"
           "CGl+jTb8dv8MuUV6Oe6Q0Qs3MVkr1CxkTbc9eCF9VA9IeSycC7T7L5/gZZyMbk6ZhKXe0Lj49+Xj"
           "jTYOBlsChQOwj6V3Kd+fqkXkyXiFLAAAAAA="),
    "eu": ("Europa",
           "quchhDaeL8Pm3tU6kGElhlfJ5a28nhGDclOc2PJuEjlrVu1aWTUymLgy0lNthB1irlhhWwAAAADs"
           "3b/pU8KzE42deOVVPI47q3AXOQdWLiiZnytJBYDqZMJe9bUxlnu2yrqpGqCWdsTLCQ2S8+ps6iui"
           "X3T5hoYJhQOwj6V3Kd+fqkXkyXiFLAAAAAA="),
    "ru": ("Russia",
           "5g3odWUWbER6kM7pbalV91ZHeU3ti7xn32X/K9fQ+wPgkGNrwku8t1BjKi6Z/SE0RFhhWwAAAABJ"
           "2/PWDRNYtxiTMJolraau62+ditV4GpJUTtEwYccq/2ROZvfPaUyM4m7LY/ZRSRJs8mLdI6W/5YpX"
           "AZyAtsoAhQOwj6V3Kd+fqkXkyXiFLAAAAAA="),
}

#: nomi alternativi accettati dalla CLI
REGION_ALIASES = {"us": "ab", "other": "ab", "altro": "ab", "world": "ab", "eu": "eu"}

#: ordine in cui provare le regioni quando l'utente non sa quale ha scelto
REGION_TRY_ORDER = ("eu", "ab", "ru", "cn")


@dataclass(frozen=True)
class Region:
    code: str
    label: str
    license_id: str
    company_id: str
    license_blob: str = ""

    @property
    def base_url(self) -> str:
        return "https://%sappservice.ibroadlink.com" % self.license_id


def _region_from_license(code: str, label: str, blob_b64: str) -> Region:
    raw = base64.b64decode(blob_b64)
    if len(raw) < 32:
        raise ValueError("licenza troppo corta per la regione %s" % code)
    return Region(code=code, label=label,
                  license_id=raw[0:16].hex(),
                  company_id=raw[16:32].hex(),
                  license_blob=blob_b64)


REGIONS: Dict[str, Region] = {
    code: _region_from_license(code, label, blob)
    for code, (label, blob) in LICENSE_BLOBS.items()
}


def resolve_region(name: str) -> str:
    """Accetta il codice dell'app o un alias comune ("us" -> "ab")."""
    name = (name or "").strip().lower()
    name = REGION_ALIASES.get(name, name)
    if name not in REGIONS:
        raise CloudError("regione sconosciuta: %r (usa %s)"
                         % (name, ", ".join(sorted(REGIONS))))
    return name


#: report disponibili per lo storico consumi
ENERGY_REPORTS = {
    "hour": "fw_tcldaystatus_v1",     # granularita' oraria, finestra giornaliera
    "day": "fw_tclmonthstatus_v1",    # granularita' giornaliera, finestra mensile
    "month": "fw_tclyearstatus_v1",   # granularita' mensile, finestra annuale
}

#: il firmware e i report ragionano in UTC+8
DEVICE_TZ_OFFSET = 8 * 3600


class CloudError(Exception):
    pass


class AuthError(CloudError):
    pass


class RateLimitError(CloudError):
    pass


@dataclass
class CloudDevice:
    did: str
    pid: str
    mac: str
    aeskey: str
    name: str = ""
    lanaddr: str = ""
    devtype: int = 0
    device_flag: int = 0
    local_id: int = 1
    dev_session: str = ""
    raw: Dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_cloud(cls, d: Dict[str, Any], name: str = "") -> "CloudDevice":
        return cls(
            did=str(d.get("did") or ""),
            pid=str(d.get("pid") or "").lower(),
            mac=str(d.get("mac") or d.get("wifimac") or ""),
            aeskey=str(d.get("aeskey") or "").lower(),
            name=name or str(d.get("name") or ""),
            lanaddr=str(d.get("lanaddr") or d.get("host") or ""),
            devtype=int(d.get("devtype") or d.get("type") or 0),
            device_flag=int(d.get("devicetypeflag") or d.get("devicetypeFlag") or 0),
            local_id=int(d.get("id") or 1),
            raw=d,
        )


def _md5(s: str) -> str:
    return hashlib.md5(s.encode()).hexdigest()


def _sha1(s: str) -> str:
    return hashlib.sha1(s.encode()).hexdigest()


def _jd(obj: Any) -> str:
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False)


class CloudClient:
    """Client sincrono del cloud BroadLink."""

    def __init__(self, region: str = "eu", verify_tls: bool = True):
        self.region = REGIONS[resolve_region(region)]
        self.base = self.region.base_url
        self.userid: Optional[str] = None
        self.loginsession: Optional[str] = None
        self._fkey: Optional[str] = None
        self._fts: Optional[int] = None
        self._ctx = ssl.create_default_context()
        if not verify_tls:
            self._ctx.check_hostname = False
            self._ctx.verify_mode = ssl.CERT_NONE

    # ------------------------------------------------------------ trasporto

    def _common_headers(self, extra: Optional[Dict[str, str]] = None) -> Dict[str, str]:
        now_ms = int(time.time() * 1000)
        h = {
            "system": "android",
            "appPlatform": "android",
            "language": "it-it",
            "timestamp": str(now_ms // 1000),
            "appVersion": APP_VERSION,
            "messageId": str(now_ms),
            "Content-type": "application/x-java-serialized-object",
        }
        if extra:
            h.update(extra)
        return h

    def _request(self, url: str, headers: Dict[str, str],
                 body: Optional[bytes] = None) -> Dict[str, Any]:
        if not url.startswith("http"):
            url = self.base + url
        req = urllib.request.Request(url, data=body, headers=self._common_headers(headers),
                                     method="POST" if body is not None else "GET")
        if DEBUG:
            print("[klimakontrol] POST %s" % url, file=sys.stderr)
            for key, value in sorted(self._common_headers(headers).items()):
                shown = value if key not in ("loginsession", "token") else "<%d caratteri>" % len(value)
                print("[klimakontrol]   %s: %s" % (key, shown), file=sys.stderr)
            if body is not None:
                print("[klimakontrol]   corpo cifrato: %d byte" % len(body), file=sys.stderr)
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT, context=self._ctx) as resp:
                raw = resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:300]
            raise CloudError("HTTP %s da %s: %s" % (exc.code, url, detail)) from exc
        except urllib.error.URLError as exc:
            raise CloudError("impossibile raggiungere %s: %s" % (url, exc.reason)) from exc
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise CloudError("risposta non JSON da %s: %r" % (url, raw[:200])) from exc
        if not isinstance(data, dict):
            raise CloudError("risposta inattesa da %s" % url)
        if DEBUG:
            print("[klimakontrol] risposta grezza: %s" % raw[:1000], file=sys.stderr)
        return data

    @staticmethod
    def _ensure_ok(resp: Dict[str, Any], what: str) -> Dict[str, Any]:
        code = resp.get("error", resp.get("status", 0))
        if code in (0, "0", None):
            return resp
        msg = resp.get("msg") or resp.get("message") or ""
        # il messaggio del server distingue casi che il solo codice confonde
        # (utente inesistente in questo scope, password errata, account bloccato):
        # non va mai scartato.
        detail = " - %s" % msg if msg else ""
        extra = {k: v for k, v in resp.items()
                 if k not in ("error", "status", "msg", "message")}
        if extra:
            detail += " | altri campi: %s" % _jd(extra)[:300]
        if str(code) == "-1008":
            raise AuthError("%s: il cloud rifiuta le credenziali (%s)%s" % (what, code, detail))
        if str(code) == "-1036":
            raise RateLimitError("%s: troppi tentativi, il cloud ha messo in pausa i login (%s)%s"
                                 % (what, code, detail))
        raise CloudError("%s: errore %s%s" % (what, code, detail))

    # ------------------------------------------------------------ account

    def login(self, username: str, password: str) -> None:
        username = username.strip()
        ident = "phone" if username.isdigit() else "email"
        body = {
            ident: username,
            "password": _sha1(password + salt("password")),
            "companyid": self.region.company_id,
            "lid": self.region.license_id,
        }
        bj = _jd(body)
        ts = str(int(time.time()))
        key = bytes.fromhex(_md5(ts + salt("token")))
        headers = {
            "timestamp": ts,
            "token": _md5(bj + salt("body")),
            "lid": self.region.license_id,
            "licenseId": self.region.license_id,
            ident: username,
        }
        resp = self._ensure_ok(
            self._request("/account/login", headers, encrypt_cbc(bj.encode(), key, REQUEST_IV)),
            "login")
        self.userid = resp.get("userid")
        self.loginsession = resp.get("loginsession")
        if not (self.userid and self.loginsession):
            raise CloudError("il login non ha restituito una sessione utilizzabile")

    def restore_session(self, userid: str, loginsession: str) -> None:
        """Riusa una sessione salvata: il cloud limita i login ravvicinati."""
        self.userid, self.loginsession = userid, loginsession

    @property
    def logged_in(self) -> bool:
        return bool(self.userid and self.loginsession)

    def _require_session(self) -> None:
        if not self.logged_in:
            raise CloudError("sessione assente: fai prima il login")

    # ------------------------------------------------------------ /ec4 firmate

    def _refresh_family_key(self) -> None:
        resp = self._ensure_ok(self._request("/ec4/v1/common/api", {}), "chiave famiglia")
        self._fkey, self._fts = str(resp["key"]), int(resp["timestamp"])

    def _ec4(self, path: str, body: Dict[str, Any]) -> Dict[str, Any]:
        self._require_session()
        if not self._fkey:
            self._refresh_family_key()
        bj = _jd(body)
        ts = str(self._fts)
        headers = {
            "timestamp": ts,
            "token": _md5(bj + salt("body") + ts + self.userid),
            "userid": self.userid,
            "loginsession": self.loginsession,
            "licenseid": self.region.license_id,
            "lid": self.region.license_id,
        }
        enc = encrypt_cbc(bj.encode(), bytes.fromhex(self._fkey), REQUEST_IV)
        return self._ensure_ok(self._request(path, headers, enc), path)

    def family_ids(self) -> List[str]:
        resp = self._ec4("/ec4/v1/user/getfamilyid", {"userid": self.userid})
        return [str(f["id"]) for f in (resp.get("familyinfo") or []) if f.get("id")]

    def devices(self) -> List[CloudDevice]:
        """Elenco dispositivi con chiave AES, indirizzo LAN e nome amichevole."""
        fams = self.family_ids()
        if not fams:
            return []
        resp = self._ec4("/ec4/v1/family/getallinfo",
                         {"userid": self.userid, "familyid": fams})
        out: List[CloudDevice] = []
        seen = set()
        for fam in resp.get("familyallinfo") or []:
            names = {}
            for mod in fam.get("moduleinfo") or []:
                label = (mod.get("name") or "").strip()
                for md in mod.get("moduledev") or []:
                    if md.get("did") and label:
                        names.setdefault(md["did"], label)
            for field_name in ("devinfo", "subdevinfo"):
                for raw in fam.get(field_name) or []:
                    if not raw.get("aeskey") or not raw.get("did"):
                        continue
                    if raw["did"] in seen:
                        continue
                    seen.add(raw["did"])
                    out.append(CloudDevice.from_cloud(raw, names.get(raw["did"], "")))
        return out

    # ------------------------------------------------------------ controllo remoto

    def _control_headers(self, with_company: bool = False) -> Dict[str, str]:
        self._require_session()
        h = {
            "userid": self.userid,
            "loginsession": self.loginsession,
            "licenseid": self.region.license_id,
            "lid": self.region.license_id,
        }
        if with_company:
            h["companyid"] = self.region.company_id
        return h

    @staticmethod
    def build_cookie(dev: CloudDevice) -> str:
        """Il `cookie` che porta al cloud la chiave AES del dispositivo."""
        inner = {"device": {
            "id": dev.local_id,
            "key": dev.aeskey,
            "aeskey": dev.aeskey,
            "did": dev.did,
            "pid": dev.pid,
            "mac": dev.mac,
        }}
        return base64.b64encode(_jd(inner).encode()).decode()

    def build_directive(self, dev: CloudDevice, payload: Dict[str, Any],
                        raw_passthrough: bool = False,
                        now: Optional[int] = None) -> Dict[str, Any]:
        ts = int(now if now is not None else time.time())
        namespace, name = (("DNA.TransmissionControl", "commonControl") if raw_passthrough
                           else ("DNA.KeyValueControl", "KeyValueControl"))
        endpoint: Dict[str, Any] = {
            "devicePairedInfo": {
                "did": dev.did,
                "pid": dev.pid,
                "mac": dev.mac,
                "devicetypeflag": dev.device_flag,
                "cookie": self.build_cookie(dev),
            },
            "endpointId": dev.did,
            "cookie": {},
        }
        if dev.dev_session and len(dev.dev_session) >= 112:
            endpoint["devSession"] = dev.dev_session
        return {"directive": {
            "header": {
                "namespace": namespace,
                "name": name,
                "interfaceVersion": "2",
                "messageId": "%s-%d" % (dev.did, ts),
                "timstamp": str(ts),   # sic: il typo e' nell'SDK originale
            },
            "endpoint": endpoint,
            "payload": payload,
        }}

    def sdk_control(self, dev: CloudDevice, payload: Dict[str, Any]) -> Dict[str, Any]:
        """Inoltra un comando al climatizzatore passando dal cloud."""
        directive = self.build_directive(dev, payload)
        url = "%s/device/control/v2/sdkcontrol?license=%s" % (self.base, self.region.license_id)
        resp = self._request(url, self._control_headers(), _jd(directive).encode())
        event = resp.get("event")
        if not event:
            raise CloudError("controllo remoto fallito: %s (%s)"
                             % (resp.get("msg", "errore sconosciuto"), resp.get("status")))
        endpoint = event.get("endpoint") or {}
        session = endpoint.get("devSession")
        if session and session != dev.dev_session:
            dev.dev_session = session      # il cloud ruota la sessione del dispositivo
        return event.get("payload") or {}

    def get_state(self, dev: CloudDevice, names: Optional[List[str]] = None) -> Dict[str, Any]:
        from .local import flatten_params_vals
        from .params import READ_SET
        payload = {"act": "get", "params": list(names or READ_SET), "vals": []}
        out = self.sdk_control(dev, payload)
        data = out.get("data", out)
        return flatten_params_vals(data) if isinstance(data, dict) else {}

    def set_state(self, dev: CloudDevice, changes: Dict[str, Any]) -> Dict[str, Any]:
        from .local import build_params_vals, flatten_params_vals
        payload = dict(act="set", **build_params_vals(changes))
        out = self.sdk_control(dev, payload)
        data = out.get("data", out)
        return flatten_params_vals(data) if isinstance(data, dict) else {}

    def query_state(self, devices: List[CloudDevice],
                    now: Optional[int] = None) -> Dict[str, Any]:
        """Stato online di tutte le unita' in una sola chiamata."""
        self._require_session()
        ts = int(now if now is not None else time.time())
        studata = []
        for d in devices:
            entry: Dict[str, Any] = {"did": d.did, "devtype": d.devtype}
            if d.dev_session:
                entry["devSession"] = d.dev_session
            studata.append(entry)
        directive = {"directive": {
            "header": {
                "namespace": "DNA.QueryState",
                "name": "queryState",
                "interfaceVersion": "2",
                "messageType": "controlgw.batch",
                "senderId": "sdk",
                "messageId": "%s-%d" % (self.userid, ts),
                "timstamp": str(ts),
            },
            "payload": {"msgtype": "batch", "studata": studata},
        }}
        url = "%s/device/control/v2/querystate" % self.base
        return self._request(url, self._control_headers(True), _jd(directive).encode())

    # ------------------------------------------------------------ storico

    @staticmethod
    def _fmt_device_time(epoch: float) -> str:
        return time.strftime("%Y-%m-%d_%H:%M:%S", time.gmtime(epoch + DEVICE_TZ_OFFSET))

    def energy(self, dev: CloudDevice, granularity: str = "hour",
               start: Optional[float] = None, end: Optional[float] = None) -> Dict[str, Any]:
        """Storico consumi. `granularity`: hour | day | month.

        Gli orari vanno passati in UTC+8, come il firmware.
        """
        if granularity not in ENERGY_REPORTS:
            raise CloudError("granularita' non valida: %s (usa %s)"
                             % (granularity, ", ".join(ENERGY_REPORTS)))
        span = {"hour": 86400, "day": 31 * 86400, "month": 366 * 86400}[granularity]
        end = time.time() if end is None else end
        start = end - span if start is None else start
        body = {"report": ENERGY_REPORTS[granularity], "device": [{
            "did": dev.did,
            "start": self._fmt_device_time(start),
            "end": self._fmt_device_time(end),
            "sortk": "-occurtime",
            "params": [],
        }]}
        url = "%s/dataservice/v1/device/stats" % self.base
        return self._request(url, self._control_headers(), _jd(body).encode())

    def status_history(self, dev: CloudDevice, start: Optional[float] = None,
                       end: Optional[float] = None) -> Dict[str, Any]:
        """Storico di stato: temperature e accensioni nel tempo."""
        end = time.time() if end is None else end
        start = end - 7 * 86400 if start is None else start
        body = {"device": [{
            "did": dev.did,
            "start": self._fmt_device_time(start),
            "end": self._fmt_device_time(end),
            "sortk": "-occurtime",
            "params": [],
        }]}
        url = "%s/dataservice/v1/device/status" % self.base
        return self._request(url, self._control_headers(), _jd(body).encode())


def login_any_region(username: str, password: str,
                     regions: Optional[List[str]] = None,
                     verify_tls: bool = True) -> CloudClient:
    """Prova le regioni una dopo l'altra e restituisce il client che ha funzionato.

    L'app chiede la regione al primo avvio e poi non la mostra piu': chi l'ha
    scelta due anni fa non ha modo di ricordarsela. Invece di far indovinare,
    proviamo. Al primo rate limit ci fermiamo: insistere peggiora la situazione.
    """
    codes = [resolve_region(r) for r in (regions or REGION_TRY_ORDER)]
    errors = []
    for code in codes:
        client = CloudClient(code, verify_tls=verify_tls)
        try:
            client.login(username, password)
            return client
        except RateLimitError:
            raise
        except AuthError as exc:
            errors.append("%s (%s): %s" % (REGIONS[code].label, code, exc))
        except CloudError as exc:
            errors.append("%s (%s): %s" % (REGIONS[code].label, code, exc))
    raise AuthError("nessuna regione ha accettato le credenziali:\n  - "
                    + "\n  - ".join(errors))
