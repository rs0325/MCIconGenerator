"""運用鍵と回復鍵の公開DERだけから、正規化した固定2鍵の信頼集合を作る。"""
from pathlib import Path
import argparse

MAGIC = b'MCITRS1\n'
PREFIX = bytes.fromhex('302a300506032b6570032100')
SIZE = 96


def public_key(data):
    if len(data) != 44 or not data.startswith(PREFIX):
        raise ValueError('正規Ed25519 X.509公開鍵DERが必要です')
    # RFC 8032 §5.1.3の公開点の復号条件。標準整数演算で検査し、署名処理はJCAに任せる。
    p = 2**255 - 19
    encoded = int.from_bytes(data[12:], 'little')
    y, sign = encoded & (2**255 - 1), encoded >> 255
    if y >= p:
        raise ValueError('Ed25519公開点が不正です')
    d = -121665 * pow(121666, -1, p) % p
    square = (y*y - 1) * pow((d*y*y + 1) % p, -1, p) % p
    if (square == 0 and sign) or pow(square, (p - 1)//2, p) == p - 1:
        raise ValueError('Ed25519公開点が不正です')
    return data


def validate(data):
    if len(data) != SIZE or data[:8] != MAGIC:
        raise ValueError('固定2鍵の信頼集合形式が必要です。旧単一鍵DERは未対応です')
    first, second = public_key(data[8:52]), public_key(data[52:96])
    if first >= second:
        raise ValueError('信頼集合の鍵が重複または正規順ではありません')
    return data


def read(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size != SIZE:
        raise ValueError('96bytesの通常信頼集合fileが必要です')
    return validate(path.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('operational-key', 'recovery-key', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    keys = []
    for path in (args.operational_key, args.recovery_key):
        if not path.is_file() or path.is_symlink() or path.stat().st_size != 44:
            raise ValueError('44bytesの公開鍵DER通常fileが必要です')
        keys.append(public_key(path.read_bytes()))
    data = validate(MAGIC + b''.join(sorted(keys)))
    with args.output.open('xb') as stream:
        stream.write(data)


if __name__ == '__main__':
    main()
