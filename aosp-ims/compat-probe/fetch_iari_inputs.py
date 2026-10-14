#!/usr/bin/env python3
"""Fetch the exact IARI producer-chain inputs for the frameworks backport.

Fail-closed: an existing file is re-verified against its pinned digest and a
mismatch aborts before any write. Nothing already on disk is overwritten.

Provenance is deliberate and must stay this way:
  - Android 15 targets are LineageOS 22.2 FORKS at the commits pinned in
    aosp-ims/upstream.lock, because that is what the ROM actually builds.
  - Android 17 references are vanilla AOSP at android-17.0.0_r1, the release
    the unchanged IMS stack comes from.
"""
import base64
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1] / 'work/shim-proof' / 'iari-chain-01'

LOS_BASE = ('https://raw.githubusercontent.com/LineageOS/'
            'android_frameworks_opt_telephony/'
            '6feb405d62073d98593e60cfc3f340874ba4594b/')
LOS_FB_BASE = ('https://raw.githubusercontent.com/LineageOS/'
               'android_frameworks_base/'
               '5c05c66e872c907a925f633b69f11661c59e7ad0/')
AOSP = 'https://android.googlesource.com/platform/{project}/+/android-17.0.0_r1/{path}?format=TEXT'

# (relative name, url, expected sha256). Digests are filled in by the manifest
# this script writes, but a pre-existing file must match its recorded digest.
INPUTS = [
    # --- Android 15 producers, from the LineageOS 22.2 fork ---
    ('a15-IccRecords.java',
     LOS_BASE + 'src/java/com/android/internal/telephony/uicc/IccRecords.java'),
    ('a15-IsimUiccRecords.java',
     LOS_BASE + 'src/java/com/android/internal/telephony/uicc/IsimUiccRecords.java'),
    ('a15-SIMRecords.java',
     LOS_BASE + 'src/java/com/android/internal/telephony/uicc/SIMRecords.java'),
    # --- Android 15 AIDL surface. IPhoneSubInfo.aidl lives in frameworks/base
    # (filegroup framework-telephony-common-shared-srcs), NOT in
    # frameworks/opt/telephony, which ships only 3 AIDLs in A15. ---
    ('a15-IPhoneSubInfo.aidl',
     LOS_FB_BASE + 'telephony/java/com/android/internal/telephony/IPhoneSubInfo.aidl'),
    ('a15-UiccProfile.java',
     LOS_BASE + 'src/java/com/android/internal/telephony/uicc/UiccProfile.java'),
    # --- Android 15 API surface + system API manifest ---
    ('a15-system-current.txt',
     LOS_FB_BASE + 'core/api/system-current.txt',
     '51e3846f4c566cff2930536d3d4a9efb12b8e964b07a1826c8770093e327ca1b'),
    # --- Android 17 references, vanilla AOSP ---
    ('a17-IPhoneSubInfo.aidl',
     AOSP.format(project='frameworks/base',
                 path='telephony/java/com/android/internal/telephony/IPhoneSubInfo.aidl')),
]


def fetch(url):
    with urllib.request.urlopen(url, timeout=90) as response:
        raw = response.read()
    # googlesource ?format=TEXT is base64; plain raw.githubusercontent is not.
    if 'format=TEXT' in url:
        return base64.b64decode(raw)
    return raw


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    manifest_path = ROOT / 'manifest.json'
    recorded = {}
    if manifest_path.exists():
        for entry in json.loads(manifest_path.read_text()):
            recorded[entry['name']] = entry.get('sha256')

    entries = []
    for item in INPUTS:
        name, url = item[0], item[1]
        expected = item[2] if len(item) > 2 else recorded.get(name)
        dest = ROOT / name
        if dest.exists():
            data = dest.read_bytes()
            state = 'verified existing'
        else:
            data = fetch(url)
            state = 'fetched'
        digest = hashlib.sha256(data).hexdigest()
        if expected is not None and digest != expected:
            raise RuntimeError(f'checksum mismatch for {name}: {digest} != {expected}')
        if not dest.exists():
            with dest.open('xb') as stream:
                stream.write(data)
            state = 'fetched and verified'
        entries.append({'name': name, 'url': url, 'bytes': len(data),
                        'sha256': digest})
        print(f'{state}: {name} ({len(data)} bytes)')

    manifest_path.write_text(json.dumps(entries, indent=2) + '\n')
    print(f'\nmanifest: {manifest_path}')


if __name__ == '__main__':
    main()
