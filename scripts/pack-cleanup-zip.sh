#!/usr/bin/env bash
# Pack the uninstaller zip: removes JoanIms and leftover joan-ims files.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
mkdir -p out
python3 - "$ROOT" <<'PYEOF'
import os, sys, time, zipfile

root = sys.argv[1]
out = os.path.join(root, 'out', 'joan-volte-uninstall.zip')
files = {
    'META-INF/com/google/android/update-binary':
        os.path.join(root, 'scripts', 'update-binary-cleanup'),
    'META-INF/com/google/android/updater-script':
        os.path.join(root, 'META-INF/com/google/android/updater-script'),
}
# Fixed entry timestamps, as in pack-zip.sh, so the same source gives
# the same bytes and an md5 means something.
epoch = int(os.environ.get('SOURCE_DATE_EPOCH', '315532800'))
stamp = time.gmtime(epoch)[:6]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for arc, p in files.items():
        data = open(p, 'rb').read()
        assert len(data) > 30, f'{arc} too small ({len(data)})'
        entry = zipfile.ZipInfo(arc, date_time=stamp)
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = ((0o755 if arc.endswith('update-binary')
                                else 0o644) << 16)
        z.writestr(entry, data)
print('zip:', os.path.getsize(out), 'bytes ->', out)
PYEOF
