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
