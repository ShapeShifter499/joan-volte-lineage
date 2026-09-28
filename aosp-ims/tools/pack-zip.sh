#!/bin/bash
# Assemble the flashable install and uninstall zips for the AOSP IMS stack.
#
# The installer is joan's installer v8 rewritten by make-installer.py (the
# one that flashes across LineageOS-based ROMs), so the zip layout is
# joan's: app/, etc/, apn/, scripts/.
#
# Three zips: -fresh (phones without joan; refuses one that has it),
# -migrate-from-joan (removes joan's stack, then installs), -uninstall.
# Run after build-apk.sh. Output: $WORK/out/zip/
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$HERE/.." && pwd)
WORK=${WORK:-$HERE/work}
VERSION_NAME=${VERSION_NAME:-17.0.0_r1-a15-alpha1}
APK=$WORK/out/apk
OUT=$WORK/out/zip
mkdir -p "$OUT"
python3 "$HERE/tools/make-installer.py" "$ROOT/scripts" "$OUT/scripts"

python3 - "$ROOT" "$HERE" "$APK" "$OUT" "$VERSION_NAME" <<'EOF'
import os, sys, time, zipfile
root, here, apk, out, version = sys.argv[1:6]
install = {
    'META-INF/com/google/android/update-binary': f'{out}/scripts/update-binary',
    'META-INF/com/google/android/updater-script': f'{root}/META-INF/com/google/android/updater-script',
    'app/ImsStack.apk': f'{apk}/ImsStack.apk',
    'app/ImsStackPhoneOverlay.apk': f'{apk}/ImsStackPhoneOverlay.apk',
    'app/ImsStackFrameworkOverlay.apk': f'{apk}/ImsStackFrameworkOverlay.apk',
    'etc/permissions/com.android.imsstack.xml': f'{here}/permissions/privapp-permissions-com.android.imsstack.xml',
    'etc/permissions/android.hardware.telephony.ims.xml': f'{root}/permissions/android.hardware.telephony.ims.xml',
    'etc/default-permissions/com.android.imsstack.xml': f'{here}/permissions/default-permissions-com.android.imsstack.xml',
    'etc/sysconfig/com.android.imsstack.xml': f'{here}/permissions/sysconfig-com.android.imsstack.xml',
    # VoWiFi: AOSP IWLAN (ePDG tunnel) and QNS (LTE <-> Wi-Fi choice).
    'app/Iwlan.apk': f'{apk}/Iwlan.apk',
    'app/QualifiedNetworksService.apk': f'{apk}/QualifiedNetworksService.apk',
    'etc/permissions/com.google.android.iwlan.xml': f'{here}/permissions/privapp-permissions-com.google.android.iwlan.xml',
    'etc/permissions/com.android.telephony.qns.xml': f'{here}/permissions/privapp-permissions-com.android.telephony.qns.xml',
    'etc/sysconfig/com.google.android.iwlan.xml': f'{here}/permissions/sysconfig-com.google.android.iwlan.xml',
    'apn/viettel-45204.xml': f'{root}/apn/viettel-45204.xml',
    'scripts/merge-viettel-apns.sh': f'{root}/scripts/merge-viettel-apns.sh',
    'grant-permissions.sh': f'{here}/zip/grant-permissions.sh',
    # Keeps the stack across LineageOS updates (backuptool's addon.d).
    'addon.d/60-aosp-ims.sh': f'{here}/zip/addon.d/60-aosp-ims.sh',
}
migrate = dict(install)
migrate['META-INF/com/google/android/update-binary'] = f'{out}/scripts/update-binary-migrate'
uninstall = {
    'META-INF/com/google/android/update-binary': f'{out}/scripts/update-binary-cleanup',
    'META-INF/com/google/android/updater-script': f'{root}/META-INF/com/google/android/updater-script',
}
# Fixed timestamps: the same sources give the same bytes, so testers can
# compare checksums.
stamp = time.gmtime(int(os.environ.get('SOURCE_DATE_EPOCH', '315532800')))[:6]
for name, files in ((f'aosp-ims-{version}-fresh.zip', install),
                    (f'aosp-ims-{version}-migrate-from-joan.zip', migrate),
                    (f'aosp-ims-{version}-uninstall.zip', uninstall)):
    path = os.path.join(out, name)
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        for arc, src in files.items():
            data = open(src, 'rb').read()
            assert len(data) > 30, f'{arc} too small'
            e = zipfile.ZipInfo(arc, date_time=stamp)
            e.compress_type = zipfile.ZIP_DEFLATED
            e.external_attr = (0o755 if arc.endswith(('update-binary', '.sh')) else 0o644) << 16
            z.writestr(e, data)
    print(f'{path}: {os.path.getsize(path)} bytes')
EOF
