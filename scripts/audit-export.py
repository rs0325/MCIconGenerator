#!/usr/bin/env python3
"""Audit generated files and manifest counts; keep proprietary inputs local."""
import argparse
import hashlib
import json
import struct
import zlib
import zipfile
from pathlib import Path


def rgba_png(data):
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('PNG signature')
    pos = 8
    compressed = bytearray()
    header = None
    ended = False
    while pos + 12 <= len(data):
        length = struct.unpack_from('>I', data, pos)[0]
        chunk = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        crc = data[pos + 8 + length:pos + 12 + length]
        if len(body) != length or len(crc) != 4 or zlib.crc32(chunk + body) != struct.unpack('>I', crc)[0]:
            raise ValueError('PNG chunk CRC')
        if chunk == b'IHDR':
            header = struct.unpack('>IIBBBBB', body)
        if chunk == b'IDAT':
            compressed.extend(body)
        pos += length + 12
        if chunk == b'IEND':
            ended = True
            break
    if not ended or pos != len(data) or header is None:
        raise ValueError('PNG end/header')
    width, height, bits, color, compression, filtering, interlace = header
    if bits != 8 or color != 6 or compression or filtering or interlace:
        raise ValueError('Expected noninterlaced RGBA8')
    if width * height > 16 * 1024 * 1024:
        raise ValueError('Pixel limit')
    stride = width * 4
    raw = zlib.decompress(compressed)
    if len(raw) != height * (stride + 1):
        raise ValueError('Decoded byte count')
    pixels = bytearray()
    previous = bytearray(stride)
    for y in range(height):
        offset = y * (stride + 1)
        method = raw[offset]
        row = bytearray(raw[offset + 1:offset + 1 + stride])
        if method == 0:
            pixels.extend(row)
            previous = row
            continue
        for x in range(stride):
            a = row[x - 4] if x >= 4 else 0
            b = previous[x]
            c = previous[x - 4] if x >= 4 else 0
            if method == 0:
                prediction = 0
            elif method == 1:
                prediction = a
            elif method == 2:
                prediction = b
            elif method == 3:
                prediction = (a + b) // 2
            elif method == 4:
                p = a + b - c
                distances = [abs(p - a), abs(p - b), abs(p - c)]
                prediction = [a, b, c][distances.index(min(distances))]
            else:
                raise ValueError('PNG filter')
            row[x] = (row[x] + prediction) & 255
        pixels.extend(row)
        previous = row
    return width, height, pixels


def audit(manifest_path, source_jar=None, compare=None, require_all=False):
    manifest = json.loads(manifest_path.read_text())
    failures = []
    compared = 0
    comparison_entries = {}
    if compare:
        previous=json.loads(compare.read_text())
        if previous.get("version")!=manifest.get("version") or previous.get("mode")!=manifest.get("mode"):
            failures.append("Comparison version or mode differs")
        comparison_entries={e["id"]:e for e in previous["entries"] if e["status"]=="success"}
    ids = [e['id'] for e in manifest['entries']]
    if ids != sorted(set(ids)):
        failures.append('Unsorted or duplicate IDs')
    successes = sum(e['status'] == 'success' for e in manifest['entries'])
    if successes != manifest['counts']['succeeded'] or len(ids) - successes != manifest['counts']['failed']:
        failures.append('Manifest counts disagree')
    if manifest['complete'] and manifest['counts']['failed']:
        failures.append('complete=true with failures')
    paths = [e['path'] for e in manifest['entries']]
    if len(paths) != len(set(paths)):
        failures.append('Duplicate output paths')
    registry_checked = False
    if manifest['mode'] == 'icons':
        catalog = manifest['catalog']
        catalog_ids = [(e['id'], e['kind'], e['status']) for e in catalog]
        if len(catalog_ids) != len(set(catalog_ids)):
            failures.append('Duplicate catalog IDs')
        excluded = [e for e in catalog if e['status'] == 'no_item_form']
        if len(excluded) != manifest['counts']['noItemForm'] or any(e['kind'] != 'block' or e['blocks'] for e in excluded):
            failures.append('no_item_form records disagree')
        available_ids = [e['id'] for e in catalog if e['status'] == 'available']
        available = set(available_ids)
        if len(available) != len(available_ids):
            failures.append('Duplicate registered item IDs')
        if not set(ids).issubset(available):
            failures.append('Output ID is absent from registry')
        if require_all:
            registry_checked = set(ids) == available
            if not registry_checked:
                failures.append('Full export differs from registry population')
    source = zipfile.ZipFile(source_jar) if source_jar else None
    alpha_pixels = 0
    transparent_images = 0
    for entry in manifest['entries']:
        if entry['status'] != 'success':
            failures.append(entry['id'] + ': ' + str(entry.get('code')))
            continue
        try:
            file = (manifest_path.parent / entry['path']).resolve()
            if not file.is_relative_to(manifest_path.parent.resolve()):
                raise ValueError('Path escapes output')
            data = file.read_bytes()
            if hashlib.sha256(data).hexdigest() != entry['sha256']:
                raise ValueError('SHA-256 mismatch')
            for path, digest in entry.get('auxiliary', {}).items():
                sidecar = (manifest_path.parent / path).resolve()
                if not sidecar.is_relative_to(manifest_path.parent.resolve()):
                    raise ValueError('Sidecar escapes output')
                if hashlib.sha256(sidecar.read_bytes()).hexdigest() != digest:
                    raise ValueError('Sidecar SHA-256 mismatch')
                if source and sidecar.read_bytes() != source.read(path):
                    raise ValueError('Source sidecar differs')
            if manifest['mode'] == 'raw' and source and data != source.read(entry['path']):
                raise ValueError('Original bytes differ')
            if manifest['mode'] == 'icons':
                width, height, rgba = rgba_png(data)
                if width != manifest['settings']['size'] or height != width:
                    raise ValueError('Canvas size differs')
                alpha = rgba[3::4]
                if any(rgba[i] or rgba[i+1] or rgba[i+2] for i in range(0, len(rgba), 4) if not rgba[i+3]):
                    raise ValueError('Hidden RGB in transparent pixel')
                if not any(alpha):
                    raise ValueError('Empty output')
                if 0 in alpha:
                    transparent_images += 1
                alpha_pixels += sum(0 < a < 255 for a in alpha)
                if compare:
                    previous_entry=comparison_entries.get(entry["id"])
                    if not previous_entry or previous_entry["path"]!=entry["path"]:
                        raise ValueError("Comparison manifest has no matching successful entry")
                    other = (compare.parent / previous_entry["path"]).resolve()
                    if not other.is_relative_to(compare.parent.resolve()):
                        raise ValueError('Comparison path escapes output')
                    other_data=other.read_bytes()
                    if hashlib.sha256(other_data).hexdigest()!=previous_entry["sha256"]:
                        raise ValueError("Comparison PNG SHA-256 mismatch")
                    other_width, other_height, other_rgba = rgba_png(other_data)
                    if (other_width, other_height) != (width, height) or other_rgba != rgba:
                        raise ValueError('Decoded RGBA differs from comparison export')
                    compared += 1
        except Exception as error:
            failures.append(entry['id'] + ': ' + str(error))
    if source:
        source.close()
    return {'version': manifest['version'], 'mode': manifest['mode'], 'audited': successes,
            'registryPopulationVerified': registry_checked, 'transparentImages': transparent_images, 'fractionalAlphaPixels': alpha_pixels,
            'failures': failures, 'compared': compared,
            'comparisonVerified': bool(compare) and not failures and compared == successes, 'canonicalVerified': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--require-all', action='store_true')
    parser.add_argument('--source-jar', type=Path)
    parser.add_argument('--compare', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    result = audit(args.manifest, args.source_jar, args.compare, args.require_all)
    text = json.dumps(result, indent=2)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text + '\n')
    print(text)
    raise SystemExit(bool(result['failures']))
