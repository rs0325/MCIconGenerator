#!/usr/bin/env python3
"""jsonschema 4.25.1の整数演算でstateのJava long境界を検証する。"""
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

repo = Path(__file__).resolve().parents[1]
LONG_MAX = 2**63 - 1


class StateSchemaTest(unittest.TestCase):
    def test_all_schema_integer_maxima_fit_java_long(self):
        def check(node):
            if isinstance(node, dict):
                if node.get('type') == 'integer' and 'maximum' in node:
                    self.assertIs(type(node['maximum']), int)
                    self.assertLessEqual(node['maximum'], LONG_MAX)
                for child in node.values(): check(child)
            elif isinstance(node, list):
                for child in node: check(child)

        for path in sorted((repo / 'schemas').glob('*.schema.json')):
            with self.subTest(schema=path.name):
                schema = json.loads(path.read_text(encoding='utf-8'))
                Draft202012Validator.check_schema(schema)
                check(schema)

    def test_state_long_max_is_accepted_and_larger_integers_are_rejected(self):
        schema = json.loads((repo / 'schemas/bootstrap-state-v4.schema.json').read_text(encoding='utf-8'))
        fields = [schema['properties']['highestAcceptedSequence'],
                  schema['$defs']['process']['properties']['pid'],
                  schema['$defs']['initializationFailure']['properties']['pid'],
                  schema['$defs']['badCandidate']['properties']['sequence']]
        for index, field in enumerate(fields):
            with self.subTest(field=index):
                self.assertEqual(field['maximum'], LONG_MAX)
                validator = Draft202012Validator(field)
                for number in (LONG_MAX - 1, LONG_MAX):
                    self.assertTrue(validator.is_valid(json.loads(str(number))))
                for number in (LONG_MAX + 1, 9223372036854776000):
                    self.assertFalse(validator.is_valid(json.loads(str(number))))


if __name__ == '__main__': unittest.main()
