#!/usr/bin/env python3
"""consumerの実解決JARと候補bytesの一致・改変・不足を検証する。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('consumer', Path(__file__).with_name('verify-maven-consumer.py'))
consumer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(consumer)


class ArtifactComparisonTest(unittest.TestCase):
    def test_resolved_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / 'candidate.zip'
            artifacts = {}
            with zipfile.ZipFile(bundle, 'w') as archive:
                for artifact in ('mcicon-core', 'mcicon-worker'):
                    path = root / f'{artifact}-1.1.0.jar'
                    path.write_bytes(artifact.encode())
                    artifacts[artifact] = str(path)
                    archive.write(path, f'io/github/rs0325/mcicon/{artifact}/1.1.0/{path.name}')
            self.assertTrue(all(item['passed'] for item in consumer.compare_artifacts(bundle, '1.1.0', artifacts)))
            Path(artifacts['mcicon-worker']).write_bytes(b'changed worker')
            results = consumer.compare_artifacts(bundle, '1.1.0', artifacts)
            self.assertTrue(results[0]['passed'])
            self.assertFalse(results[1]['passed'])
            with self.assertRaises(KeyError):
                consumer.compare_artifacts(bundle, '1.1.1', artifacts)


if __name__ == '__main__':
    unittest.main()
