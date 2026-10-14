#!/bin/bash
# Recover the pinned nightly's framework classes and platform libraries from
# the published repacked ROM.
#
# Why this exists: upstream.lock pins the official joan nightly
# lineage-22.2-20260920, which has aged off the current LineageOS mirrors, so
# tools/setup-workdir.sh step 4 cannot fetch it. The repack
# (aosp-ims/tools/repack-rom.sh) unpacks that same official OTA and only ADDS
# files to the system/product images; framework.jar, framework-res.apk and
# /system/lib64/*.so are byte-identical to the official nightly. So the
# published repack is an equivalent source for the build's Java classpath and
# native link inputs, and it keeps the pinned baseline exact.
#
# The OTA URL in upstream.lock is NOT changed: this is an offline recovery of
# the same pinned nightly's files, not a rebaseline.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$HERE/work}
ROM_URL=https://github.com/ShapeShifter499/joan-volte-lineage/releases/download/aosp-ims-17.0.0_r1-a15-alpha1/lineage-22.2-20260920-UNOFFICIAL-AOSPIMS-alpha1-joan.zip
SUM_URL=https://github.com/ShapeShifter499/joan-volte-lineage/releases/download/aosp-ims-17.0.0_r1-a15-alpha1/SHA256SUMS
mkdir -p "$WORK/rom" "$WORK/fwcls" "$WORK/platlibs"
cd "$WORK/rom"

ROM=$(basename "$ROM_URL")
if [ ! -s "$ROM" ]; then
    echo "fetching repacked ROM (1.05 GiB)"
    curl -fL --retry 3 -o "$ROM" "$ROM_URL"
fi

# Verify against the release's own published checksums, not a local copy.
curl -fsSL -o SHA256SUMS "$SUM_URL"
want=$(grep -F "$ROM" SHA256SUMS | awk '{print $1}')
[ -n "$want" ] || { echo "no published checksum for $ROM"; exit 1; }
got=$(sha256sum "$ROM" | awk '{print $1}')
[ "$want" = "$got" ] || { echo "sha mismatch: want $want got $got"; exit 1; }
echo "$ROM: sha256 verified ($got)"

# Block OTA: *.new.dat.br + transfer.list -> brotli + sdat2img.
for part in system; do
    if [ ! -s "$part.img" ]; then
        unzip -o -q "$ROM" "$part.new.dat.br" "$part.transfer.list" -d .
        brotli -d -f "$part.new.dat.br" -o "$part.new.dat"
        python3 "$HERE/tools/sdat2img.py" "$part.transfer.list" \
            "$part.new.dat" "$part.img" >/dev/null
        rm -f "$part.new.dat.br" "$part.new.dat"
        # sdat2img stops at the last block in transfer.list; the ROM's
        # dynamic-partition ops need the image at its full superblock size.
        python3 - "$part.img" <<'EOF'
import os, struct, sys
p = sys.argv[1]
sb = open(p, 'rb').read(2048)[1024:]
n = struct.unpack('<I', sb[4:8])[0] | struct.unpack('<I', sb[0x150:0x154])[0] << 32
need = n * (1024 << struct.unpack('<I', sb[24:28])[0])
if os.path.getsize(p) < need:
    os.truncate(p, need)
EOF
    fi
    echo "$part.img: $(stat -c%s "$part.img") bytes"
done

dump() { debugfs -R "dump $1 $2" rom/system.img >/dev/null 2>&1
         [ -s "$2" ] || { echo "cannot read $1"; exit 1; }; }
cd "$WORK"
for j in framework telephony-common ims-common framework-location framework-connectivity-b; do
    [ -s "fwcls/$j.jar" ] && { echo "fwcls/$j.jar: present"; continue; }
    dump "/system/framework/$j.jar" "rom/$j.jar"
    java -Xmx4g -cp "$(ls tools/d2j/*.jar | tr '\n' ':')" \
        com.googlecode.dex2jar.tools.Dex2jarCmd -f -n -o "fwcls/$j.jar" "rom/$j.jar" >/dev/null
    echo "fwcls/$j.jar: ok"
done
[ -s rom/framework-res.apk ] || dump /system/framework/framework-res.apk rom/framework-res.apk
[ -s rom/framework-res.apk ] && echo "rom/framework-res.apk: ok"
for l in libbinder libutils libcutils libc++ liblog libbase libxml2 libcrypto libssl \
         libz libmediautils framework-permission-aidl-cpp libaaudio libandroid \
         libandroid_runtime libcamera2ndk libjnigraphics libmediandk libnativewindow; do
    [ -s "platlibs/$l.so" ] || dump "/system/lib64/$l.so" "platlibs/$l.so"
done
echo "platlibs: $(ls platlibs/*.so | wc -l) libraries"
echo "framework classes and platform libraries recovered from the pinned nightly"
