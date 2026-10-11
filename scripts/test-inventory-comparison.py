"""承認画像の限定許容が別の画像・画素差へ広がらないことを確認する。"""
import hashlib
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('comparison', Path(__file__).with_name('inventory-comparison.py'))
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)


class ComparisonTest(unittest.TestCase):
    def setUp(self):
        self.size = 8
        self.background = bytes([139, 139, 139, 255] * 64)
        self.rgba = bytearray([0, 0, 0, 0] * 64)
        self.native = bytearray(self.background)
        for y in range(2, 6):
            for x in range(2, 6):
                self.put(self.rgba, x, y, [96, 74, 42, 255])
                self.put(self.native, x, y, [96, 74, 42, 255])
        self.put(self.rgba, 3, 6, [96, 74, 42, 255])
        self.put(self.native, 6, 3, [96, 74, 42, 255])
        self.reference = {'id': 'minecraft:oak_fence', 'exportPngSha256': 'export', 'nativeRgbaSha256': hashlib.sha256(self.native).hexdigest(), 'backgroundRgbaSha256': hashlib.sha256(self.background).hexdigest(), 'pixels': [[6, 3], [3, 6]]}

    def put(self, image, x, y, color):
        p = (y * self.size + x) * 4
        image[p:p + 4] = bytes(color)

    def result(self, item_id='minecraft:oak_fence', export_sha='export'):
        with patch.object(comparison, 'APPROVED', self.reference):
            return comparison.compare(self.rgba, self.native, self.background, self.size, item_id, export_sha)

    def test_accepts_confirmed_reference_and_keeps_raw_error(self):
        result = self.result()
        self.assertTrue(result['comparisonPassed'])
        self.assertEqual(2, result['pixelsOverTolerance'])
        self.assertEqual(2, result['edgeAcceptedPixels'])

    def test_is_strict_without_reference_identity(self):
        self.assertFalse(self.result(None, None)['comparisonPassed'])

    def test_rejects_other_item(self):
        self.assertFalse(self.result(item_id='minecraft:stone')['comparisonPassed'])

    def test_rejects_other_export(self):
        self.assertFalse(self.result(export_sha='changed')['comparisonPassed'])

    def test_rejects_other_native_reference(self):
        self.put(self.native, 3, 3, [139, 139, 139, 255])
        self.assertFalse(self.result()['comparisonPassed'])

    def test_rejects_other_background(self):
        self.background = bytes([140, 140, 140, 255] * 64)
        self.assertFalse(self.result()['comparisonPassed'])

    def test_rejects_other_difference_coordinates(self):
        self.put(self.rgba, 3, 6, [0, 0, 0, 0])
        self.put(self.rgba, 4, 6, [96, 74, 42, 255])
        self.assertFalse(self.result()['comparisonPassed'])

    def test_rejects_background_colored_internal_pixel_next_to_hole(self):
        self.put(self.rgba, 4, 3, [0, 0, 0, 0])
        self.put(self.native, 4, 3, [139, 139, 139, 255])
        self.put(self.native, 3, 3, [139, 139, 139, 255])
        self.assertFalse(self.result()['comparisonPassed'])

    def test_rejects_translucent_reference_difference(self):
        self.put(self.native, 3, 3, [197, 197, 197, 255])
        self.put(self.rgba, 3, 3, [0, 0, 0, 0])
        self.assertFalse(self.result()['comparisonPassed'])

    def test_accepts_strict_match_without_reference_identity(self):
        self.put(self.native, 6, 3, [139, 139, 139, 255])
        self.put(self.rgba, 3, 6, [0, 0, 0, 0])
        result = self.result(None, None)
        self.assertTrue(result['comparisonPassed'])
        self.assertEqual(0, result['pixelsOverTolerance'])


if __name__ == '__main__':
    unittest.main()
