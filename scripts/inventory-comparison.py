"""厳密比較を既定とし、ユーザーが確認した同一画像の輪郭差だけを許容する。"""
import hashlib

APPROVED = {
    'id': 'minecraft:oak_fence',
    'exportPngSha256': 'c7f9018b723b97c2212691e117a1a49bcf8b5a2a67c806b0b7db86c016bf44cd',
    'nativeRgbaSha256': 'a1dfbffdc2078fff47da3816abb60242ead94e7842e9b1929aa1270ee857bbf2',
    'backgroundRgbaSha256': '5a09912d69e6b5eda398c8aa1d57942cbff6bc1a332cc89e615bf52f5d6f1f90',
    'pixels': [[40, 46], [42, 47]],
}


def compare(rgba, native, background, size=64, item_id=None, export_sha=None):
    composed = bytearray()
    for i in range(0, len(rgba), 4):
        alpha = rgba[i + 3]
        composed.extend((rgba[i + k] * alpha + background[i + k] * (255 - alpha) + 127) // 255 for k in range(3))
        composed.append(255)
    errors = [abs(composed[i] - native[i]) for i in range(len(rgba)) if i % 4 != 3]
    bad = [[p % size, p // size] for p in range(size * size) if max(abs(composed[p * 4 + k] - native[p * 4 + k]) for k in range(3)) > 2]
    accepted = bool(bad) and item_id == APPROVED['id'] and export_sha == APPROVED['exportPngSha256'] and hashlib.sha256(native).hexdigest() == APPROVED['nativeRgbaSha256'] and hashlib.sha256(background).hexdigest() == APPROVED['backgroundRgbaSha256'] and bad == APPROVED['pixels']
    return {'maxChannelError': max(errors), 'meanChannelError': sum(errors) / len(errors), 'pixelsOverTolerance': len(bad), 'edgePixels': bad if accepted else [], 'edgeAcceptedPixels': len(bad) if accepted else 0, 'comparisonPassed': not bad or accepted}
