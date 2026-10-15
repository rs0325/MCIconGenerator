#!/usr/bin/env python3
"""同一bundleの改変・engine欠落・期限時の子process回収を検査する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys
import unittest

parser=argparse.ArgumentParser()
parser.add_argument('--bundle',type=Path,required=True)
parser.add_argument('--java',required=True)
args,remaining=parser.parse_known_args()

class HostBundleTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.root=Path(self.temporary.name)
        self.bundle=self.root/'bundle';shutil.copytree(args.bundle,self.bundle)
        self.inventory_hash=hashlib.sha256((self.bundle/'file-hashes.json').read_bytes()).hexdigest()
        (self.root/'tmp').mkdir()
    def tearDown(self):
        self.temporary.cleanup()
    def run_probe(self,mode='normal',seconds=75):
        return subprocess.run([args.java,'-Djava.io.tmpdir='+str(self.root/'tmp'),'-cp','classes'+os.pathsep+'lib/*','HostFileLockProbe',str(self.root/'report.json'),str(seconds),mode,self.inventory_hash],cwd=self.bundle,capture_output=True,text=True,timeout=90)
    def test_rejects_changed_bundle_hash(self):
        path=self.bundle/'file-hashes.json';data=json.loads(path.read_text())
        data['classes/HostFileLockProbe.class']='0'*64;path.write_text(json.dumps(data))
        result=self.run_probe()
        self.assertNotEqual(0,result.returncode)
        self.assertIn('bundle hash不一致',result.stderr)
        self.assertFalse((self.root/'report.json').exists())
    def test_rejects_missing_test_engine(self):
        jar=next((self.bundle/'lib').glob('junit-jupiter-engine-*.jar'));jar.unlink()
        path=self.bundle/'file-hashes.json';data=json.loads(path.read_text());del data[str(jar.relative_to(self.bundle)).replace(os.sep,'/')];path.write_text(json.dumps(data))
        self.inventory_hash=hashlib.sha256(path.read_bytes()).hexdigest()
        result=self.run_probe()
        self.assertNotEqual(0,result.returncode)
        if (self.root/'report.json').exists():self.assertFalse(json.loads((self.root/'report.json').read_text())['passed'])
    def test_deadline_reaps_owned_java_child(self):
        result=self.run_probe('deadline',1)
        self.assertEqual(124,result.returncode,result.stderr)
        report=json.loads((self.root/'report.json').read_text())
        self.assertFalse(report['passed'])
        self.assertEqual('DEADLINE',report['failure'])
        self.assertTrue(report['ownedChildren'])
        self.assertEqual([],report['remainingOwnedChildren'])
    def test_reap_exception_preserves_failure_report(self):
        result=self.run_probe('deadline-reap-error',1)
        self.assertEqual(124,result.returncode,result.stderr)
        report=json.loads((self.root/'report.json').read_text())
        self.assertFalse(report['passed'])
        self.assertIn('QA reap例外注入',report['cleanupFailure'])
        self.assertEqual([],report['remainingOwnedChildren'])
    def test_rejects_empty_or_nonobject_inventory(self):
        for value in ({},[]):
            path=self.bundle/'file-hashes.json';path.write_text(json.dumps(value))
            self.inventory_hash=hashlib.sha256(path.read_bytes()).hexdigest()
            result=self.run_probe()
            self.assertNotEqual(0,result.returncode)
            self.assertIn('bundle inventoryが空またはobjectではない',result.stderr)
    def test_rejects_unlisted_classpath_file(self):
        (self.bundle/'classes/unlisted.txt').write_text('unexpected classpath resource')
        result=self.run_probe()
        self.assertNotEqual(0,result.returncode)
        self.assertIn('bundle classpath集合',result.stderr)
    def test_preserves_existing_archive_before_creating_directory(self):
        output=self.root/'new.suffix';archive=output.with_suffix('.zip');archive.write_bytes(b'previous evidence')
        script=Path(__file__).parent/'bundle-host-filelocks.py'
        result=subprocess.run([sys.executable,str(script.resolve()),'--classpath','absent.json','--core-jar','absent.jar','--test-classes','absent','--out',str(output),'--javac','absent'],capture_output=True,text=True,timeout=10)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(output.exists())
        self.assertEqual(b'previous evidence',archive.read_bytes())

if __name__=='__main__':unittest.main(argv=[__file__,*remaining])
