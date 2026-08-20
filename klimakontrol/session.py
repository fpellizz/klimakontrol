"""Persistenza di sessione e dispositivi.

Il cloud BroadLink blocca temporaneamente chi fa login troppo spesso (errore
-1036), quindi la sessione va riusata. La password non viene mai salvata.
"""

from __future__ import annotations

import json
import os
from typing import Any, Dict, List, Optional

from .cloud import CloudClient, CloudDevice

DEFAULT_PATH = os.path.join(
    os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"),
    "klimakontrol", "session.json")

_DEVICE_FIELDS = ("did", "pid", "mac", "aeskey", "name", "lanaddr",
                  "devtype", "device_flag", "local_id", "dev_session")


def save(client: CloudClient, devices: List[CloudDevice],
         path: str = DEFAULT_PATH) -> str:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = {
        "region": client.region.code,
        "userid": client.userid,
        "loginsession": client.loginsession,
        "devices": [{f: getattr(d, f) for f in _DEVICE_FIELDS} for d in devices],
    }
    tmp = path + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(data, fh, indent=1)
    os.replace(tmp, path)
    try:
        os.chmod(path, 0o600)   # contiene chiavi AES e token di sessione
    except OSError:
        pass
    return path


def load(path: str = DEFAULT_PATH):
    """Restituisce (client, devices) oppure (None, []) se non c'e' sessione."""
    if not os.path.exists(path):
        return None, []
    with open(path) as fh:
        data = json.load(fh)
    client = CloudClient(data.get("region", "eu"))
    client.restore_session(data.get("userid"), data.get("loginsession"))
    devices = [CloudDevice(**{k: v for k, v in d.items() if k in _DEVICE_FIELDS})
               for d in data.get("devices", [])]
    return client, devices


def mask(obj: Any) -> Any:
    """Copia con i campi sensibili sostituiti dalla loro lunghezza.

    Serve per poter incollare l'output di debug senza esporre chiavi e token.
    """
    secret = {"aeskey", "key", "loginsession", "cookie", "devsession", "dev_session",
              "password", "userid", "did", "mac", "terminalid", "lanaddr", "endpointid"}
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if k.lower() in secret and v:
                out[k] = "<%s: %d caratteri>" % (k, len(str(v)))
            else:
                out[k] = mask(v)
        return out
    if isinstance(obj, list):
        return [mask(x) for x in obj]
    return obj
