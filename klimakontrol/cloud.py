"""Cloud BroadLink app-service: login, dispositivi, controllo remoto, consumi.

E' la via che funziona da fuori casa. Il cloud fa da postino: il client gli
passa la chiave AES del dispositivo a ogni comando (campo `cookie`), e lui
inoltra. Ricostruito da `cn.com.broadlink.sdk` nel dex dell'app.
"""

from __future__ import annotations

import base64
import hashlib
import json
import ssl
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from .aes import encrypt_cbc

BODY_SALT = "xgx3d*fe3478$ukx"
TOKEN_SALT = "kdixkdqp54545^#*"
PASSWORD_SALT = "4969fj#k23#"
REQUEST_IV = bytes.fromhex("eaaaaa3abb5862a21918b5771d1615aa")
APP_VERSION = "1.0.12"
TIMEOUT = 30.0


@dataclass(frozen=True)
class Region:
    code: str
    label: str
    license_id: str
    company_id: str

    @property
    def base_url(self) -> str:
        return "https://%sappservice.ibroadlink.com" % self.license_id


REGIONS: Dict[str, Region] = {r.code: r for r in (
    Region("eu", "Europa", "aae72184369e2fc3e6ded53a90612586", "57c9e5adbc9e118372539cd8f26e1239"),
    Region("us", "USA / altro", "f6e9e21566e109a28797aba5a1d8ed7e", "8503b08fa57729df9faa45e4c978852c"),
    Region("cn", "Cina", "bffd4d702ec53938c31eb10cc0194b4a", "b8671d5c011bababdb6b0689c70ab656"),
    Region("ru", "Russia", "e60de87565166c447a90cee96da955f7", "5647794ded8bbc67df65ff2bd7d0fb03"),
)}

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
        if region not in REGIONS:
            raise CloudError("regione sconosciuta: %s (usa %s)" % (region, ", ".join(REGIONS)))
        self.region = REGIONS[region]
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
        return data

    @staticmethod
    def _ensure_ok(resp: Dict[str, Any], what: str) -> Dict[str, Any]:
        code = resp.get("error", resp.get("status", 0))
        if code in (0, "0", None):
            return resp
        msg = resp.get("msg") or resp.get("message") or "errore sconosciuto"
        if str(code) == "-1008":
            raise AuthError("%s: credenziali errate" % what)
        if str(code) == "-1036":
            raise RateLimitError("%s: troppi tentativi, il cloud ha messo in pausa i login" % what)
        raise CloudError("%s: %s (%s)" % (what, msg, code))

    # ------------------------------------------------------------ account

    def login(self, username: str, password: str) -> None:
        username = username.strip()
        ident = "phone" if username.isdigit() else "email"
        body = {
            ident: username,
            "password": _sha1(password + PASSWORD_SALT),
            "companyid": self.region.company_id,
            "lid": self.region.license_id,
        }
        bj = _jd(body)
        ts = str(int(time.time()))
        key = bytes.fromhex(_md5(ts + TOKEN_SALT))
        headers = {
            "timestamp": ts,
            "token": _md5(bj + BODY_SALT),
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
            "token": _md5(bj + BODY_SALT + ts + self.userid),
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
