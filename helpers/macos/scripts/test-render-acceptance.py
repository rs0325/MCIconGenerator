"""実機に接続せず、公式取得元・整合性違反とpilot停止を検証する。"""

import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).with_name('render-acceptance.py'))
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class AcceptanceTest(unittest.TestCase):
    def test_sources_and_redirects(self):
        for url in ['https://maven.ornithemc.net/releases/net/ornithemc/feather/a.jar',
                    'https://libraries.minecraft.net/a.jar']:
            acceptance.check_url(url)
        for url in ['http://piston-data.mojang.com/a.jar', 'https://example.org/a.jar',
                    'https://piston-data.mojang.com.example.org/a.jar']:
            with self.assertRaises(acceptance.AcquisitionStopped):
                acceptance.check_url(url)
            with self.assertRaises(acceptance.AcquisitionStopped):
                acceptance.OfficialOnly().redirect_request(None, None, 302, '', {}, url)

    def test_integrity_failure_does_not_store(self):
        for sha, size in [('0' * 40, 7), (None, 8)]:
            with self.subTest(sha=sha, size=size), tempfile.TemporaryDirectory() as temporary:
                job = object.__new__(acceptance.Acceptance)
                job.cache = Path(temporary) / 'cache'
                with patch.object(acceptance.urllib.request, 'build_opener') as opener:
                    opener.return_value.open.return_value = io.BytesIO(b'invalid')
                    with self.assertRaises(acceptance.AcquisitionStopped):
                        job.fetch('https://piston-data.mojang.com/a.jar', 'a.jar', sha, size)
                self.assertFalse(job.cache.exists())

    def test_forbidden_source_does_not_connect(self):
        job = object.__new__(acceptance.Acceptance)
        with patch.object(acceptance.urllib.request, 'build_opener') as opener:
            with self.assertRaises(acceptance.AcquisitionStopped):
                job.fetch('https://example.org/a.jar', 'a.jar')
        opener.assert_not_called()

    def test_failed_pilot_stops_before_remaining_versions(self):
        job = object.__new__(acceptance.Acceptance)
        job.fetch = Mock()
        job.fetch.return_value.read_bytes.return_value = b'{"versions":[]}'
        job.version = Mock(return_value={'passed': False})
        with patch.object(acceptance.subprocess, 'check_output', return_value='/jdk21'):
            with self.assertRaisesRegex(RuntimeError, 'pilot'):
                job.execute()
        job.version.assert_called_once_with('1.21.4')


if __name__ == '__main__':
    unittest.main()
