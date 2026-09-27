#!/usr/bin/env python3
"""Fail if an APK requests a privileged permission its allowlist lacks.

With ro.control_privapp_permissions=enforce (LineageOS), PackageManager
throws during boot when a priv-app requests a signature|privileged
permission that no privapp-permissions file grants: the phone does not
come up. Protection levels are read from the ROM's own framework-res.apk.

Usage: check-privapp.py <aapt2> <apk> <framework-res.apk> <allowlist.xml>
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

PRIVILEGED = 0x10  # PermissionInfo.PROTECTION_FLAG_PRIVILEGED


def platform_levels(aapt2, fwres):
    out = subprocess.run([aapt2, 'dump', 'xmltree', '--file', 'AndroidManifest.xml', fwres],
                         check=True, capture_output=True, text=True).stdout
    levels, name, in_perm = {}, None, False
    for line in out.splitlines():
        s = line.strip()
        if s.startswith('E: '):
            name = None
            in_perm = s.startswith('E: permission ')
            continue
        if not in_perm:
            continue
        m = re.search(r':name\(0x01010003\)="([^"]+)"', s)
        if m:
            name = m.group(1)
            levels.setdefault(name, 0)
        m = re.search(r':protectionLevel\(0x01010009\)=(0x[0-9a-fA-F]+|\d+)', s)
        if m and name:
            levels[name] = int(m.group(1), 0)
    return levels


def requested(aapt2, apk):
    out = subprocess.run([aapt2, 'dump', 'permissions', apk],
                         check=True, capture_output=True, text=True).stdout
    return set(re.findall(r"uses-permission: name='([^']+)'", out))


def main(aapt2, apk, fwres, allowlist):
    levels = platform_levels(aapt2, fwres)
    if len(levels) < 500:
        raise SystemExit(f'only {len(levels)} permissions parsed from {fwres}')
    allowed = {p.get('name') for p in ET.parse(allowlist).getroot().iter('permission')}
    need = sorted(p for p in requested(aapt2, apk)
                  if levels.get(p, 0) & PRIVILEGED)
    missing = [p for p in need if p not in allowed]
    if missing:
        print('privileged permissions requested but not allowlisted:')
        for p in missing:
            print('  ' + p)
        raise SystemExit(1)
    print(f'allowlist covers all {len(need)} privileged permissions requested')


if __name__ == '__main__':
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
