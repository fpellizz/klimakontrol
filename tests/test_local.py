import struct
import unittest

from klimakontrol import local as L


class TestInnerPayload(unittest.TestCase):
    def test_matches_documented_golden_packet(self):
        """Il payload di `set temp 23.0` deve uscire identico a quello osservato.

        Riferimento (docs/protocol.md):
            1800 a5a55a5a 87c4 020b 0c000000 7b2274656d70223a3233307d 000000000000
        """
        out = L.encode_inner(L.ACT_SET, {"temp": 230})
        self.assertEqual(
            out.hex(),
            "1800a5a55a5a87c4020b0c0000007b2274656d70223a3233307d000000000000")

    def test_get_payload_golden(self):
        """`get` con corpo vuoto: 0e00 a5a55a5a <cks> 010b 02000000 7b7d"""
        out = L.encode_inner(L.ACT_GET, {})
        self.assertTrue(out.hex().startswith("0e00a5a55a5a"))
        self.assertIn("010b02000000", out.hex())
        self.assertTrue(out.hex().endswith("7b7d"))

    def test_declared_length_excludes_first_two_bytes(self):
        out = L.encode_inner(L.ACT_SET, {"pwr": 1})
        declared = struct.unpack("<H", out[:2])[0]
        payload = out[2:].rstrip(b"\x00")
        self.assertGreaterEqual(declared, len(payload))

    def test_round_trip(self):
        body = {"pwr": 1, "temp": 235, "tcl_mode": 3}
        self.assertEqual(L.decode_inner(L.encode_inner(L.ACT_SET, body)), body)


class TestChecksum(unittest.TestCase):
    def test_seed(self):
        self.assertEqual(L.checksum(b""), 0xBEAF)

    def test_wraps_at_16_bits(self):
        self.assertEqual(L.checksum(b"\xff" * 1000), (0xBEAF + 255 * 1000) & 0xFFFF)


class TestPacket(unittest.TestCase):
    KEY = bytes.fromhex("00112233445566778899aabbccddeeff")
    MAC = "aa:bb:cc:dd:ee:ff"

    def _packet(self):
        payload = L.encode_inner(L.ACT_SET, {"temp": 230})
        return L.build_packet(0x507C, L.CMD_CONTROL, L.normalize_mac(self.MAC),
                              1, payload, self.KEY, nonce=0x1234)

    def test_header_layout(self):
        pkt = self._packet()
        self.assertEqual(pkt[0x00:0x08], L.MAGIC)
        self.assertEqual(struct.unpack("<H", pkt[0x24:0x26])[0], 0x507C)
        self.assertEqual(struct.unpack("<H", pkt[0x26:0x28])[0], L.CMD_CONTROL)
        self.assertEqual(struct.unpack("<H", pkt[0x28:0x2A])[0], 0x1234)
        self.assertEqual(pkt[0x2A:0x30], L.normalize_mac(self.MAC)[::-1])
        self.assertEqual(struct.unpack("<I", pkt[0x30:0x34])[0], 1)

    def test_packet_checksum_verifies(self):
        pkt = self._packet()
        stated = struct.unpack("<H", pkt[0x20:0x22])[0]
        zeroed = pkt[:0x20] + b"\x00\x00" + pkt[0x22:]
        self.assertEqual(stated, L.checksum(zeroed))

    def test_payload_checksum_covers_plaintext(self):
        payload = L.encode_inner(L.ACT_SET, {"temp": 230})
        pkt = self._packet()
        self.assertEqual(struct.unpack("<H", pkt[0x34:0x36])[0], L.checksum(payload))

    def test_parse_round_trip(self):
        pkt = self._packet()
        self.assertEqual(L.decode_inner(L.parse_packet(pkt, self.KEY)), {"temp": 230})

    def test_error_field_raises(self):
        pkt = bytearray(self._packet())
        struct.pack_into("<H", pkt, 0x22, 0xFFFF)   # -1
        with self.assertRaises(L.LocalError):
            L.parse_packet(bytes(pkt), self.KEY)

    def test_short_response_raises(self):
        with self.assertRaises(L.LocalError):
            L.parse_packet(b"\x00" * 16, self.KEY)


class TestParamsVals(unittest.TestCase):
    def test_flatten(self):
        data = {"params": ["pwr", "temp"],
                "vals": [[{"val": 1, "idx": 1}], [{"val": 230, "idx": 1}]]}
        self.assertEqual(L.flatten_params_vals(data), {"pwr": 1, "temp": 230})

    def test_flatten_tolerates_missing_values(self):
        data = {"params": ["pwr", "temp"], "vals": [[{"val": 1, "idx": 1}]]}
        self.assertEqual(L.flatten_params_vals(data), {"pwr": 1, "temp": None})

    def test_flatten_passthrough_when_not_dna_shape(self):
        self.assertEqual(L.flatten_params_vals({"temp": 230}), {"temp": 230})

    def test_build_round_trip(self):
        changes = {"pwr": 1, "temp": 230}
        self.assertEqual(L.flatten_params_vals(L.build_params_vals(changes)), changes)


class TestMac(unittest.TestCase):
    def test_accepts_separators(self):
        for text in ("aa:bb:cc:dd:ee:ff", "aa-bb-cc-dd-ee-ff", "aabbccddeeff"):
            self.assertEqual(L.normalize_mac(text), bytes.fromhex("aabbccddeeff"))

    def test_rejects_bad_length(self):
        with self.assertRaises(ValueError):
            L.normalize_mac("aa:bb:cc")

    def test_format_round_trip(self):
        self.assertEqual(L.format_mac(L.normalize_mac("AA:BB:CC:DD:EE:FF")),
                         "aa:bb:cc:dd:ee:ff")


class TestDiscovery(unittest.TestCase):
    def test_packet_shape(self):
        pkt = L.build_discovery_packet("192.168.1.50", 36200)
        self.assertEqual(len(pkt), 0x30)
        self.assertEqual(pkt[0x00:0x08], L.MAGIC)
        self.assertEqual(pkt[0x26], L.CMD_DISCOVERY)
        self.assertEqual(pkt[0x18:0x1C], bytes([50, 1, 168, 192]))
        self.assertEqual(struct.unpack("<H", pkt[0x1C:0x1E])[0], 36200)
        stated = struct.unpack("<H", pkt[0x20:0x22])[0]
        zeroed = pkt[:0x20] + b"\x00\x00" + pkt[0x22:]
        self.assertEqual(stated, L.checksum(zeroed))

    def test_parse_response(self):
        data = bytearray(0x50)
        struct.pack_into("<H", data, 0x34, 0x507C)
        data[0x3A:0x40] = bytes.fromhex("aabbccddeeff")[::-1]
        data[0x40:0x4C] = b"OEM_TCLISO_1\x00"[:12]
        info = L.parse_discovery_response(bytes(data))
        self.assertEqual(info["devtype"], 0x507C)
        self.assertEqual(info["mac"], "aa:bb:cc:dd:ee:ff")
        self.assertTrue(info["name"].startswith("OEM_TCLISO"))


if __name__ == "__main__":
    unittest.main()
