"""klimakontrol - controllo dei climatizzatori TCL/Wisnow con moduli BroadLink DNA.

Due trasporti, un solo modello di dati:

* `local`  - UDP porta 80 sulla rete di casa: risposta in millisecondi, nessun cloud
* `cloud`  - HTTPS verso BroadLink app-service: funziona da qualunque rete

Le specifiche del protocollo, ricostruite dall'app ufficiale, sono in docs/protocol.md.
"""

__version__ = "0.1.0"

from .aes import decrypt_cbc, encrypt_cbc                      # noqa: F401
from .cloud import CloudClient, CloudDevice, REGIONS            # noqa: F401
from .local import Device, LocalClient, discover                # noqa: F401
from .params import PARAMS, decode_status, encode_changes       # noqa: F401
from .tasks import Task                                         # noqa: F401

__all__ = [
    "encrypt_cbc", "decrypt_cbc",
    "CloudClient", "CloudDevice", "REGIONS",
    "LocalClient", "Device", "discover",
    "PARAMS", "decode_status", "encode_changes",
    "Task",
]
