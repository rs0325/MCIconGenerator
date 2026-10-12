#!/usr/bin/env python3
"""登録集合とPNGの異常を監査が見逃さないことを確認する。"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

spec=importlib.util.spec_from_file_location('audit_export',Path(__file__).with_name('audit-export.py'))
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)


class ExportAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name)
        self.manifest=self.root/'manifest.json'
        self.data={'version':'1.13','mode':'icons','complete':True,'settings':{'size':16},
                   'counts':{'succeeded':2,'failed':0,'noItemForm':1},
                   'catalog':[{'id':'minecraft:stone','kind':'block','status':'available','blocks':['minecraft:stone']},
                              {'id':'minecraft:wheat','kind':'item','status':'available','blocks':[]},
                              {'id':'minecraft:water','kind':'block','status':'no_item_form','blocks':[]}],
                   'entries':[]}
        for item in ['stone','wheat']:
            path='assets/minecraft/textures/'+('block/' if item=='stone' else 'item/')+item+'.png'
            self.data['entries'].append({'id':'minecraft:'+item,'status':'success','path':path,'sha256':None})
            self.write_png(self.data['entries'][-1],bytes([120,80,40,255]))

    def write_png(self,entry,pixel):
        def chunk(kind,body):
            return struct.pack('>I',len(body))+kind+body+struct.pack('>I',zlib.crc32(kind+body))
        data=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',16,16,8,6,0,0,0))
        data+=chunk(b'IDAT',zlib.compress((b'\0'+pixel*16)*16))+chunk(b'IEND',b'')
        target=self.root/entry['path'];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
        entry['sha256']=hashlib.sha256(data).hexdigest()

    def result(self):
        self.manifest.write_text(json.dumps(self.data))
        return audit.audit(self.manifest,require_all=True)

    def test_registered_population_matches(self):
        result=self.result()
        self.assertEqual([],result['failures'])
        self.assertTrue(result['registryPopulationVerified'])

    def test_legacy_item_and_excluded_block_share_id(self):
        self.data['catalog'][-1]['id']='minecraft:wheat'
        self.assertEqual([],self.result()['failures'])

    def test_same_kind_id_with_conflicting_status_is_duplicate(self):
        self.data['catalog'][-1]['id']='minecraft:stone'
        self.assertIn('Duplicate catalog IDs',self.result()['failures'])

    def test_unknown_catalog_status_is_not_skipped(self):
        self.data['catalog'].append({'id':'minecraft:lava','kind':'block','status':'unknown','blocks':[]})
        self.assertIn('Unknown catalog kind or status',self.result()['failures'])

    def test_missing_registered_item(self):
        self.data['entries'].pop();self.data['counts']['succeeded']=1
        self.assertIn('Full export differs from registry population',self.result()['failures'])

    def test_duplicate_output_id(self):
        self.data['entries'].append(copy.deepcopy(self.data['entries'][-1]));self.data['counts']['succeeded']=3
        self.assertIn('Unsorted or duplicate IDs',self.result()['failures'])

    def test_duplicate_output_path(self):
        self.data['entries'][1]['path']=self.data['entries'][0]['path']
        self.assertIn('Duplicate output paths',self.result()['failures'])

    def test_changed_png_hash(self):
        self.data['entries'][0]['sha256']='0'*64
        self.assertTrue(any('SHA-256 mismatch' in f for f in self.result()['failures']))

    def test_fully_transparent_png(self):
        self.write_png(self.data['entries'][0],bytes(4))
        self.assertTrue(any('Empty output' in f for f in self.result()['failures']))


if __name__=='__main__':
    unittest.main()
