"""無agent InventoryScreen比較に使う限定的なRGBA検査。"""
import hashlib


def validate_capture_hashes(expected_screen, actual_screen, expected_slot, actual_slot):
    if expected_screen != actual_screen:
        raise ValueError('F2画像のhashがcapture記録と一致しません')
    if expected_slot != actual_slot:
        raise ValueError('slot RGBAのhashがcapture記録と一致しません')


def comparison_record(capture, export_png_sha256, empty_background, metrics):
    return {
        'id': capture['id'],
        'sample': capture['sample'],
        'exportPngSha256': export_png_sha256,
        'nativeScreenSha256': capture['sha256'],
        'emptySlotRgbaSha256': hashlib.sha256(empty_background).hexdigest(),
        **metrics,
    }


def compare(item_id, rgba, background, native, size=64):
    if item_id not in {'minecraft:white_stained_glass', 'minecraft:slime_block'}:
        raise ValueError('対象外の素材IDです')
    expected_length = size * size * 4
    if any(len(pixels) != expected_length for pixels in (rgba, background, native)):
        raise ValueError('64px RGBA画像の長さが不正です')
    if not any(rgba[i + 3] > 0 for i in range(0, expected_length, 4)):
        raise ValueError('元PNGに可視alpha画素がありません')
    if not all(background[i + 3] == 255 and native[i + 3] == 255 for i in range(0, expected_length, 4)):
        raise ValueError('empty slotとF2画像は不透明である必要があります')

    errors = []
    fractional_alpha_pixels = 0
    nonzero_alpha_pixels = 0
    pixels_over_tolerance = 0
    for i in range(0, expected_length, 4):
        alpha = rgba[i + 3]
        nonzero_alpha_pixels += alpha > 0
        fractional_alpha_pixels += 0 < alpha < 255
        pixel_errors = [
            abs((rgba[i + channel] * alpha + background[i + channel] * (255 - alpha) + 127) // 255 - native[i + channel])
            for channel in range(3)
        ]
        errors.extend(pixel_errors)
        pixels_over_tolerance += max(pixel_errors) > 2

    return {
        'nonzeroAlphaPixels': nonzero_alpha_pixels,
        'fractionalAlphaPixels': fractional_alpha_pixels,
        'maxChannelError': max(errors),
        'meanChannelError': sum(errors) / len(errors),
        'pixelsOverTolerance': pixels_over_tolerance,
        'passed': fractional_alpha_pixels > 0 and pixels_over_tolerance == 0,
    }
