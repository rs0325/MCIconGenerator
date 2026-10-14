#!/usr/bin/env python3
"""実InventoryScreenで中間alpha素材のbyte RGB合成を限定比較する。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

repo = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--distribution', type=Path, required=True)
parser.add_argument('--source-commit', required=True)
parser.add_argument('--core-commit', required=True)
parser.add_argument('--worker-commit', required=True)
parser.add_argument('--core-sha256', required=True)
parser.add_argument('--worker-sha256', required=True)
parser.add_argument('--case', action='append', nargs=4, metavar=('VERSION', 'WORLD_QA', 'UNINSTRUMENTED_CAPTURE', 'EXPORT_OUT'), required=True)
a = parser.parse_args()

out = a.out.resolve()
if out.exists():
    parser.error('--outには未使用の新しいディレクトリを指定してください。失敗時も別outを使います')
out.mkdir(parents=True)
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
spec = importlib.util.spec_from_file_location('audit_export', repo / 'scripts/audit-export.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
spec = importlib.util.spec_from_file_location('uninstrumented_alpha', repo / 'scripts/qa/uninstrumented_alpha.py')
alpha = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alpha)

distribution = a.distribution.resolve()
core = distribution / 'lib/core-0.1.0-SNAPSHOT.jar'
worker = distribution / 'worker/worker-agent.jar'
if not core.is_file() or not worker.is_file():
    parser.error('指定distributionにcore/worker agentがありません')
if sha(core) != a.core_sha256 or sha(worker) != a.worker_sha256:
    parser.error('distributionのcore/worker SHA-256が指定値と一致しません')

report = {
    'issue': 61,
    'sourceCommit': a.source_commit,
    'coreCommit': a.core_commit,
    'coreSha256': sha(core),
    'workerCommit': a.worker_commit,
    'workerSha256': sha(worker),
    'cases': [],
    'passed': False,
}


def save():
    (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')


def crop(path):
    width, height, rgba = audit.rgba_png(path.read_bytes())
    if (width, height) != (1280, 960):
        raise ValueError(f'F2画像サイズが一致しません: {path.name}')
    return b''.join(rgba[(row * width + 320) * 4:(row * width + 384) * 4] for row in range(484, 548))


try:
    expected_ids = {'minecraft:white_stained_glass', 'minecraft:slime_block'}
    versions = [case[0] for case in a.case]
    if set(versions) != {'1.20.6', '1.21.4'} or len(versions) != 2:
        raise ValueError('1.20.6と1.21.4を各1件指定してください')
    for version, world_qa_arg, capture_arg, export_arg in a.case:
        world_qa = Path(world_qa_arg).resolve()
        capture_dir = Path(capture_arg).resolve()
        export_out = Path(export_arg).resolve()
        qa = json.loads((world_qa / 'report.json').read_text())
        capture = json.loads((capture_dir / 'capture.json').read_text())
        manifest_path = export_out / 'java' / version / 'icons' / 'manifest.json'
        manifest = json.loads(manifest_path.read_text())
        manifest_entries = {entry['id']: entry for entry in manifest['entries']}
        qa_entries = {entry['id']: entry for entry in qa['entries']}
        excluded = set(qa.get('excludedProductionClasses', []))
        expected_exclusions = {
            'RenderAgent', 'CanonicalTransformer', 'CanonicalContext', 'ResourceTransformer',
            'ModernRenderer', 'ClassicRenderer', 'FixedRenderer', 'LegacyRenderer',
            'DeferredRenderer', 'GpuImmediateRenderer', 'LatestRenderer', 'PngOutput', 'Bridge',
        }

        if not qa['passed'] or qa['version'] != version or not expected_ids.issubset(qa_entries):
            raise ValueError(f'{version}: 対象IDを含む静的World QAが合格していません')
        if not expected_exclusions.issubset(excluded):
            raise ValueError(f'{version}: 補助World QAにproduction描画コードが含まれていないと確認できません')
        if any(not qa_entries[item]['rawPassed'] for item in expected_ids):
            raise ValueError(f'{version}: 対象IDのWorld QA raw比較が失敗しています')
        if not capture['completed'] or capture['version'] != version or capture['exitCode'] != 0:
            raise ValueError(f'{version}: 無agent captureが正常完了していません')
        if not capture['instrumentationAbsent'] or capture['officialInputsModified'] or capture['sourceWorldModified']:
            raise ValueError(f'{version}: 無agent・入力不変の条件を満たしません')
        if capture['count'] != 1 or capture['componentOverrides'] or capture['slot'] != 9 or capture['slotGui'] != [80, 121]:
            raise ValueError(f'{version}: 既定ItemStackまたはslot 9条件が一致しません')
        resourcepacks = capture_dir / 'game/resourcepacks'
        if resourcepacks.exists() and any(path.is_file() for path in resourcepacks.rglob('*')):
            raise ValueError(f'{version}: QA resource packファイルが存在します')
        options = (capture_dir / 'game/options.txt').read_text()
        if 'mcicon-' in options or 'resourcePacks:' in options:
            raise ValueError(f'{version}: QA resource pack設定があります')
        command = capture['clientCommand']
        if any(token in command for token in ('-javaagent', '-agentlib', '-agentpath')) or any('worker-agent' in token or 'world-reference.jar' in token for token in command):
            raise ValueError(f'{version}: client commandにagentまたはhookがあります')
        if not capture['ownedContainerCleanup']['removed']:
            raise ValueError(f'{version}: owned containerが削除されていません')
        if capture['qaReferenceReportSha256'] != sha(world_qa / 'report.json'):
            raise ValueError(f'{version}: World QA reference report hashが一致しません')
        if capture['sourceSha256'] != qa['sourceSha256'] or manifest['sourceSha256'] != capture['sourceSha256']:
            raise ValueError(f'{version}: 元client hashが一致しません')
        if capture['imageId'] != qa['imageId'] or manifest['environment']['imageId'] != capture['imageId']:
            raise ValueError(f'{version}: QA・renderer・clientのimage IDが一致しません')
        if manifest['version'] != version or manifest['settings']['size'] != 64 or manifest['settings']['agentSha256'] != a.worker_sha256:
            raise ValueError(f'{version}: export版・size・worker agent hashが一致しません')
        if not manifest['complete'] or any(item not in manifest_entries or manifest_entries[item]['status'] != 'success' for item in expected_ids):
            raise ValueError(f'{version}: 対象PNGが成功manifestにありません')
        if not capture['worldOutputLevelDatSha256']:
            raise ValueError(f'{version}: client終了後のWorld保存を確認できません')

        captures = capture['captures']
        expected_capture_ids = {'empty', *expected_ids}
        if len(captures) != 6 or {entry['id'] for entry in captures} != expected_capture_ids:
            raise ValueError(f'{version}: expected ID集合またはcapture数が一致しません')
        empty = [entry for entry in captures if entry['id'] == 'empty']
        if len(empty) != 2 or {entry['sample'] for entry in empty} != {0, 1}:
            raise ValueError(f'{version}: empty slotの2 sampleが揃いません')
        if any(not entry['qaSlotRgbaMatches'] for entry in captures):
            raise ValueError(f'{version}: captureがWorld QA参照と一致しません')
        if any(entry['count'] != 0 for entry in empty):
            raise ValueError(f'{version}: empty slotにitemがあります')
        for entry in captures:
            screenshot_path = capture_dir / entry['screen']
            screenshot = crop(screenshot_path)
            alpha.validate_capture_hashes(
                entry['sha256'], sha(screenshot_path), entry['slotRgbaSha256'], hashlib.sha256(screenshot).hexdigest())

        operations = {item['command'] for item in capture['commandConfirmations']}
        required_commands = {'/clear', *(f'/item replace entity @s inventory.0 with {item}' for item in sorted(expected_ids))}
        if not required_commands.issubset(operations):
            raise ValueError(f'{version}: vanilla commandで対象IDを確認できません')

        empty_by_sample = {entry['sample']: crop(capture_dir / entry['screen']) for entry in empty}
        case = {
            'version': version,
            'sourceSha256': capture['sourceSha256'],
            'imageId': capture['imageId'],
            'worldQaReportSha256': capture['qaReferenceReportSha256'],
            'clientInputsSha256': {item['path']: item['sha256'] for item in capture['inputs']},
            'sourceWorldInputsSha256': hashlib.sha256(json.dumps(capture['worldInputs'], sort_keys=True, separators=(',', ':')).encode()).hexdigest(),
            'harnessSourcesSha256': capture['harnessSourcesSha256'],
            'captureHarnessSha256': capture['harnessSourcesSha256']['scripts/verify-uninstrumented-inventory.py'],
            'postCaptureHarnessSourcesSha256': {
                'scripts/verify-uninstrumented-inventory.py': sha(repo / 'scripts/verify-uninstrumented-inventory.py'),
                'scripts/qa/owned_container.py': sha(repo / 'scripts/qa/owned_container.py'),
                'scripts/qa/static_world_reference.py': sha(repo / 'scripts/qa/static_world_reference.py'),
            },
            'alphaAuditHarnessSha256': sha(Path(__file__).resolve()),
            'alphaAuditModuleSha256': sha(repo / 'scripts/qa/uninstrumented_alpha.py'),
            'clientProcessProofSha256': capture['clientProcessProofSha256'],
            'instrumentationAbsent': capture['instrumentationAbsent'],
            'qaResourcePackFiles': sum(path.is_file() for path in resourcepacks.rglob('*')) if resourcepacks.exists() else 0,
            'resourcePackSettingPresent': 'resourcePacks:' in options,
            'officialInputsModified': capture['officialInputsModified'],
            'sourceWorldModified': capture['sourceWorldModified'],
            'ownedContainerCleanup': capture['ownedContainerCleanup'],
            'excludedProductionClasses': sorted(excluded),
            'exitCode': capture['exitCode'],
            'captures': [],
        }
        report['cases'].append(case)
        save()
        for item in sorted(expected_ids):
            png_entry = manifest_entries[item]
            png_path = manifest_path.parent / png_entry['path']
            if sha(png_path) != png_entry['sha256']:
                raise ValueError(f'{version}/{item}: export PNG hashがmanifestと一致しません')
            width, height, rgba = audit.rgba_png(png_path.read_bytes())
            if (width, height) != (64, 64):
                raise ValueError(f'{version}/{item}: export PNGサイズが64pxではありません')
            item_captures = [entry for entry in captures if entry['id'] == item]
            if len(item_captures) != 2 or {entry['sample'] for entry in item_captures} != {0, 1}:
                raise ValueError(f'{version}/{item}: 2 sampleが揃いません')
            for entry in sorted(item_captures, key=lambda current: current['sample']):
                if entry['count'] != 1 or entry['slotGui'] != [80, 121]:
                    raise ValueError(f'{version}/{item}: 既定count 1・slot 9ではありません')
                native = crop(capture_dir / entry['screen'])
                metrics = alpha.compare(item, rgba, empty_by_sample[entry['sample']], native)
                result = alpha.comparison_record(
                    entry, png_entry['sha256'], empty_by_sample[entry['sample']], metrics)
                case['captures'].append(result)
                save()
                if not metrics['passed']:
                    raise ValueError(f'{version}/{item}/sample-{entry["sample"]}: alpha合成が許容値を超えました')
        if len(case['captures']) != 4:
            raise ValueError(f'{version}: 比較件数が4ではありません')

    report['allEightComparisonsPassed'] = sum(len(case['captures']) for case in report['cases']) == 8
    report['passed'] = report['allEightComparisonsPassed'] and all(
        item['passed'] for case in report['cases'] for item in case['captures'])
    save()
    if not report['passed']:
        raise SystemExit(1)
except BaseException as error:
    report['failure'] = {'type': type(error).__name__, 'message': str(error)}
    save()
    raise
