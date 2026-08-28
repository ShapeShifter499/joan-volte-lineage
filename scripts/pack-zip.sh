#!/usr/bin/env bash
# Pack the flashable zip for joan-volte-lineage.
# Artifacts: out/joan-volte-recovery.zip (recovery-flashable)
# Requires: aarch64-linux-gnu-gcc, Android SDK (javac/d8/aapt2/apksigner),
# python3 for zip assembly. Idempotent; rebuilds everything.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
OUT=$ROOT/out
SDK=${ANDROID_SDK:-$HOME/Android/Sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort | tail -1)

echo "== 1. native device binary"
make -C native device >/dev/null 2>&1 || {
    echo "native build failed"; exit 1;
}
echo "   $(ls -l native/build/joan-ims-aarch64 | awk '{print $5, $9}')"

echo "== 2. host unit tests (sanity gate)"
./tests/run-host-tests.sh > /tmp/joan-pack-tests.log 2>&1 || {
    cat /tmp/joan-pack-tests.log; exit 1;
}
echo "   ok: $(tail -1 /tmp/joan-pack-tests.log)"

echo "== 3. apk build"
# Clean first: stale .class files from earlier builds must never leak
# into the dex (the dead JoanSip draft shipped that way once).
rm -rf ims-service/build/obj ims-service/build/dex
OBJ=ims-service/build/obj
DEX=ims-service/build/dex
mkdir -p "$OBJ" "$DEX"
javac -classpath "$SDK/platforms/android-36/android.jar:$ROOT/ims-service/stubs" \
    -d "$OBJ" $(find ims-service/src -name '*.java')
if [ "$(find "$OBJ" -name '*.class' | wc -l)" -eq 0 ]; then
    echo "javac produced no classes"; exit 1;
fi
rm -f "$DEX"/*.dex
"$BT/d8" --release --lib "$SDK/platforms/android-36/android.jar" \
    --output "$DEX" $(find "$OBJ" -name '*.class')

APKDIR=ims-service/build/apk
mkdir -p "$APKDIR"
cp ims-service/AndroidManifest.xml "$APKDIR/"
python3 - "$ROOT" "$BT" <<'PYEOF'
import os, subprocess, sys, zipfile

root, bt = sys.argv[1], sys.argv[2]
base = os.path.join(root, 'ims-service', 'build')
manifest = os.path.join(base, 'apk', 'AndroidManifest.xml')
unsigned = os.path.join(base, 'joan-ims-unsigned.apk')
signed = os.path.join(base, 'joan-ims.apk')

subprocess.run([os.path.join(bt, 'aapt2'), 'link',
                '-o', unsigned,
                '-I', os.path.join(os.path.expanduser('~'),
                                   'Android/Sdk/platforms/android-36',
                                   'android.jar'),
                '--manifest', manifest], check=True,
               env={**os.environ})

with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(base, 'dex', 'classes.dex'), 'classes.dex')

ks = os.path.join(root, 'ims-service', 'build', 'keystore', 'joan-dev.jks')
if not os.path.exists(ks):
    subprocess.run(['keytool', '-genkeypair', '-keystore', ks,
                    '-alias', 'joan', '-keyalg', 'RSA', '-keysize', '2048',
                    '-validity', '10950', '-storepass', 'joanims',
                    '-keypass', 'joanims',
                    '-dname', 'CN=joan-ims-dev,O=joan,C=US'], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

subprocess.run([os.path.join(bt, 'apksigner'), 'sign',
                '--ks', ks, '--ks-pass', 'pass:joanims',
                '--key-pass', 'pass:joanims',
                '--out', signed, unsigned], check=True)

print('apk:', os.path.getsize(signed), 'bytes ->', signed)
PYEOF

echo "== 4. assemble recovery zip"
INSTALLED_SIZE=$(stat -c%s ims-service/build/joan-ims.apk)
[ "$INSTALLED_SIZE" -gt 5000 ] || { echo "apk too small"; exit 1; }
mkdir -p root/system/bin
cp native/build/joan-ims-aarch64 root/system/bin/joan-ims
mkdir -p out
python3 - "$ROOT" <<'PYEOF2'
import os, sys, zipfile

root = sys.argv[1]
out = os.path.join(root, 'out', 'joan-volte-recovery.zip')
files = {
    'META-INF/com/google/android/update-binary':
        os.path.join(root, 'scripts', 'update-binary'),
    'META-INF/com/google/android/updater-script':
        os.path.join(root, 'META-INF/com/google/android/updater-script'),
    'system/bin/joan-ims':
        os.path.join(root, 'root/system/bin/joan-ims'),
    'system/etc/init/joan-ims.rc':
        os.path.join(root, 'root/system/etc/init/joan-ims.rc'),
    'app/joan-ims.apk': os.path.join(root,
        'ims-service/build/joan-ims.apk'),
    'etc/permissions/org.joan.ims.xml': os.path.join(root,
        'permissions/org.joan.ims.xml') if os.path.exists(
            os.path.join(root, 'permissions/org.joan.ims.xml')) else None,
}
os.makedirs(os.path.dirname(out), exist_ok=True)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for arc, p in files.items():
        if p is None:
            continue
        data = open(p, 'rb').read()
        assert len(data) > 30, f'{arc} too small ({len(data)})'
        z.writestr(arc, data)
print('zip:', os.path.getsize(out), 'bytes ->', out)
PYEOF2

echo "== done: out/joan-volte-recovery.zip"
