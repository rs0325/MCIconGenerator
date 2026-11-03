#!/usr/bin/env python3
"""Maven consumer検証器の単体テスト。"""
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

    def test_managed_api_reports_missing_runtime_as_unperformed(self):
        self.assertIn('未実施', consumer.managed_api_result(False, (), '実行前'))

    def test_managed_api_reports_exercise_from_execution_marker(self):
        status = consumer.managed_api_result(True, {'doctor': '呼出完了'}, '実行前')
        self.assertIn('doctorManaged=呼出完了', status)
        self.assertIn('renderManaged=未実施', status)

    def test_doctor_exception_after_call_start_is_reported_as_executed_failure(self):
        self.assertEqual(consumer.managed_markers('MCICON_MANAGED:abc:DOCTOR_START', 'abc')['doctor'], '実行・失敗')

    def test_compile_output_inside_render_failure_is_not_compile_failure(self):
        output = "> Task :compileJava\nExecution failed for task ':run'."
        self.assertEqual(consumer.managed_failure_stage(output, 1), 'Gradle実行失敗')

    def test_marker_text_in_error_body_is_not_accepted(self):
        self.assertEqual(consumer.managed_markers('error: MCICON_MANAGED:abc:DOCTOR_END', 'abc')['doctor'], '未実施')

    def test_marker_requires_this_execution_nonce_and_full_line(self):
        output = 'MCICON_MANAGED:other:DOCTOR_END\nMCICON_MANAGED:abc:DOCTOR_END trailing'
        self.assertEqual(consumer.managed_markers(output, 'abc')['doctor'], '未実施')

    def test_gradle_compile_failure_does_not_claim_managed_apis_ran(self):
        failure = consumer.managed_failure_stage("> Task :compileJava FAILED", 1)
        status = consumer.managed_api_result(True, {'doctor': '未実施'}, failure)
        self.assertIn('未実施', status)
        self.assertIn('Gradleコンパイル失敗', status)
        self.assertNotIn('renderManagedを実行', status)

    def test_managed_runtime_requires_cached_render_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            missing = consumer.managed_runtime_missing(Path(temporary))
            self.assertIn('versions/1.13/client.jar', missing)
            self.assertIn('mappings/1.13/feather.jar', missing)


if __name__ == '__main__':
    unittest.main()
