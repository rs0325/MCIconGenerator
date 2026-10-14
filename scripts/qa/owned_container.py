"""QA clientの所有containerだけを削除する。"""
import json
from pathlib import Path
import subprocess


def cleanup(docker, name, label, mounts, run=subprocess.run):
    try:
        inspected = run(docker + ['inspect', name], capture_output=True, text=True, timeout=15)
    except Exception as error:
        return {'attempted': True, 'removed': False, 'error': f'{type(error).__name__}: {error}'}
    if inspected.returncode != 0:
        return {
            'attempted': True,
            'removed': False,
            'exists': False,
            'inspectReturnCode': inspected.returncode,
            'stderr': inspected.stderr,
        }

    try:
        containers = json.loads(inspected.stdout)
        if len(containers) != 1:
            raise ValueError('inspect結果の件数が一致しません')
        container = containers[0]
        actual_mounts = {
            mount['Destination']: (str(Path(mount['Source']).resolve()), mount['RW'])
            for mount in container['Mounts']
        }
        expected_mounts = {
            destination: (str(Path(source).resolve()), writable)
            for destination, (source, writable) in mounts.items()
        }
        owned = (
            container['Name'].lstrip('/') == name
            and (container['Config'].get('Labels') or {}).get('mcicon.qa.owner') == label
            and actual_mounts == expected_mounts
        )
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        return {'attempted': True, 'removed': False, 'exists': True, 'error': str(error)}
    if not owned:
        return {'attempted': True, 'removed': False, 'exists': True, 'owned': False}

    try:
        removed = run(docker + ['rm', '--force', name], capture_output=True, text=True, timeout=120)
        return {
            'attempted': True,
            'removed': removed.returncode == 0,
            'owned': True,
            'returnCode': removed.returncode,
            'stderr': removed.stderr,
        }
    except Exception as error:
        return {'attempted': True, 'removed': False, 'owned': True, 'error': f'{type(error).__name__}: {error}'}
