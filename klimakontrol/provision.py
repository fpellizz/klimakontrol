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
