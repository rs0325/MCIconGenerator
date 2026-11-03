"""合成公開bytesで信頼形式・公開鍵差し込みを検証する。署名鍵は生成しない。"""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import trust_set


class TrustSetTest(unittest.TestCase):
    def setUp(self):
        self.first = trust_set.PREFIX + bytes([1]) * 32
        self.second = trust_set.PREFIX + b'\x58' + b'\x66' * 31
        self.data = trust_set.MAGIC + self.first + self.second

    def test_exact_two_der_keys_and_canonical_order(self):
        self.assertEqual(trust_set.validate(self.data), self.data)
        for data in (self.first, b'', self.data[:52], self.data + self.first,
                     trust_set.MAGIC + self.first * 2, trust_set.MAGIC + self.second + self.first,
                     b'MCITRS2\n' + self.data[8:], self.data[:8] + b'\x00' + self.data[9:]):
            with self.subTest(length=len(data)), self.assertRaises(ValueError): trust_set.validate(data)

    def test_public_key_inputs_produce_same_bytes_in_either_role_and_preserve_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); first = root / 'operational.der'; second = root / 'recovery.der'
            first.write_bytes(self.first); second.write_bytes(self.second)
            for index, keys in enumerate(((first, second), (second, first))):
                output = root / str(index)
                args = ['trust_set.py', '--operational-key', str(keys[0]), '--recovery-key', str(keys[1]), '--output', str(output)]
                with mock.patch.object(sys, 'argv', args): trust_set.main()
                self.assertEqual(trust_set.read(output), self.data)
                with mock.patch.object(sys, 'argv', args), self.assertRaises(FileExistsError): trust_set.main()
                self.assertEqual(output.read_bytes(), self.data)

    def test_duplicate_or_invalid_public_key_input_creates_no_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); first = root / 'first.der'; second = root / 'second.der'; output = root / 'trust-set.bin'
            first.write_bytes(self.first)
            for data in (self.first, b'invalid', b'\x00' + self.second[1:],
                         trust_set.PREFIX + b'\x02' + bytes(31)):
                second.write_bytes(data)
                args = ['trust_set.py', '--operational-key', str(first), '--recovery-key', str(second), '--output', str(output)]
                with mock.patch.object(sys, 'argv', args), self.assertRaises(ValueError): trust_set.main()
                self.assertFalse(output.exists())

    def test_off_curve_point_is_rejected_in_either_position_and_on_read(self):
        invalid = trust_set.PREFIX + b'\x02' + bytes(31)
        self.assertLess(self.first, invalid)
        self.assertLess(invalid, self.second)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'trust-set.bin'
            for first, second in ((self.first, invalid), (invalid, self.second)):
                data = trust_set.MAGIC + first + second
                with self.subTest(first=first), self.assertRaises(ValueError): trust_set.validate(data)
                path.write_bytes(data)
                with self.subTest(first=first), self.assertRaises(ValueError): trust_set.read(path)

    def test_noncanonical_y_and_zero_x_with_sign_bit_are_rejected(self):
        for encoded in (2**255 - 19, 2**255 - 1, 2**255 + 1):
            with self.subTest(encoded=encoded), self.assertRaises(ValueError):
                trust_set.public_key(trust_set.PREFIX + encoded.to_bytes(32, 'little'))


if __name__ == '__main__': unittest.main()
