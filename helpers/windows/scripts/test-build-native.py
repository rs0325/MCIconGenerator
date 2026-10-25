"""build中断時の所有回収と元例外/raw保存を、外部compilerを起動せず検査する。"""
import importlib.util, json, pathlib, subprocess, tempfile, unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('build_native', pathlib.Path(__file__).with_name('build-native.py'))
build = importlib.util.module_from_spec(spec); spec.loader.exec_module(build)

class Child:
    pid = 123
    def __init__(self, failure=None, already_reaped=False, cleanup_failure=False):
        self.failure = failure; self.already_reaped = already_reaped; self.cleanup_failure = cleanup_failure; self.returncode = None; self.waits = 0
    def wait(self, timeout):
        self.waits += 1
        if self.waits == 1 and self.failure is not None:
            if self.already_reaped: self.returncode = 0
            raise self.failure
        if self.cleanup_failure: raise OSError('reap失敗')
        self.returncode = -9 if self.failure else 0
        return self.returncode

class BuildRunTest(unittest.TestCase):
    def invoke(self, folder, child, probe=(0, 0), probe_error=None):
        def start(*args, **kwargs):
            kwargs['stdout'].write(b'partial compiler output'); kwargs['stdout'].flush()
            kwargs['stderr'].write(b'partial compiler diagnostic'); kwargs['stderr'].flush()
            return child
        return patch.multiple(build.subprocess, Popen=Mock(side_effect=start)), patch.object(build.os, 'waitpid', side_effect=probe_error, return_value=probe), patch.object(build.os, 'killpg')
    def test_success_records_raw_and_exit_without_signal(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = pathlib.Path(directory); child = Child(); spawn, probe, kill = self.invoke(folder, child)
            with spawn, probe as waited, kill as signalled: build.run(['compiler'], folder, {})
            signalled.assert_not_called(); waited.assert_not_called()
            report = json.loads((folder / 'compile-process.json').read_text()); self.assertTrue(report['collected']); self.assertEqual(report['exit'], 0); self.assertFalse(report['unknown'])
    def test_wait_exceptions_collect_unreaped_child_and_preserve_original(self):
        for failure in [KeyboardInterrupt(), OSError('wait失敗'), subprocess.TimeoutExpired('compiler', 120)]:
            with self.subTest(failure=type(failure).__name__), tempfile.TemporaryDirectory() as directory:
                folder = pathlib.Path(directory); child = Child(failure); spawn, probe, kill = self.invoke(folder, child)
                with spawn, probe, kill as signalled, self.assertRaises(type(failure)) as caught: build.run(['compiler'], folder, {})
                self.assertIs(caught.exception, failure); signalled.assert_called_once_with(123, build.signal.SIGKILL)
                report = json.loads((folder / 'compile-process.json').read_text()); self.assertTrue(report['collected']); self.assertTrue(report['signalBeforeReap']); self.assertTrue(report['unknown']); self.assertLessEqual(child.waits, 2)
                self.assertEqual((folder / 'compile.stdout').read_bytes(), b'partial compiler output'); self.assertEqual((folder / 'compile.stderr').read_bytes(), b'partial compiler diagnostic')
    def test_already_reaped_or_probe_reaped_never_signals(self):
        for already_reaped, probe_result in [(True, (0, 0)), (False, (123, 0))]:
            with tempfile.TemporaryDirectory() as directory:
                folder = pathlib.Path(directory); failure = KeyboardInterrupt(); child = Child(failure, already_reaped); spawn, probe, kill = self.invoke(folder, child, probe_result)
                with spawn, probe, kill as signalled, self.assertRaises(KeyboardInterrupt): build.run(['compiler'], folder, {})
                signalled.assert_not_called(); self.assertTrue(json.loads((folder / 'compile-process.json').read_text())['collected'])
    def test_unknown_waitpid_never_signals_or_accepts_completed_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = pathlib.Path(directory); failure = OSError('元wait失敗'); child = Child(failure); spawn, probe, kill = self.invoke(folder, child, probe_error=ChildProcessError('ECHILD'))
            with spawn, probe, kill as signalled, self.assertRaises(OSError) as caught: build.run(['compiler'], folder, {})
            self.assertIs(caught.exception, failure); signalled.assert_not_called(); report = json.loads((folder / 'compile-process.json').read_text()); self.assertFalse(report['collected']); self.assertIsNone(report['exit']); self.assertTrue(report['unknown'])
    def test_cleanup_failure_is_unknown_without_replacing_original(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = pathlib.Path(directory); failure = KeyboardInterrupt(); child = Child(failure, cleanup_failure=True); spawn, probe, kill = self.invoke(folder, child)
            with spawn, probe, kill, self.assertRaises(KeyboardInterrupt) as caught: build.run(['compiler'], folder, {})
            self.assertIs(caught.exception, failure); report = json.loads((folder / 'compile-process.json').read_text()); self.assertFalse(report['collected']); self.assertIsNone(report['exit']); self.assertIn('OSError', report['collectionError'])
    def test_record_failure_does_not_replace_original(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = pathlib.Path(directory); failure = KeyboardInterrupt(); child = Child(failure); spawn, probe, kill = self.invoke(folder, child)
            with spawn, probe, kill, patch.object(pathlib.Path, 'write_text', side_effect=OSError('記録失敗')), self.assertRaises(KeyboardInterrupt) as caught: build.run(['compiler'], folder, {})
            self.assertIs(caught.exception, failure); self.assertTrue(failure.__notes__)

if __name__ == '__main__': unittest.main()
