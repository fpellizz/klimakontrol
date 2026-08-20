import os
import unittest

from klimakontrol.aes import decrypt_cbc, encrypt_cbc


class TestAes(unittest.TestCase):
    def test_fips197_vector(self):
        """Vettore ufficiale FIPS-197 appendice B."""
        key = bytes(range(16))
        pt = bytes.fromhex("00112233445566778899aabbccddeeff")
        self.assertEqual(encrypt_cbc(pt, key, bytes(16)).hex(),
                         "69c4e0d86a7b0430d8cdb78070b4c55a")

    def test_all_zero_vector(self):
        self.assertEqual(encrypt_cbc(bytes(16), bytes(16), bytes(16)).hex(),
                         "66e94bd4ef8a2c3b884cfa59ca342b2e")

    def test_round_trip_random(self):
        for _ in range(20):
            key, iv = os.urandom(16), os.urandom(16)
            data = os.urandom(16 * (1 + os.urandom(1)[0] % 5))
            self.assertEqual(decrypt_cbc(encrypt_cbc(data, key, iv), key, iv), data)

    def test_zero_padding_to_block(self):
        out = encrypt_cbc(b"abc", bytes(16), bytes(16))
        self.assertEqual(len(out), 16)
        self.assertEqual(decrypt_cbc(out, bytes(16), bytes(16)), b"abc" + b"\x00" * 13)

    def test_cbc_chains_blocks(self):
        """Due blocchi identici in chiaro devono dare cifrati diversi (IV concatenato)."""
        out = encrypt_cbc(b"\x11" * 32, bytes(16), bytes(16))
        self.assertNotEqual(out[:16], out[16:])

    def test_rejects_wrong_key_size(self):
        with self.assertRaises(ValueError):
            encrypt_cbc(b"x" * 16, b"corta", bytes(16))


if __name__ == "__main__":
    unittest.main()
