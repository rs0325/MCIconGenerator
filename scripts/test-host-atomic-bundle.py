#!/usr/bin/env python3
"""bundle改変・観測不成立・期限・回収例外と既存成果の保護を検査する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

parser=argparse.ArgumentParser()
parser.add_argument('--bundle',type=Path,required=True)
parser.add_argument('--java',required=True)
parser.add_argument('--evidence',type=Path)
args,remaining=parser.parse_known_args()
if args.evidence:
    if args.evidence.exists():parser.error('--evidenceは新規directoryが必要')
    args.evidence.mkdir(parents=True)


class AtomicBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name)
        self.bundle=self.root/'bundle';shutil.copytree(args.bundle,self.bundle)
        self.inventory=hashlib.sha256((self.bundle/'file-hashes.json').read_bytes()).hexdigest()

    def tearDown(self):
        if args.evidence and (self.root/'evidence').exists():
            shutil.copytree(self.root/'evidence',args.evidence/self._testMethodName)
        self.temp.cleanup()

    def run_probe(self,mode='normal',seconds=40):
        return subprocess.run([args.java,'-Xmx512m','-cp','classes'+os.pathsep+'lib/*','HostAtomicRecoveryProbe',str(self.root/'output'),str(self.root/'evidence'),str(seconds),mode,self.inventory],cwd=self.bundle,capture_output=True,text=True,timeout=50)

    def failure_report(self,result):
        self.assertNotEqual(0,result.returncode,result.stderr)
        report=json.loads((self.root/'evidence/report.json').read_text())
        self.assertFalse(report['passed'])
        self.assertEqual([],report['remainingOwnedActors'])
        for pid in report['ownedActorPids']:
            with self.assertRaises(ProcessLookupError):os.kill(pid,0)
        return report

    def test_rejects_inventory_tampering(self):
        (self.bundle/'file-hashes.json').write_text('{}')
        report=self.failure_report(self.run_probe())
        self.assertIn('inventory SHA256不一致',report['failure'])
        self.assertFalse((self.root/'output').exists())

    def test_rejects_changed_compiled_actor(self):
        path=self.bundle/'classes/AtomicCrashProbe.class';path.write_bytes(path.read_bytes()+b'changed')
        report=self.failure_report(self.run_probe())
        self.assertIn('bundle SHA256不一致',report['failure'])

    def test_rejects_unlisted_classpath_resource(self):
        (self.bundle/'classes/unlisted.txt').write_text('unexpected')
        report=self.failure_report(self.run_probe())
        self.assertIn('ファイル集合不一致',report['failure'])

    def test_natural_exit_is_failure(self):
        report=self.failure_report(self.run_probe('natural-exit'))
        self.assertIn('READY前にactorが終了',report['failure'])

    def test_missing_real_part_is_failure(self):
        report=self.failure_report(self.run_probe('no-part'))
        self.assertTrue(report['readyObserved'])
        self.assertIn('実part未観測',report['failure'])
        self.assertEqual(1,len(report['cleanupKilledOwnedActors']))

    def test_deadline_reaps_owned_actor(self):
        result=self.run_probe('deadline',1)
        report=self.failure_report(result)
        self.assertEqual(124,result.returncode)
        self.assertEqual('DEADLINE',report['failure'])
        self.assertEqual(1,len(report['cleanupKilledOwnedActors']))

    def test_reap_failure_is_preserved(self):
        result=self.run_probe('deadline-reap-error',1)
        report=self.failure_report(result)
        self.assertEqual(124,result.returncode)
        self.assertIn('QA回収失敗注入',report['cleanupFailure'])

    def test_terminal_deadline_rejects_late_success(self):
        result=self.run_probe('terminal-deadline-first',1)
        report=self.failure_report(result)
        self.assertEqual(124,result.returncode)
        self.assertTrue(report['terminalFixture'])
        self.assertEqual('DEADLINE',report['terminalWinner'])
        self.assertEqual('DEADLINE',report['failure'])
        self.assertIn('LATE_SUCCESS_REJECTED',result.stdout)

    def test_terminal_success_rejects_queued_deadline(self):
        result=self.run_probe('terminal-success-first',1)
        self.assertEqual(0,result.returncode,result.stderr)
        report=json.loads((self.root/'evidence/report.json').read_text())
        self.assertTrue(report['terminalFixture'])
        self.assertTrue(report['passed'])
        self.assertEqual('SUCCESS',report['terminalWinner'])
        self.assertNotIn('failure',report)
        self.assertIn('LATE_DEADLINE_REJECTED',result.stdout)

    def test_preserves_existing_output(self):
        (self.root/'output').mkdir();sentinel=self.root/'output/prior.json';sentinel.write_bytes(b'previous evidence')
        result=self.run_probe()
        self.assertNotEqual(0,result.returncode)
        self.assertEqual(b'previous evidence',sentinel.read_bytes())
        self.assertFalse((self.root/'evidence').exists())

    def test_preserves_existing_archive(self):
        archive=self.root/'new.zip';archive.write_bytes(b'previous archive')
        script=Path(__file__).resolve().parent/'bundle-host-atomic.py'
        result=subprocess.run([sys.executable,str(script),'--distribution','absent','--out',str(self.root/'new'),'--javac','absent'],capture_output=True,text=True,timeout=10)
        self.assertNotEqual(0,result.returncode)
        self.assertEqual(b'previous archive',archive.read_bytes())
        self.assertFalse((self.root/'new').exists())


if __name__=='__main__':unittest.main(argv=[__file__,*remaining])
