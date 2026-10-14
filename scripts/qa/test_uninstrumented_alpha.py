import importlib.util
import hashlib
from pathlib import Path
import unittest

source = Path(__file__).with_name('uninstrumented_alpha.py')
spec = importlib.util.spec_from_file_location('uninstrumented_alpha', source)
alpha = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alpha)


class UninstrumentedAlphaTest(unittest.TestCase):
    def setUp(self):
        self.rgba = bytearray()
        self.background = bytearray()
        self.native = bytearray()
        for index in range(64 * 64):
            red = index % 256
            green = 80
            blue = 190
            opacity = 128 if index == 0 else 255 if index == 1 else 0
            self.rgba.extend((red, green, blue, opacity))
            self.background.extend((15, 35, 55, 255))
            self.native.extend((
                (red * opacity + 15 * (255 - opacity) + 127) // 255,
                (green * opacity + 35 * (255 - opacity) + 127) // 255,
                (blue * opacity + 55 * (255 - opacity) + 127) // 255,
                255,
            ))

    def test_accepts_expected_id_with_fractional_alpha_and_exact_byte_composite(self):
        result = alpha.compare('minecraft:white_stained_glass', self.rgba, self.background, self.native)
        self.assertTrue(result['passed'])
        self.assertEqual(result['fractionalAlphaPixels'], 1)
        self.assertEqual(result['pixelsOverTolerance'], 0)

    def test_rejects_wrong_id(self):
        with self.assertRaisesRegex(ValueError, '対象外'):
            alpha.compare('minecraft:stone', self.rgba, self.background, self.native)

    def test_rejects_fully_transparent_source(self):
        transparent = bytearray(self.rgba)
        for index in range(3, len(transparent), 4):
            transparent[index] = 0
        with self.assertRaisesRegex(ValueError, '可視alpha'):
            alpha.compare('minecraft:slime_block', transparent, self.background, self.native)

    def test_rejects_transparent_empty_slot(self):
        empty = bytearray(self.background)
        empty[3] = 0
        with self.assertRaisesRegex(ValueError, '不透明'):
            alpha.compare('minecraft:slime_block', self.rgba, empty, self.native)

    def test_fails_when_any_channel_exceeds_two(self):
        native = bytearray(self.native)
        native[0] = min(255, native[0] + 3)
        result = alpha.compare('minecraft:slime_block', self.rgba, self.background, native)
        self.assertFalse(result['passed'])
        self.assertEqual(result['pixelsOverTolerance'], 1)

    def test_report_uses_empty_background_hash_not_item_slot_hash(self):
        empty = bytes(self.background)
        item_slot_sha = hashlib.sha256(self.rgba).hexdigest()
        record = alpha.comparison_record(
            {'id': 'minecraft:slime_block', 'sample': 0, 'sha256': 'screen', 'slotRgbaSha256': item_slot_sha},
            'export', empty, {'passed': True})
        self.assertEqual(record['emptySlotRgbaSha256'], hashlib.sha256(empty).hexdigest())
        self.assertNotEqual(record['emptySlotRgbaSha256'], item_slot_sha)

    def test_rejects_capture_file_hash_mismatch(self):
        with self.assertRaisesRegex(ValueError, 'F2画像'):
            alpha.validate_capture_hashes('old-file', 'replacement-file', 'slot', 'slot')

    def test_rejects_capture_slot_hash_mismatch(self):
        with self.assertRaisesRegex(ValueError, 'slot RGBA'):
            alpha.validate_capture_hashes('screen', 'screen', 'item-slot', 'empty-slot')


if __name__ == '__main__':
    unittest.main()
