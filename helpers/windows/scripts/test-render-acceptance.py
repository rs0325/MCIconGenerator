"""実機に触れず、取得元・入力検証・回収境界を検証する。"""
import importlib.util
import io
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).with_name('render-acceptance.py'))
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class AcceptanceTest(unittest.TestCase):
    def test_sources_and_redirects(self):
        acceptance.check_url('https://maven.ornithemc.net/releases/net/ornithemc/feather/a.jar')
        acceptance.check_url('https://libraries.minecraft.net/a.jar')
        for url in ['http://piston-data.mojang.com/a.jar', 'https://example.org/a.jar',
                    'https://piston-data.mojang.com.example.org/a.jar']:
            with self.assertRaisesRegex(RuntimeError, '許可外URL'):
                acceptance.check_url(url)
            with self.assertRaisesRegex(RuntimeError, '許可外URL'):
                acceptance.OfficialOnly().redirect_request(None, None, 302, '', {}, url)

    def test_hash_failure_does_not_store_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            job = object.__new__(acceptance.Acceptance)
            job.cache = Path(temporary) / 'cache'
            job.download_lock = threading.Lock()
            job.stop = threading.Event()
            response = io.BytesIO(b'invalid')
            with patch.object(acceptance.urllib.request, 'build_opener') as opener:
                opener.return_value.open.return_value = response
                with self.assertRaisesRegex(RuntimeError, 'SHA-1/size不一致'):
                    job.fetch('https://piston-data.mojang.com/a.jar', 'a.jar', '0' * 40, 7)
            self.assertFalse(job.cache.exists())
            self.assertTrue(job.stop.is_set())

    def test_stopped_acquisition_does_not_connect(self):
        job = object.__new__(acceptance.Acceptance)
        job.stop = threading.Event()
        job.stop.set()
        with patch.object(acceptance.urllib.request, 'build_opener') as opener:
            with self.assertRaises(acceptance.AcquisitionStopped):
                job.fetch('https://piston-data.mojang.com/a.jar', 'a.jar')
        opener.assert_not_called()

    def test_forbidden_source_stops_other_acquisition(self):
        job = object.__new__(acceptance.Acceptance)
        job.stop = threading.Event()
        with patch.object(acceptance.urllib.request, 'build_opener') as opener:
            with self.assertRaises(acceptance.AcquisitionStopped):
                job.fetch('https://example.org/a.jar', 'a.jar')
        opener.assert_not_called()
        self.assertTrue(job.stop.is_set())

    def test_foreign_registration_is_not_removed(self):
        job = object.__new__(acceptance.Acceptance)
        job.name = 'mcicon-f1-test'
        job.registration = {'name': job.name, 'guid': 'owned'}
        job.guest = Mock()
        job.command = Mock()
        with patch.object(acceptance, 'registrations', return_value={
                'distributions': [{'name': job.name, 'guid': 'replaced'}]}):
            with self.assertRaisesRegex(RuntimeError, '所有登録不一致'):
                job.cleanup()
        job.guest.assert_not_called()
        job.command.assert_not_called()

    def test_cleanup_path_boundary(self):
        with tempfile.TemporaryDirectory() as temporary:
            job = object.__new__(acceptance.Acceptance)
            job.run = Path(temporary) / 'run'
            job.run.mkdir()
            foreign = Path(temporary) / 'foreign'
            foreign.mkdir()
            with self.assertRaisesRegex(RuntimeError, 'path境界不一致'):
                job.remove(foreign)
            self.assertTrue(foreign.is_dir())


if __name__ == '__main__':
    unittest.main()
