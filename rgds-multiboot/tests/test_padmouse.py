"""Unit tests for overlay/usr/local/bin/rgds-padmouse (no hardware needed).

Run: python3 -m unittest discover -s tests -p 'test_*.py'
"""
import importlib.machinery
import importlib.util
import os
import tempfile
import types
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "..", "overlay", "usr", "local", "bin", "rgds-padmouse")

loader = importlib.machinery.SourceFileLoader("rgds_padmouse", SCRIPT)
spec = importlib.util.spec_from_loader("rgds_padmouse", loader)
pm = importlib.util.module_from_spec(spec)
loader.exec_module(pm)

FAKE_ECODES = types.SimpleNamespace(
    BTN_EAST=0x131, BTN_SOUTH=0x130, BTN_LEFT=0x110, BTN_RIGHT=0x111,
    KEY_ENTER=28, KEY_UP=103, BTN_DPAD_UP=0x220,
)


class AxisMath(unittest.TestCase):
    def test_normalize_centre_and_ends(self):
        self.assertAlmostEqual(pm.normalize(2048, 0, 4096), 0.0)
        self.assertAlmostEqual(pm.normalize(0, 0, 4096), -1.0)
        self.assertAlmostEqual(pm.normalize(4096, 0, 4096), 1.0)

    def test_normalize_clamps_and_handles_inverted_range(self):
        self.assertEqual(pm.normalize(9999, 0, 4096), 1.0)
        self.assertAlmostEqual(pm.normalize(0, 4096, 0), 1.0)
        self.assertEqual(pm.normalize(5, 7, 7), 0.0)

    def test_shape_deadzone_and_sign(self):
        self.assertEqual(pm.shape(0.1, 0.12, 2.0), 0.0)
        self.assertEqual(pm.shape(-0.1, 0.12, 2.0), 0.0)
        self.assertAlmostEqual(pm.shape(1.0, 0.12, 2.0), 1.0)
        self.assertAlmostEqual(pm.shape(-1.0, 0.12, 2.0), -1.0)
        mid = pm.shape(0.56, 0.12, 2.0)
        self.assertTrue(0 < mid < 0.5, mid)  # curve gives finer control near centre

    def test_accumulator_keeps_fractions(self):
        acc = pm.Accumulator()
        moved = sum(acc.step(0.4) for _ in range(10))
        self.assertEqual(moved, 4)
        acc = pm.Accumulator()
        self.assertEqual(sum(acc.step(-0.4) for _ in range(10)), -4)


class Config(unittest.TestCase):
    def test_defaults_without_file(self):
        cfg = pm.load_config("/nonexistent/padmouse.conf")
        self.assertEqual(cfg["padmouse"]["toggle"], "BTN_MODE")
        self.assertEqual(cfg["buttons"]["BTN_EAST"], "BTN_LEFT")

    def test_buttons_section_replaces_defaults(self):
        with tempfile.NamedTemporaryFile("w", suffix=".conf", delete=False) as f:
            f.write("[padmouse]\npointer_speed = 500\n[buttons]\nBTN_SOUTH = BTN_LEFT\n")
        try:
            cfg = pm.load_config(f.name)
        finally:
            os.unlink(f.name)
        self.assertEqual(cfg["padmouse"]["pointer_speed"], "500")
        self.assertEqual(cfg["padmouse"]["toggle"], "BTN_MODE")  # untouched default kept
        self.assertEqual(dict(cfg["buttons"]), {"BTN_SOUTH": "BTN_LEFT"})

    def test_resolve_buttons_skips_unknown_names(self):
        mapping, unknown = pm.resolve_buttons(
            {"BTN_EAST": "BTN_LEFT", "BTN_DPAD_UP": "KEY_UP", "BTN_FOO": "KEY_ENTER"}, FAKE_ECODES)
        self.assertEqual(mapping, {0x131: 0x110, 0x220: 103})
        self.assertEqual(unknown, ["BTN_FOO = KEY_ENTER"])

    def test_shipped_config_parses(self):
        path = os.path.join(HERE, "..", "overlay", "etc", "rgds", "padmouse.conf")
        cfg = pm.load_config(path)
        self.assertEqual(cfg["padmouse"]["pointer_axes"].split(), ["ABS_X", "ABS_Y"])
        self.assertIn("BTN_START", cfg["buttons"])  # commented-out map -> defaults stay


if __name__ == "__main__":
    unittest.main()
