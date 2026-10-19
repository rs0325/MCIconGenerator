#!/usr/bin/env python3
"""Linuxと既存OSのresource fixtureをjsonschemaで検査する。実OS操作は行わない。"""
import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator, FormatChecker

repo=Path(__file__).resolve().parents[1]


class LinuxContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        schema=json.loads((repo/'schemas/managed-helper-v1.schema.json').read_text())
        Draft202012Validator.check_schema(schema)
        cls.validator=Draft202012Validator({'$schema':schema['$schema'],'$defs':schema['$defs'],'$ref':'#/$defs/resources'},format_checker=FormatChecker())
        cls.examples=json.loads((repo/'schemas/managed-helper-linux-v1.examples.json').read_text())['resourceExamples']
        cls.ready=next(x['resources'] for x in cls.examples if x['name']=='linux-ready-owned-shape')

    def test_shared_linux_and_existing_os_fixtures(self):
        existing=json.loads((repo/'schemas/managed-helper-v1.examples.json').read_text())['resourceExamples']
        for case in self.examples+existing:
            with self.subTest(case=case['name']):
                self.assertEqual(case['schemaValid'],self.validator.is_valid(case['resources']))

    def test_ready_requires_every_live_identity(self):
        for name in ['hostBootId','uid','userUnit','rootlesskit','dockerd','userNamespace','dockerSocket']:
            value=copy.deepcopy(self.ready);del value['linux'][name]
            with self.subTest(field=name):self.assertFalse(self.validator.is_valid(value))

    def test_nested_records_are_closed_and_complete(self):
        for name in ['userUnit','rootlesskit','dockerd','userNamespace','dockerSocket']:
            value=copy.deepcopy(self.ready);value['linux'][name]['unknown']=1
            with self.subTest(field=name):self.assertFalse(self.validator.is_valid(value))
            for field in self.ready['linux'][name]:
                value=copy.deepcopy(self.ready);del value['linux'][name][field]
                with self.subTest(record=name,field=field):self.assertFalse(self.validator.is_valid(value))

    def test_root_uid_paths_and_container_identity_pair_are_rejected(self):
        for field,value in [('uid',0),('uid',4294967295),('runtimeDir','relative'),('runtimeDir','/fixture/../runtime'),('runtimeDir','/fixture//runtime'),('runtimeDir','/'),('runtimeDir','/fixture/')]:
            record=copy.deepcopy(self.ready);record['linux'][field]=value
            with self.subTest(field=field,value=value):self.assertFalse(self.validator.is_valid(record))
        for field in ['id','startedAt']:
            value=copy.deepcopy(self.ready);del value['linux']['containers'][1][field]
            with self.subTest(field=field):self.assertFalse(self.validator.is_valid(value))


if __name__=='__main__':unittest.main()
