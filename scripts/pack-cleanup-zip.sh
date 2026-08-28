#!/usr/bin/env bash
# Pack the cleanup-only zip: removes legacy /vendor + current /system
# joan-ims installs. Flash before joan-volte-recovery.zip when migrating.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
mkdir -p out
python3 - "$ROOT" <<'PYEOF'
import os, sys, zipfile

root = sys.argv[1]
out = os.path.join(root, 'out', 'joan-volte-cleanup.zip')
files = {
    'META-INF/com/google/android/update-binary':
        os.path.join(root, 'scripts', 'update-binary-cleanup'),
    'META-INF/com/google/android/updater-script':
        os.path.join(root, 'META-INF/com/google/android/updater-script'),
}
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for arc, p in files.items():
        data = open(p, 'rb').read()
        assert len(data) > 30, f'{arc} too small ({len(data)})'
        z.writestr(arc, data)
print('zip:', os.path.getsize(out), 'bytes ->', out)
PYEOF
