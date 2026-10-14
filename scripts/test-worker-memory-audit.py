#!/usr/bin/env python3
"""正常出力の欠落・誤IDと、他jobのOOMをメモリ受入に流用しないことを確認する。"""
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

repo=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('memory',repo/'scripts/verify-worker-memory.py')
memory=importlib.util.module_from_spec(spec);spec.loader.exec_module(memory)

class MemoryAuditTest(unittest.TestCase):
    def test_empty_and_wrong_id_exports_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);png=root/'assets/minecraft/textures/block/stone.png';png.parent.mkdir(parents=True)
            def chunk(name,body):return struct.pack('>I',len(body))+name+body+struct.pack('>I',zlib.crc32(name+body))
            png.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',64,64,8,6,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\x80\x80\x80\xff'*64)*64))+chunk(b'IEND',b''))
            entry={'id':'minecraft:stone','kind':'block','status':'success','path':png.relative_to(root).as_posix(),'sha256':memory.RUNTIME.digest(png)}
            value={'version':'1.21.4','mode':'icons','complete':True,'entries':[entry],'catalog':[{'id':'minecraft:stone','kind':'block','status':'available','blocks':['minecraft:stone']}],
                   'counts':{'succeeded':1,'failed':0,'noItemForm':0},'settings':{'size':64,'agentSha256':'agent','dockerImageId':'image'},'environment':{'imageId':'image'},'sourceSha256':'source'}
            manifest=root/'manifest.json';manifest.write_text(json.dumps(value))
            self.assertEqual(memory.audit_success(manifest,'agent','image','source')['audited'],1)
            value['entries']=[];value['counts']['succeeded']=0;manifest.write_text(json.dumps(value))
            with self.assertRaises(ValueError):memory.audit_success(manifest,'agent','image','source')
            value['entries']=[dict(entry,id='minecraft:dirt')];value['counts']['succeeded']=1;manifest.write_text(json.dumps(value))
            with self.assertRaises(ValueError):memory.audit_success(manifest,'agent','image','source')

    def test_heap_failure_belongs_to_the_observed_worker(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache=Path(temporary)
            for directory,text in [('worker-1','normal'),('worker-2','Caused by: java.lang.OutOfMemoryError: Java heap space')]:
                (cache/directory).mkdir();(cache/directory/'worker.log').write_text(text)
            observed=[{'Config':{'Cmd':['-javaagent:/mcicon/worker.jar=/mcicon/cache/worker-1/agent.json']}}]
            self.assertFalse(memory.heap_evidence(observed,cache)['heapOutOfMemoryEvidence'])
            observed[0]['Config']['Cmd']=['-javaagent:/mcicon/worker.jar=/mcicon/cache/worker-2/agent.json']
            self.assertTrue(memory.heap_evidence(observed,cache)['heapOutOfMemoryEvidence'])

if __name__=='__main__':unittest.main()
