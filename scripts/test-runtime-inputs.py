#!/usr/bin/env python3
"""入力検証harnessの空出力・hash・所有権・期限の拒否条件を合成検証する。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zlib

SPEC=importlib.util.spec_from_file_location('runtime_inputs',Path(__file__).with_name('verify-runtime-inputs.py'))
MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)


def png(visible=True):
    def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
    pixels=bytearray(64*64*4)
    if visible:pixels[(64+1)*4:(64+1)*4+4]=bytes((1,2,3,255))
    rows=b''.join(b'\0'+pixels[row*256:(row+1)*256] for row in range(64))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',64,64,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b'')


class RuntimeInputsTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.addCleanup(self.temporary.cleanup);self.root=Path(self.temporary.name)
        self.fixture=self.root/'fixture';jar=self.fixture/'installation/versions/1.21.4/1.21.4.jar';jar.parent.mkdir(parents=True);jar.write_bytes(b'client')
        self.worker=self.root/'agent.jar';self.worker.write_bytes(b'agent');self.case=self.root/'case'
        base=self.case/'out/java/1.21.4/icons';base.mkdir(parents=True);self.manifest=base/'manifest.json'
        image=base/'assets/minecraft/textures/block/stone.png';image.parent.mkdir(parents=True);image.write_bytes(png())
        self.data={'version':'1.21.4','mode':'icons','complete':True,'sourceSha256':MODULE.digest(jar),'settings':{'size':64,'agentSha256':MODULE.digest(self.worker)},
            'catalog':[{'id':'minecraft:stone','kind':'block','status':'available','blocks':['minecraft:stone']}],
            'counts':{'succeeded':1,'failed':0,'noItemForm':0},'entries':[{'id':'minecraft:stone','kind':'block','status':'success','path':'assets/minecraft/textures/block/stone.png','sha256':MODULE.digest(image)}]}
        self.save()
    def save(self):self.manifest.write_text(json.dumps(self.data),encoding='utf-8')
    def audit(self):return MODULE.audit_case(self.case,'1.21.4',False,self.fixture,self.worker)
    def test_real_expected_stone_is_accepted(self):
        data,audit=self.audit();self.assertTrue(data['complete']);self.assertEqual(audit['audited'],1)
    def test_empty_success_manifest_is_rejected(self):
        self.data['entries']=[];self.data['counts']['succeeded']=0;self.save()
        for p in self.case.rglob('*.png'):p.unlink()
        with self.assertRaises(ValueError):self.audit()
    def test_wrong_worker_hash_png_hash_and_invisible_pixels_are_rejected(self):
        self.data['settings']['agentSha256']='0'*64;self.save()
        with self.assertRaises(ValueError):self.audit()
        self.data['settings']['agentSha256']=MODULE.digest(self.worker);self.data['entries'][0]['sha256']='0'*64;self.save()
        with self.assertRaises(ValueError):self.audit()
        image=self.manifest.parent/self.data['entries'][0]['path'];image.write_bytes(png(False));self.data['entries'][0]['sha256']=MODULE.digest(image);self.save()
        with self.assertRaises(ValueError):self.audit()
    def test_output_overlap_is_rejected_before_creation(self):
        source=self.root/'source';source.mkdir();output=source/'new-output'
        with self.assertRaises(ValueError):MODULE.prepare(argparse.Namespace(source_cache=[source],out=output))
        self.assertFalse(output.exists())
        output=self.fixture/'installation/new-output'
        with self.assertRaises(ValueError):MODULE.verify(argparse.Namespace(fixture=self.fixture,distribution=self.root/'distribution',out=output))
        self.assertFalse(output.exists())
    def test_supervisor_failure_is_saved_before_validation_exits(self):
        for version in MODULE.VERSIONS:(self.fixture/('cache-'+version)).mkdir()
        (self.fixture/'installation-proof.json').write_text('{}',encoding='utf-8')
        distribution=self.root/'distribution';distribution.mkdir();output=self.root/'failed-output'
        arguments=['verify-runtime-inputs.py','verify','--fixture',str(self.fixture),'--distribution',str(distribution),'--worker',str(self.worker),
            '--context','synthetic','--image','synthetic','--out',str(output)]
        cleanup={'errors':[],'workerSnapshotsAbsent':True,'removedContainers':[]}
        with patch.object(sys,'argv',arguments),patch.object(MODULE,'execute',return_value=(124,cleanup)):
            with self.assertRaises(ValueError):MODULE.main()
        summary=json.loads((output/'summary.json').read_text(encoding='utf-8'))
        self.assertEqual(summary['status'],'FAIL');self.assertEqual(len(summary['records']),4)
        for record in summary['records']:
            self.assertTrue(record['supervisorTimedOut']);self.assertEqual(record['exitCode'],124)
            self.assertTrue((output/record['label']/'result.json').is_file())
    def test_existing_or_overlapping_summary_is_never_rewritten(self):
        for output in [self.root/'existing',self.fixture/'installation/existing']:
            output.mkdir();summary=output/'summary.json';sentinel=b'{"status":"PASS","sentinel":true}';summary.write_bytes(sentinel)
            arguments=['verify-runtime-inputs.py','verify','--fixture',str(self.fixture),'--distribution',str(self.root/'distribution'),
                '--context','synthetic','--image','synthetic','--out',str(output)]
            with patch.object(sys,'argv',arguments):
                with self.assertRaises(ValueError):MODULE.main()
            self.assertEqual(summary.read_bytes(),sentinel)
    def test_windows_daemon_mount_forms_match_only_the_same_host_cache(self):
        expected=MODULE.mount_path('C:\\Users\\ryuma\\case\\cache',windows=True)
        for value in ['C:/Users/ryuma/case/cache','/run/desktop/mnt/host/c/Users/ryuma/case/cache','/host_mnt/c/Users/ryuma/case/cache','/mnt/c/Users/ryuma/case/cache']:
            self.assertEqual(MODULE.mount_path(value,windows=True),expected)
        self.assertNotEqual(MODULE.mount_path('/run/desktop/mnt/host/c/Users/ryuma/other/cache',windows=True),expected)
    @unittest.skipIf(os.name=='nt','POSIXのTERM無視fixtureを使用する')
    def test_supervisor_timeout_removes_only_owned_container_and_snapshot(self):
        cache=self.root/'cache';directory=cache/'worker-owned';directory.mkdir(parents=True);snapshot=directory/'worker-agent.jar';snapshot.write_bytes(b'agent')
        docker=self.root/'docker';removed=self.root/'removed.txt'
        docker.write_text('''#!/usr/bin/env python3
import sys,json
from pathlib import Path
if 'ps' in sys.argv:print('mcicon-worker-owned\\nmcicon-worker-other')
elif 'inspect' in sys.argv:
 name=sys.argv[-1];directory='worker-owned'
 source='''+repr(str(cache))+''' if name.endswith('owned') else '''+repr(str(self.root/'other-cache'))+'''
 print(json.dumps([{'Config':{'Cmd':['-javaagent:/mcicon/worker.jar=/mcicon/cache/'+directory+'/agent.json']},'Mounts':[{'Source':source,'Destination':'/mcicon/cache'}]}]))
elif 'rm' in sys.argv:Path('''+repr(str(removed))+''').write_text(sys.argv[-1])
else:sys.exit(2)
''');docker.chmod(0o755)
        process=self.root/'blocked.py';process.write_text('import signal,time\nsignal.signal(signal.SIGTERM,lambda *_:None)\nwhile True:time.sleep(1)\n')
        with patch.dict(os.environ,PATH=str(self.root)+os.pathsep+os.environ['PATH']):
            with (self.root/'stdout').open('wb') as out,(self.root/'stderr').open('wb') as err:
                code,cleanup=MODULE.execute([sys.executable,str(process)],out,err,cache,'synthetic',timeout=1)
        self.assertEqual(code,124);self.assertEqual(cleanup['errors'],[]);self.assertEqual(cleanup['removedContainers'],['mcicon-worker-owned']);self.assertEqual(removed.read_text(),'mcicon-worker-owned');self.assertFalse(snapshot.exists())


if __name__=='__main__':unittest.main()
