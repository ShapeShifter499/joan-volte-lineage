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

echo "== 1. native host tests (daemon no longer shipped)"
./tests/run-host-tests.sh > /tmp/joan-pack-tests.log 2>&1 || {
    cat /tmp/joan-pack-tests.log; exit 1;
}
echo "   ok: $(tail -1 /tmp/joan-pack-tests.log)"

echo "== 2. apk build"
# Clean first: stale .class files from earlier builds must never leak
# into the dex (the dead JoanSip draft shipped that way once).
rm -rf ims-service/build/obj ims-service/build/dex
OBJ=ims-service/build/obj
DEX=ims-service/build/dex
mkdir -p "$OBJ" "$DEX"
# Stubs are compiled as SOURCE so SystemApi shapes resolve, then dropped
# from dex so they never ship (the device framework provides the real
# classes). Compiling only src/ against android.jar fails: ImsService is
# hidden from the public SDK.
javac -classpath "$SDK/platforms/android-36/android.jar" \
    -d "$OBJ" $(find ims-service/stubs ims-service/src -name '*.java')
if [ "$(find "$OBJ/org" -name '*.class' | wc -l)" -eq 0 ]; then
    echo "javac produced no org.joan.ims classes"; exit 1;
fi
rm -f "$DEX"/*.dex
"$BT/d8" --release --lib "$SDK/platforms/android-36/android.jar" \
    --output "$DEX" $(find "$OBJ/org" -name '*.class')

APKDIR=ims-service/build/apk
mkdir -p "$APKDIR"
cp ims-service/AndroidManifest.xml "$APKDIR/"
python3 - "$ROOT" "$BT" <<'PYEOF'
import os, subprocess, sys, time, zipfile

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
                '--manifest', manifest,
                '-A', os.path.join(root, 'ims-service', 'assets')],
               check=True, env={**os.environ})

# Append classes.dex with a FIXED entry timestamp.
#
# ZipFile.write() takes the entry's date_time from the file's mtime, and
# d8 regenerates classes.dex on every build -- so the apk's bytes changed
# on every run even when not one source line had. That defeated the md5
# check the README asks testers to perform, and it is why the two RROs
# (plain aapt2 link + apksigner, no append step) rebuilt byte-identically
# while the app apk did not.
#
# SOURCE_DATE_EPOCH is honoured if set; otherwise 1980-01-01, which is the
# earliest timestamp the zip format can represent.
epoch = int(os.environ.get('SOURCE_DATE_EPOCH', '315532800'))
entry = zipfile.ZipInfo('classes.dex', date_time=time.gmtime(epoch)[:6])
entry.compress_type = zipfile.ZIP_DEFLATED
entry.external_attr = 0o644 << 16
with open(os.path.join(base, 'dex', 'classes.dex'), 'rb') as dexf:
    dexbytes = dexf.read()
with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as z:
    z.writestr(entry, dexbytes)

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

echo "== 3. rro overlay build (device-default IMS for com.android.phone)"
if [ -d rro ]; then
    rm -rf rro/build
    mkdir -p rro/build
    "$BT/aapt2" compile --dir rro/res -o rro/build/res.zip
    "$BT/aapt2" link -o rro/build/joan-ims-rro-unsigned.apk \
        -I "$SDK/platforms/android-36/android.jar" \
        --manifest rro/AndroidManifest.xml rro/build/res.zip \
        --auto-add-overlay
    "$BT/apksigner" sign --ks ims-service/build/keystore/joan-dev.jks \
        --ks-pass pass:joanims --key-pass pass:joanims \
        --out rro/build/joan-ims-rro.apk rro/build/joan-ims-rro-unsigned.apk
    echo "   rro: $(stat -c%s rro/build/joan-ims-rro.apk) bytes"
fi

echo "== 3b. framework rro build (config_device_volte_available)"
# Part 2 term 1 of the VoLTE admit gate: ImsManager.isVolteEnabledByPlatform()
# ANDs this framework-res bool with the carrier config. joan ships it false,
# which hides the Settings VoLTE toggle and sends every MO dial to CS. A ROM
# build sets it in the device tree instead -- see upstream/VOLTE-PLATFORM-SETUP.md.
if [ -d rro-fw ]; then
    rm -rf rro-fw/build
    mkdir -p rro-fw/build
    "$BT/aapt2" compile --dir rro-fw/res -o rro-fw/build/res.zip
    "$BT/aapt2" link -o rro-fw/build/joan-fw-volte-unsigned.apk \
        -I "$SDK/platforms/android-36/android.jar" \
        --manifest rro-fw/AndroidManifest.xml rro-fw/build/res.zip \
        --auto-add-overlay
    "$BT/apksigner" sign --ks ims-service/build/keystore/joan-dev.jks \
        --ks-pass pass:joanims --key-pass pass:joanims \
        --out rro-fw/build/joan-fw-volte.apk \
        rro-fw/build/joan-fw-volte-unsigned.apk
    echo "   rro-fw: $(stat -c%s rro-fw/build/joan-fw-volte.apk) bytes"
fi

echo "== 4. assemble recovery zip"
INSTALLED_SIZE=$(stat -c%s ims-service/build/joan-ims.apk)
[ "$INSTALLED_SIZE" -gt 5000 ] || { echo "apk too small"; exit 1; }
mkdir -p out
python3 - "$ROOT" <<'PYEOF2'
import os, sys, time, zipfile

root = sys.argv[1]
out = os.path.join(root, 'out', 'joan-volte-recovery.zip')
files = {
    'META-INF/com/google/android/update-binary':
        os.path.join(root, 'scripts', 'update-binary'),
    'META-INF/com/google/android/updater-script':
        os.path.join(root, 'META-INF/com/google/android/updater-script'),
    'app/joan-ims.apk': os.path.join(root,
        'ims-service/build/joan-ims.apk'),
    'app/joan-ims-rro.apk': os.path.join(root,
        'rro/build/joan-ims-rro.apk') if os.path.exists(
            os.path.join(root, 'rro/build/joan-ims-rro.apk')) else None,
    'app/joan-fw-volte.apk': os.path.join(root,
        'rro-fw/build/joan-fw-volte.apk') if os.path.exists(
            os.path.join(root, 'rro-fw/build/joan-fw-volte.apk')) else None,
    'etc/permissions/org.joan.ims.xml': os.path.join(root,
        'permissions/org.joan.ims.xml') if os.path.exists(
            os.path.join(root, 'permissions/org.joan.ims.xml')) else None,
    'etc/permissions/android.hardware.telephony.ims.xml': os.path.join(root,
        'permissions/android.hardware.telephony.ims.xml') if os.path.exists(
            os.path.join(root, 'permissions/android.hardware.telephony.ims.xml')) else None,
    'etc/default-permissions/org.joan.ims.xml': os.path.join(root,
        'permissions/default-permissions-org.joan.ims.xml') if os.path.exists(
            os.path.join(root,
                'permissions/default-permissions-org.joan.ims.xml')) else None,

    'apn/viettel-45204.xml': os.path.join(root, 'apn/viettel-45204.xml'),
    'scripts/merge-viettel-apns.sh': os.path.join(root,
        'scripts/merge-viettel-apns.sh'),
}
os.makedirs(os.path.dirname(out), exist_ok=True)
# Fixed entry timestamps, for the same reason as the apk's classes.dex:
# writestr() with a plain string arcname stamps the entry with
# time.time(), so this zip's bytes changed on every build. Testers are
# asked to verify it by md5, which only means something if the same
# source produces the same bytes.
epoch = int(os.environ.get('SOURCE_DATE_EPOCH', '315532800'))
stamp = time.gmtime(epoch)[:6]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for arc, p in files.items():
        if p is None:
            continue
        data = open(p, 'rb').read()
        assert len(data) > 30, f'{arc} too small ({len(data)})'
        entry = zipfile.ZipInfo(arc, date_time=stamp)
        entry.compress_type = zipfile.ZIP_DEFLATED
        # update-binary must stay executable inside the zip.
        entry.external_attr = ((0o755 if arc.endswith(('update-binary', '.sh'))
                                else 0o644) << 16)
        z.writestr(entry, data)
print('zip:', os.path.getsize(out), 'bytes ->', out)
PYEOF2

echo "== done: out/joan-volte-recovery.zip"
