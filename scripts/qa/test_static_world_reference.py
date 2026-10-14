import importlib.util
from pathlib import Path
import unittest

source = Path(__file__).with_name('static_world_reference.py')
spec = importlib.util.spec_from_file_location('static_world_reference', source)
references = importlib.util.module_from_spec(spec)
spec.loader.exec_module(references)


class StaticWorldReferenceTest(unittest.TestCase):
    def test_static_only_requires_successful_raw_reference(self):
        entries = {'minecraft:slime_block': {'rawPassed': True}}
        self.assertTrue(references.has_static_references(['slime_block'], entries))

    def test_dynamic_diagnostics_do_not_require_raw_reference(self):
        entries = {
            'minecraft:slime_block': {'rawPassed': True},
            'minecraft:clock': {'rawPassed': False},
            'minecraft:compass': {'rawPassed': False},
            'minecraft:recovery_compass': {'rawPassed': False},
            'minecraft:enchanted_book': {'rawPassed': False},
            'minecraft:sea_lantern': {'rawPassed': False},
        }
        self.assertTrue(references.has_static_references(
            ['slime_block', 'clock', 'compass', 'recovery_compass', 'enchanted_book', 'sea_lantern'], entries))

    def test_static_missing_or_failed_reference_is_rejected(self):
        self.assertFalse(references.has_static_references(['white_stained_glass'], {}))
        self.assertFalse(references.has_static_references(
            ['slime_block'], {'minecraft:slime_block': {'rawPassed': False}}))
