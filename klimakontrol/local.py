"""Protocollo locale BroadLink/DNA su UDP porta 80.

E' la via veloce: quando il telefono (o il server) e' sulla stessa rete del
climatizzatore, il comando fa un solo salto e torna in pochi millisecondi.
Nessun cloud, nessun login, funziona anche con internet giu'.

Struttura del pacchetto esterno:

    0x00  8  magic 5aa5aa555aa5aa55
    0x20  2  checksum del pacchetto (LE)
    0x22  2  codice di errore nelle risposte
    0x24  2  device type (LE)
    0x26  2  comando: 0x006a controllo, 0x0065 auth, 0x0006 discovery
    0x28  2  nonce, ricopiato nella risposta
    0x2a  6  MAC del dispositivo, invertito
    0x30  4  device id (LE)
    0x34  2  checksum del payload in chiaro
    0x38  n  payload cifrato AES-128-CBC

I checksum partono da 0xbeaf e sommano i byte modulo 0x10000.
"""

from __future__ import annotations

import json
import os
import socket
import struct
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

from .aes import decrypt_cbc, encrypt_cbc

IV = bytes.fromhex("562e17996d093d28ddb3ba695a2e6f58")
INIT_KEY = bytes.fromhex("097628343fe99e23765c1513accf8b02")
MAGIC = bytes.fromhex("5aa5aa555aa5aa55")
INNER_MAGIC = bytes.fromhex("a5a55a5a")

CMD_CONTROL = 0x006A
CMD_AUTH = 0x0065
CMD_DISCOVERY = 0x0006
RESP_CONTROL = 0x03EE

ACT_GET = 1
ACT_SET = 2

DEVTYPE_DEFAULT = 0x507C
KNOWN_DEVTYPES = (0x507A, 0x507B, 0x507C, 0x5108, 0x50D3, 0x50D9, 0x4E2E)

PORT = 80
TIMEOUT = 3.0
SEND_COUNT = 3          # l'app ne manda 3: su UDP non c'e' ritrasmissione
CHECKSUM_SEED = 0xBEAF


class LocalError(Exception):
    """Errore di comunicazione locale."""


class AuthError(LocalError):
    """L'autenticazione in LAN non e' andata a buon fine."""


def checksum(data: bytes, seed: int = CHECKSUM_SEED) -> int:
    return (seed + sum(data)) & 0xFFFF


def normalize_mac(mac: str) -> bytes:
    raw = mac.replace(":", "").replace("-", "").replace(".", "").strip()
    if len(raw) != 12:
        raise ValueError("MAC non valido: %r" % mac)
    return bytes.fromhex(raw)


def format_mac(raw: bytes) -> str:
    return ":".join("%02x" % b for b in raw)


@dataclass
class Device:
    """Un climatizzatore raggiungibile in LAN."""
    host: str
    mac: str
    key: str
    devtype: int = DEVTYPE_DEFAULT
    device_id: int = 1
    name: str = ""
    port: int = PORT
    timeout: float = TIMEOUT

    @property
    def key_bytes(self) -> bytes:
        k = bytes.fromhex(self.key)
        if len(k) != 16:
            raise ValueError("la chiave AES deve essere di 32 caratteri esadecimali")
        return k


# --------------------------------------------------------------- pacchetti


def build_packet(devtype: int, command: int, mac: bytes, device_id: int,
                 payload: bytes, key: bytes, nonce: Optional[int] = None) -> bytes:
    """Costruisce un pacchetto DNA completo, cifrando il payload."""
    if nonce is None:
        nonce = int.from_bytes(os.urandom(2), "little")
    pkt = bytearray(0x38)
    pkt[0x00:0x08] = MAGIC
    pkt[0x24:0x26] = struct.pack("<H", devtype)
    pkt[0x26:0x28] = struct.pack("<H", command)
    pkt[0x28:0x2A] = struct.pack("<H", nonce & 0xFFFF)
    pkt[0x2A:0x30] = mac[::-1]
    pkt[0x30:0x34] = struct.pack("<I", device_id)
    pkt[0x34:0x36] = struct.pack("<H", checksum(payload))
    pkt += encrypt_cbc(payload, key, IV)
    pkt[0x20:0x22] = struct.pack("<H", checksum(bytes(pkt)))
    return bytes(pkt)


def parse_packet(packet: bytes, key: bytes) -> bytes:
    """Verifica un pacchetto di risposta e restituisce il payload in chiaro."""
    if len(packet) < 0x38:
        raise LocalError("risposta troppo corta (%d byte)" % len(packet))
    err = struct.unpack("<H", packet[0x22:0x24])[0]
    if err:
        # il campo e' un int16 con segno nella pratica
        signed = err - 0x10000 if err > 0x7FFF else err
        raise LocalError("il dispositivo ha risposto con errore %d" % signed)
    enc = packet[0x38:]
    if not enc:
        raise LocalError("risposta senza payload")
    if len(enc) % 16:
        enc = enc[:len(enc) - (len(enc) % 16)]
    return decrypt_cbc(enc, key, IV)


def encode_inner(action: int, body: Dict[str, Any]) -> bytes:
    """Impacchetta il JSON di comando nel formato interno DNA."""
    raw = json.dumps(body, separators=(",", ":")).encode()
    inner = bytearray()
    inner += INNER_MAGIC                       # 0x02
    inner += b"\x00\x00"                       # 0x06 checksum, riempito dopo
    inner += bytes([action, 0x0B])             # 0x08, 0x09
    inner += struct.pack("<I", len(raw))       # 0x0a
    inner += raw                               # 0x0e
    body_part = bytes(inner[0:4]) + bytes(inner[6:])
    struct.pack_into("<H", inner, 4, checksum(body_part))
    out = struct.pack("<H", len(inner)) + bytes(inner)
    return out + b"\x00" * ((-len(out)) % 16)


def decode_inner(plain: bytes) -> Dict[str, Any]:
    """Estrae il JSON dalla risposta in chiaro."""
    if len(plain) < 0x0E:
        raise LocalError("payload interno troppo corto")
    if plain[0x02:0x06] != INNER_MAGIC:
        raise LocalError("magic interno inatteso: %s" % plain[0x02:0x06].hex())
    length = struct.unpack("<I", plain[0x0A:0x0E])[0]
    raw = plain[0x0E:0x0E + length]
    try:
        return json.loads(raw.decode("utf-8", "replace") or "{}")
    except json.JSONDecodeError as exc:
        raise LocalError("JSON non valido dal dispositivo: %r" % raw[:120]) from exc


def flatten_params_vals(data: Dict[str, Any]) -> Dict[str, Any]:
    """Converte la forma DNA {params:[...], vals:[[{val,idx}]]} in un dizionario."""
    params, vals = data.get("params"), data.get("vals")
    if not isinstance(params, list) or not isinstance(vals, list):
        return {k: v for k, v in data.items() if k not in ("params", "vals")}
    out = {}
    for i, name in enumerate(params):
        try:
            entry = vals[i]
            out[name] = entry[0]["val"] if isinstance(entry, list) else entry
        except (IndexError, KeyError, TypeError):
            out[name] = None
    return out


def build_params_vals(changes: Dict[str, Any]) -> Dict[str, Any]:
    """Converte un dizionario nella forma DNA params/vals."""
    params, vals = [], []
    for k, v in changes.items():
        params.append(k)
        vals.append([{"val": v, "idx": 1}])
    return {"params": params, "vals": vals}


# --------------------------------------------------------------- client


class LocalClient:
    """Client sincrono per un climatizzatore sulla rete locale."""

    def __init__(self, device: Device):
        self.device = device
        self._mac = normalize_mac(device.mac)

    # -- basso livello

    def _send(self, packet: bytes, expect: bool = True) -> Optional[bytes]:
        last = None
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.settimeout(self.device.timeout)
            for attempt in range(SEND_COUNT):
                sock.sendto(packet, (self.device.host, self.device.port))
                if not expect:
                    return None
                try:
                    data, _ = sock.recvfrom(4096)
                    return data
                except socket.timeout as exc:
                    last = exc
        raise LocalError("nessuna risposta da %s:%d dopo %d tentativi"
                         % (self.device.host, self.device.port, SEND_COUNT)) from last

    def _control(self, action: int, body: Dict[str, Any]) -> Dict[str, Any]:
        payload = encode_inner(action, body)
        pkt = build_packet(self.device.devtype, CMD_CONTROL, self._mac,
                           self.device.device_id, payload, self.device.key_bytes)
        resp = self._send(pkt)
        return decode_inner(parse_packet(resp, self.device.key_bytes))

    # -- alto livello

    def get_state(self, names: Optional[List[str]] = None) -> Dict[str, Any]:
        """Legge lo stato. Con `names` chiede solo quei parametri.

        Il corpo vuoto `{}` fa restituire tutto quello che il modulo ha.
        """
        body: Dict[str, Any] = {} if not names else {"act": "get", "params": list(names), "vals": []}
        resp = self._control(ACT_GET, body)
        data = resp.get("data", resp)
        return flatten_params_vals(data) if isinstance(data, dict) else {}

    def set_state(self, changes: Dict[str, Any], dialect: str = "short") -> Dict[str, Any]:
        """Scrive parametri.

        `dialect="short"` manda {"temp":230} (accettato dai moduli TCL);
        `dialect="dna"` manda la forma params/vals usata dall'app.
        """
        body = changes if dialect == "short" else dict(act="set", **build_params_vals(changes))
        resp = self._control(ACT_SET, body)
        data = resp.get("data", resp)
        return flatten_params_vals(data) if isinstance(data, dict) else {}

    def authenticate(self) -> Device:
        """Autenticazione BroadLink in LAN: restituisce chiave e id del dispositivo.

        Funziona sui moduli TCL gia' associati, senza account cloud: e' il modo
        per ottenere la chiave AES senza passare da BroadLink.
        """
        payload = bytearray(0x50)
        payload[0x04:0x14] = os.urandom(16)
        payload[0x1E] = 0x01
        payload[0x2D] = 0x01
        payload[0x30:0x36] = b"Test 1"
        pkt = build_packet(self.device.devtype, CMD_AUTH, self._mac, 0,
                           bytes(payload), INIT_KEY)
        resp = self._send(pkt)
        try:
            plain = parse_packet(resp, INIT_KEY)
        except LocalError as exc:
            raise AuthError("autenticazione rifiutata: %s" % exc) from exc
        if len(plain) < 0x14:
            raise AuthError("risposta di autenticazione troppo corta")
        device_id = struct.unpack("<I", plain[0x00:0x04])[0]
        key = plain[0x04:0x14].hex()
        self.device.device_id = device_id
        self.device.key = key
        return self.device


# --------------------------------------------------------------- discovery


def build_discovery_packet(local_ip: str, local_port: int,
                           now: Optional[time.struct_time] = None,
                           tz_offset: Optional[int] = None) -> bytes:
    """Pacchetto di discovery in broadcast, variante DNA (con magic)."""
    t = now or time.localtime()
    if tz_offset is None:
        tz_offset = -time.timezone // 3600
    pkt = bytearray(0x30)
    pkt[0x00:0x08] = MAGIC
    pkt[0x08:0x0C] = struct.pack("<i", tz_offset)
    pkt[0x0C:0x0E] = struct.pack("<H", t.tm_year)
    pkt[0x0E] = t.tm_min
    pkt[0x0F] = t.tm_hour
    pkt[0x10] = t.tm_year % 100
    pkt[0x11] = (t.tm_wday + 1) % 7
    pkt[0x12] = t.tm_mday
    pkt[0x13] = t.tm_mon
    pkt[0x18:0x1C] = bytes(int(x) for x in local_ip.split("."))[::-1]
    pkt[0x1C:0x1E] = struct.pack("<H", local_port)
    pkt[0x26] = CMD_DISCOVERY
    pkt[0x20:0x22] = struct.pack("<H", checksum(bytes(pkt)))
    return bytes(pkt)


def parse_discovery_response(data: bytes) -> Dict[str, Any]:
    """Estrae devtype, MAC e nome da una risposta di discovery."""
    if len(data) < 0x40:
        raise LocalError("risposta di discovery troppo corta")
    devtype = struct.unpack("<H", data[0x34:0x36])[0]
    mac = format_mac(data[0x3A:0x40][::-1])
    name = data[0x40:].split(b"\x00")[0].decode("utf-8", "replace") if len(data) > 0x40 else ""
    return {"devtype": devtype, "mac": mac, "name": name}


def discover(timeout: float = 4.0, broadcast: str = "255.255.255.255",
             local_ip: Optional[str] = None) -> List[Dict[str, Any]]:
    """Cerca moduli DNA in broadcast sulla rete locale."""
    if local_ip is None:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            try:
                probe.connect(("8.8.8.8", 53))
                local_ip = probe.getsockname()[0]
            except OSError:
                local_ip = "0.0.0.0"

    found: Dict[str, Dict[str, Any]] = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((local_ip, 0))
        sock.settimeout(1.0)
        port = sock.getsockname()[1]
        pkt = build_discovery_packet(local_ip, port)
        sock.sendto(pkt, (broadcast, PORT))
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                data, addr = sock.recvfrom(2048)
            except socket.timeout:
                continue
            try:
                info = parse_discovery_response(data)
            except LocalError:
                continue
            info["host"] = addr[0]
            found[info["mac"]] = info
    return list(found.values())
