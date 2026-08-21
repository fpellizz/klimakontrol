import unittest

from klimakontrol import params as P


class TestDecode(unittest.TestCase):
    def test_temperature_is_tenths_of_degree(self):
        self.assertEqual(P.PARAMS["temp"].decode(235), 23.5)

    def test_enum_becomes_label(self):
        self.assertEqual(P.PARAMS["tcl_mode"].decode(3), "freddo")
        self.assertEqual(P.PARAMS["tcl_mark"].decode(0), "auto")
        self.assertEqual(P.PARAMS["pwr"].decode(1), "on")

    def test_unknown_enum_value_passes_through(self):
        self.assertEqual(P.PARAMS["tcl_mode"].decode(99), 99)

    def test_none_stays_none(self):
        self.assertIsNone(P.PARAMS["temp"].decode(None))

    def test_decode_status_keeps_unknown_keys(self):
        out = P.decode_status({"temp": 230, "boh": 7})
        self.assertEqual(out["temp"], 23.0)
        self.assertEqual(out["boh"], 7)


class TestEncode(unittest.TestCase):
    def test_temperature_round_trips(self):
        self.assertEqual(P.PARAMS["temp"].encode(23.5), 235)

    def test_enum_by_name(self):
        self.assertEqual(P.PARAMS["tcl_mode"].encode("caldo"), 1)
        self.assertEqual(P.PARAMS["tcl_mark"].encode("MEDIA".lower()), 2)

    def test_enum_rejects_unknown_label(self):
        with self.assertRaises(ValueError):
            P.PARAMS["tcl_mode"].encode("tiepido")

    def test_read_only_param_is_refused(self):
        with self.assertRaises(ValueError):
            P.encode_changes({"envtemp": 20})

    def test_encode_changes_mixed(self):
        out = P.encode_changes({"pwr": "on", "temp": 21.0, "tcl_mode": "caldo"})
        # `temp` viene tradotto su `save_temp` (setpoint reale di questi moduli)
        self.assertEqual(out, {"pwr": 1, "save_temp": 210, "tcl_mode": 1})

    def test_temp_is_aliased_to_save_temp_on_the_wire(self):
        """Su questi moduli il setpoint e' save_temp; temp e' ignorato (HW 2026-08-21)."""
        self.assertEqual(P.wire_key("temp"), "save_temp")
        self.assertEqual(P.wire_key("pwr"), "pwr")
        # la codifica usa la scala di temp (0.1) ma emette la chiave save_temp
        self.assertEqual(P.encode_changes({"temp": 24.0}), {"save_temp": 240})


class TestDictionary(unittest.TestCase):
    def test_known_modes_match_the_app(self):
        self.assertEqual(P.MODE, {1: "caldo", 2: "deumidifica", 3: "freddo",
                                  4: "ventola", 5: "auto"})

    def test_fan_speed_order_is_not_numeric(self):
        """Nell'app le velocita' non sono in ordine di valore: 1 4 2 5 3."""
        self.assertEqual([P.FAN[v] for v in (1, 4, 2, 5, 3)],
                         ["bassa", "medio-bassa", "media", "medio-alta", "alta"])

    def test_swing_uses_seven_for_vertical(self):
        self.assertEqual(P.PARAMS["tcl_vdir"].encode("oscillante"), 7)
        self.assertEqual(P.PARAMS["tcl_hdir"].encode("oscillante"), 1)

    def test_basic_set_is_subset_of_dictionary(self):
        for name in P.BASIC_SET:
            self.assertIn(name, P.PARAMS)

    def test_every_param_has_label_and_category(self):
        for name, p in P.PARAMS.items():
            self.assertTrue(p.label, name)
            self.assertIn(p.category, (P.CONTROL, P.SENSOR, P.COMFORT,
                                       P.DIAG, P.ENERGY, P.SYSTEM))

    def test_writable_control_params(self):
        for name in ("pwr", "temp", "tcl_mode", "tcl_mark"):
            self.assertTrue(P.PARAMS[name].writable, name)

    def test_diagnostics_are_read_only(self):
        for name in ("compressor_hz", "out_volt", "ac_errcode", "in_coil_temp"):
            self.assertFalse(P.PARAMS[name].writable, name)


if __name__ == "__main__":
    unittest.main()
