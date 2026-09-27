#!/usr/bin/env python3
"""Check the APNs make-apns.py adds to a LineageOS vendor/apn.

For every SIM the Pixel data or the list itself knows (each MCC/MNC, and
each MVNO key of either), with Android 15's rules for which rows a SIM gets
(make-apns.py's effective()):
- no SIM's APNs come from another level than before (MVNO rows or MCC/MNC
  rows), and none loses a row;
- every row added has only ims, xcap and emergency types, is hidden from the
  APN picker, and gives some SIM a type it lacked, or is an IWLAN-only copy;
- IMS and emergency APNs added allow IWLAN;
- the list as LineageOS assembles it (make-apns.sh) still validates against
  its schema, when xmllint is there.

Usage: check-apns.py <lineage-pixel-apns.xml> <vendor/apn dir>
The directory is not changed: the check works on a copy.
"""
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location(
    'make_apns', os.path.join(HERE, '..', 'tools', 'make-apns.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def main(pixel_path, apn_dir):
    fails = []
    with tempfile.TemporaryDirectory() as tmp:
        work = os.path.join(tmp, 'apn')
        shutil.copytree(apn_dir, work, ignore=shutil.ignore_patterns('.git', m.OUR_FILE))
        out = subprocess.run([sys.executable, os.path.join(HERE, '..', 'tools', 'make-apns.py'),
                              pixel_path, '--vendor-apn', work],
                             check=True, capture_output=True, text=True).stdout
        rom = [r for f in sorted(os.listdir(work))
               if f.endswith('.xml') and f != m.OUR_FILE
               for r in m.rows_of(os.path.join(work, f))]
        added = m.rows_of(os.path.join(work, m.OUR_FILE))
        if os.path.exists(os.path.join(work, 'make-apns.sh')) and shutil.which('xmllint'):
            files = sorted(f for f in os.listdir(work) if f.endswith('.xml'))
            assembled = subprocess.run(['bash', 'make-apns.sh'] + files, cwd=work, check=True,
                                       capture_output=True, text=True).stdout
            v = subprocess.run(['xmllint', '--noout', '--schema', 'apns-conf.xsd', '-'],
                               cwd=work, input=assembled, capture_output=True, text=True)
            if v.returncode != 0:
                fails.append('assembled list fails the schema: ' + v.stderr.strip()[-300:])

    pixel = m.rows_of(pixel_path)
    before, _ = m.index(rom)
    after, _ = m.index(rom + added)
    pix, _ = m.index(pixel)

    for r in added:
        t = m.types(r)
        if not t or not t <= set(m.TYPES):
            fails.append(f'added row with other types: {r}')
        if r.get('user_visible') != 'false':
            fails.append(f'added row shown in the APN picker: {r}')
        if t & set(m.WIFI_TYPES) and not m.allows_iwlan(r):
            fails.append(f'added IMS/emergency row without IWLAN: {r}')
        if 'carrier_id' in r:
            fails.append(f'added row keyed by carrier id: {r}')

    sims = set()
    for idx in (pix, before):
        for p, P in idx.items():
            sims.add((p, None))
            sims.update((p, k) for k in P['mvno'])

    def level(idx, p, k):
        R = idx.get(p)
        if R is None:
            return 'none'
        if k and any(m.mvno_covers(rk, k) for rk in R['mvno']):
            return 'mvno'
        return 'mno'

    gained = 0
    for p, k in sorted(sims, key=lambda s: (s[0], s[1] or ('', ''))):
        b, a = m.effective(before, p, k), m.effective(after, p, k)
        lb, la = level(before, p, k), level(after, p, k)
        if lb != la and lb != 'none':
            fails.append(f'{p} {k}: APNs now come from {la} rows, not {lb}')
        ids = {id(r) for r in a}
        lost = [r for r in b if id(r) not in ids]
        if lost:
            fails.append(f'{p} {k}: lost {len(lost)} rows')
        new = [r for r in a if id(r) not in {id(x) for x in b}]
        for r in new:
            copy = r.get('bearer_bitmask') == m.IWLAN and r.get('carrier', '').endswith(' Wi-Fi')
            if not copy and all(m.has(b, t) for t in m.types(r)):
                fails.append(f'{p} {k}: added {r.get("apn")} ({r.get("type")}) it already had')
        gained += bool(new)

    print(out.strip().splitlines()[0])
    for f in fails[:20]:
        print('FAIL:', f)
    if fails:
        raise SystemExit(f'{len(fails)} failures')
    print(f'{len(sims)} SIMs checked, {gained} gain APNs, {len(added)} rows added: OK')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
