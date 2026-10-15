#!/usr/bin/env python3
"""ゲームを使わず、長時間監督の所有groupとcontainer回収境界を検証する。"""
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('soak', Path(__file__).with_name('verify-startup-soak.py'))
soak = importlib.util.module_from_spec(spec)
spec.loader.exec_module(soak)


class SoakTest(unittest.TestCase):
    def test_normal_exit_is_not_reaped_before_group_inspection(self):
        process = subprocess.Popen([sys.executable, '-c', 'pass'], start_new_session=True)
        try:
            soak.wait_unreaped(process, 5, threading.Event(), Path('/nonexistent-mcicon-stop'))
            self.assertIsNone(process.returncode)
            self.assertEqual([], soak.group_actors(process.pid))
            result = soak.reap_group(process)
            self.assertEqual({'forced': False, 'remaining': [], 'exitCode': 0}, result)
        finally:
            if process.returncode is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)

    def test_parent_only_exit_recovers_child_without_touching_other_group(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            unrelated = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(30)'], start_new_session=True)
            child = 'import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(30)'
            body = 'import subprocess,sys,time;from pathlib import Path;p=subprocess.Popen([sys.executable,"-c",'+repr(child)+']);Path('+repr(str(root/'child'))+').write_text(str(p.pid));time.sleep(.2)'
            process = subprocess.Popen([sys.executable, '-c', body], start_new_session=True)
            try:
                soak.wait_unreaped(process, 5, threading.Event(), root/'stop')
                self.assertIsNone(process.returncode)
                self.assertTrue(soak.group_actors(process.pid))
                result = soak.reap_group(process)
                self.assertTrue(result['forced'])
                self.assertEqual([], result['remaining'])
                self.assertEqual(0, result['exitCode'])
                self.assertIsNone(unrelated.poll())
            finally:
                if process.returncode is None:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
                unrelated.kill()
                unrelated.wait(timeout=5)

    def test_stop_is_observed_without_waiting_for_outer_deadline(self):
        process = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(30)'], start_new_session=True)
        stop = threading.Event()
        stop.set()
        try:
            started = time.monotonic()
            with self.assertRaisesRegex(RuntimeError, 'SERIES_STOP'):
                soak.wait_unreaped(process, 420, stop, Path('/nonexistent-mcicon-stop'))
            result = soak.reap_group(process)
            self.assertLess(time.monotonic()-started, 10)
            self.assertTrue(result['forced'])
            self.assertEqual([], result['remaining'])
        finally:
            if process.returncode is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)

    def test_bad_trace_and_foreign_mount_do_not_skip_later_owned_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            folder = root/'job/0'
            folder.mkdir(parents=True)
            cache = root/'cache'
            run = cache/'worker-fixture'
            (folder/'docker-0.json').write_text('{broken')
            names = ['mcicon-worker-11111111-1111-4111-8111-111111111111', 'mcicon-worker-22222222-2222-4222-8222-222222222222']
            for i, name in enumerate(names, 1):
                (folder/('docker-'+str(i)+'.json')).write_text(json.dumps({'arguments':['--context','colima-mcicon','run','--name',name], 'runDirectory':str(run)}))
            removed = []
            def docker(command, **kwargs):
                name = command[-1]
                if 'rm' in command:
                    removed.append(name)
                    return subprocess.CompletedProcess(command, 0, '', '')
                if name in removed:
                    return subprocess.CompletedProcess(command, 1, '', 'No such object')
                source = str(cache) if name == names[1] else str(root/'foreign')
                data = {'Image':soak.IMAGE,'Name':'/'+name,'HostConfig':{'NetworkMode':'none'},'Mounts':[
                    {'Destination':'/mcicon/cache','Source':source,'RW':True},
                    {'Destination':'/mcicon/worker.jar','Source':str(run/'worker-agent.jar'),'RW':False}]}
                return subprocess.CompletedProcess(command, 0, json.dumps([data]), '')
            with patch.object(soak.subprocess, 'run', docker):
                rows = soak.cleanup_containers(root/'job', cache, ['docker','--context','colima-mcicon'])
            self.assertIn('failure', rows[0])
            self.assertIn('failure', rows[1])
            self.assertEqual([names[1]], removed)
            self.assertTrue(rows[2]['removed'])


if __name__ == '__main__':
    unittest.main()
