#!/usr/bin/env python3
"""Rebuild a raw partition image from a block-based OTA's
<part>.transfer.list and decompressed <part>.new.dat.

The image is sized to the highest block the transfer list touches; an
ext4 image may still be shorter than its superblock claims, which
tools/setup-workdir.sh extends before reading it.
"""
import sys


def ranges(s):
    v = [int(x) for x in s.split(',')]
    if v[0] != len(v) - 1:
        raise SystemExit(f'bad range set {s[:40]}')
    return [(v[i], v[i + 1]) for i in range(1, len(v), 2)]


def main(tl, dat, out):
    lines = open(tl).read().splitlines()
    cmds = lines[4:] if int(lines[0]) >= 2 else lines[2:]
    bs = 4096
    end = 0
    for c in cmds:
        if c.strip():
            end = max([end] + [b for _, b in ranges(c.split(' ', 1)[1])])
    with open(dat, 'rb') as src, open(out, 'wb') as dst:
        dst.truncate(end * bs)
        for c in cmds:
            if not c.strip():
                continue
            op, arg = c.split(' ', 1)
            if op == 'new':
                for a, b in ranges(arg):
                    dst.seek(a * bs)
                    n = b - a
                    while n:
                        k = min(n, 2048)
                        dst.write(src.read(k * bs))
                        n -= k
            elif op not in ('erase', 'zero'):
                raise SystemExit(f'unsupported transfer op {op}')
    print(out, end * bs)


if __name__ == '__main__':
    main(*sys.argv[1:4])
