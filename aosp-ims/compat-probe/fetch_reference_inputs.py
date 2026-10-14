#!/usr/bin/env python3
"""Fetch only the exact public framework inputs needed by this local proof.

No existing bytes are overwritten. A checksum mismatch fails before any write.
"""
import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1] / 'work/shim-proof'
BASE = 'https://raw.githubusercontent.com/LineageOS/android_frameworks_base/5c05c66e872c907a925f633b69f11661c59e7ad0/'
INPUTS = [
    ('platform-reference/a15-BarringInfo.java',
     'telephony/java/android/telephony/BarringInfo.java',
     '17970a81407381d409e46aef41c019fcfd453d619b28d305f8d44007bef33710'),
    ('platform-chain-01/a15-system-current.txt', 'core/api/system-current.txt',
     '51e3846f4c566cff2930536d3d4a9efb12b8e964b07a1826c8770093e327ca1b'),
]


def main():
    for rel, remote, expected in INPUTS:
        dest = ROOT / rel
        present = dest.exists()
        if present:
            data = dest.read_bytes()
        else:
            with urllib.request.urlopen(BASE + remote, timeout=60) as response:
                data = response.read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError(f'Wrong bytes: {dest}')
        if not present:
            dest.parent.mkdir(parents=True, exist_ok=True)
            with dest.open('xb') as stream:
                stream.write(data)
        print(('verified existing: ' if present else 'fetched and verified: ') + rel)


if __name__ == '__main__':
    main()
