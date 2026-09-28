#!/bin/bash
# Build an unofficial LineageOS 22.2 joan ROM with the AOSP IMS backport:
# the official nightly pinned in upstream.lock, with the same files the
# flashable zip installs added to its system and product images.
#
# Not a source build (a LineageOS tree does not fit here): the official
# images are unpacked from the block OTA, mounted, extended, and written
# back as a full block OTA that recovery flashes the same way.
#
# - Same locations as the zip installs to, so the zip's uninstaller and
#   grant script work on this ROM unchanged.
# - ro.lineage.version gets an UNOFFICIAL-AOSPIMS suffix and
#   ro.lineage.releasetype becomes UNOFFICIAL: the ROM says what it is,
#   and LineageOS's updater stops offering official nightlies, which would
#   silently remove the IMS stack.
# - The zip is not signed with LineageOS's key (only LineageOS has it):
#   LineageOS recovery warns that signature verification failed and asks
#   before installing.
#
# Needs root (loop mounts), brotli, e2fsck. Run after build-apk.sh,
# build-wfc.sh and pack-zip.sh. Output: $WORK/out/rom/lineage-*-UNOFFICIAL-*.zip
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$HERE/.." && pwd)
. "$HERE/upstream.lock"
WORK=${WORK:-$HERE/work}
ROM_TAG=${ROM_TAG:-AOSPIMS-alpha1}
[ "$(id -u)" = 0 ] || exec sudo -E WORK="$WORK" ROM_TAG="$ROM_TAG" "$0" "$@"

OTA=$WORK/rom/$(basename "$LINEAGE_OTA_URL")
APK=$WORK/out/apk
R=$WORK/out/rom
rm -rf "$R" && mkdir -p "$R/mnt"
echo "$LINEAGE_OTA_SHA256  $OTA" | sha256sum -c --quiet

unpack() { # unpack <partition>: official block OTA -> raw ext4 image
    local p=$1
    unzip -o -q "$OTA" "$p.new.dat.br" "$p.transfer.list" -d "$R"
    brotli -d -f "$R/$p.new.dat.br" -o "$R/$p.new.dat"
    python3 "$HERE/tools/sdat2img.py" "$R/$p.transfer.list" "$R/$p.new.dat" "$R/$p.img" >/dev/null
    rm -f "$R/$p.new.dat.br" "$R/$p.new.dat"
    python3 - "$R/$p.img" <<'EOF'
import os, struct, sys
p = sys.argv[1]
sb = open(p, 'rb').read(2048)[1024:]
n = struct.unpack('<I', sb[4:8])[0] | struct.unpack('<I', sb[0x150:0x154])[0] << 32
need = n * (1024 << struct.unpack('<I', sb[24:28])[0])
if os.path.getsize(p) < need:
    os.truncate(p, need)
EOF
}

# put <src> <dest> <mode>: a file owned by root, labelled like the rest of
# the image, its directories created the same way.
put() {
    local src=$1 dst=$2 mode=$3 d
    d=$(dirname "$dst")
    if [ ! -d "$d" ]; then
        mkdir -p "$d"
        chmod 755 "$d"
        label "$d"
    fi
    cp "$src" "$dst"
    chown 0:0 "$dst"
    chmod "$mode" "$dst"
    label "$dst"
}
label() {
    python3 -c 'import os,sys; os.setxattr(sys.argv[1], "security.selinux", b"u:object_r:system_file:s0\0")' "$1"
}

echo "== system"
unpack system
mount -o loop,rw "$R/system.img" "$R/mnt"
S=$R/mnt/system
put "$APK/ImsStack.apk" "$S/priv-app/ImsStack/ImsStack.apk" 644
put "$APK/Iwlan.apk" "$S/priv-app/Iwlan/Iwlan.apk" 644
put "$APK/QualifiedNetworksService.apk" \
    "$S/priv-app/QualifiedNetworksService/QualifiedNetworksService.apk" 644
P=$HERE/permissions
put "$P/privapp-permissions-com.android.imsstack.xml" "$S/etc/permissions/com.android.imsstack.xml" 644
put "$P/privapp-permissions-com.google.android.iwlan.xml" "$S/etc/permissions/com.google.android.iwlan.xml" 644
put "$P/privapp-permissions-com.android.telephony.qns.xml" "$S/etc/permissions/com.android.telephony.qns.xml" 644
put "$ROOT/permissions/android.hardware.telephony.ims.xml" "$S/etc/permissions/android.hardware.telephony.ims.xml" 644
put "$P/default-permissions-com.android.imsstack.xml" "$S/etc/default-permissions/com.android.imsstack.xml" 644
put "$P/sysconfig-com.android.imsstack.xml" "$S/etc/sysconfig/com.android.imsstack.xml" 644
put "$P/sysconfig-com.google.android.iwlan.xml" "$S/etc/sysconfig/com.google.android.iwlan.xml" 644
# The zip's uninstaller removes the IMS feature file only with this marker.
echo joan > "$S/etc/permissions/android.hardware.telephony.ims.xml.joan-added"
chmod 644 "$S/etc/permissions/android.hardware.telephony.ims.xml.joan-added"
label "$S/etc/permissions/android.hardware.telephony.ims.xml.joan-added"
OLDVER=$(grep '^ro.lineage.version=' "$S/build.prop" | cut -d= -f2)
sed -i -e "s/^\(ro.lineage.version=.*\)-NIGHTLY-\(.*\)$/\1-UNOFFICIAL-$ROM_TAG-\2/" \
       -e "s/^\(ro.lineage.display.version=.*\)-NIGHTLY-\(.*\)$/\1-UNOFFICIAL-$ROM_TAG-\2/" \
       -e "s/^ro.lineage.releasetype=.*/ro.lineage.releasetype=UNOFFICIAL/" "$S/build.prop"
NEWVER=$(grep '^ro.lineage.version=' "$S/build.prop" | cut -d= -f2)
[ "$NEWVER" != "$OLDVER" ] || { echo "build.prop version not rewritten"; exit 1; }
echo "   $OLDVER -> $NEWVER"
# The AOSP stack delivers MT calls through the framework's ImsPhoneCallTracker,
# which the joan tree gates off for its modem IMS (see the installer's
# build.prop step). Flip it in the ROM so no install-time step is needed.
sed -i 's/^ro.telephony.block_binder_thread_on_incoming_calls=false$/ro.telephony.block_binder_thread_on_incoming_calls=true/' "$S/build.prop"
grep -q '^ro.telephony.block_binder_thread_on_incoming_calls=true$' "$S/build.prop" \
    || { echo "build.prop: incoming-call property not enabled"; exit 1; }
umount "$R/mnt"
e2fsck -fn "$R/system.img" >/dev/null

echo "== product"
unpack product
mount -o loop,rw "$R/product.img" "$R/mnt"
put "$APK/ImsStackPhoneOverlay.apk" "$R/mnt/overlay/ImsStackPhoneOverlay.apk" 644
put "$APK/ImsStackFrameworkOverlay.apk" "$R/mnt/overlay/ImsStackFrameworkOverlay.apk" 644
# Viettel 45204's IMS/XCAP APNs, merged into the world list as the zip
# does, with the zip's bookkeeping: the ROM's list kept as .joan-orig and
# the merged list's checksum as .joan-merged. The uninstall zip then puts
# the original back, and a zip flashed later merges into the original
# rather than into this merge.
MERGE_TMP=$R/apn-merge sh "$ROOT/scripts/merge-viettel-apns.sh" \
    "$R/mnt/etc/apns-conf.xml" "$ROOT/apn/viettel-45204.xml" > "$R/apns-merged.xml"
grep -q 'joan-viettel-45204-begin' "$R/apns-merged.xml"
[ "$(wc -c < "$R/apns-merged.xml")" -gt "$(wc -c < "$R/mnt/etc/apns-conf.xml")" ]
cp "$R/mnt/etc/apns-conf.xml" "$R/apns-orig.xml"
md5sum "$R/apns-merged.xml" | cut -d' ' -f1 > "$R/apns-mark"
put "$R/apns-orig.xml" "$R/mnt/etc/apns-conf.xml.joan-orig" 644
put "$R/apns-merged.xml" "$R/mnt/etc/apns-conf.xml" 644
put "$R/apns-mark" "$R/mnt/etc/apns-conf.xml.joan-merged" 644
umount "$R/mnt"
e2fsck -fn "$R/product.img" >/dev/null

# img2sdat: the whole image as one "new" range. Recovery's block updater
# writes it as it does the official one; zero blocks cost nothing once
# compressed.
for p in system product; do
    python3 - "$R/$p.img" "$R/$p.transfer.list" <<'EOF'
import os, sys
img, tl = sys.argv[1], sys.argv[2]
n = os.path.getsize(img) // 4096
with open(tl, 'w') as f:
    f.write(f'4\n{n}\n0\n0\nerase 2,0,{n}\nnew 2,0,{n}\n')
EOF
    brotli -q 6 -w 24 -f "$R/$p.img" -o "$R/$p.new.dat.br"
    rm -f "$R/$p.img"
done

NAME=$(basename "$OTA" | sed "s/-nightly-joan-signed\.zip$/-UNOFFICIAL-$ROM_TAG-joan.zip/")
python3 - "$OTA" "$R" "$R/$NAME" <<'EOF'
import os, sys, zipfile
ota, r, out = sys.argv[1:4]
replace = {f'{p}.{s}' for p in ('system', 'product') for s in ('new.dat.br', 'transfer.list')}
with zipfile.ZipFile(ota) as src, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, allowZip64=True) as dst:
    for info in src.infolist():
        n = info.filename
        # LineageOS's whole-file signature cannot survive a rebuilt zip.
        if n in replace or n in ('META-INF/MANIFEST.MF', 'META-INF/CERT.SF', 'META-INF/CERT.RSA'):
            continue
        dst.writestr(info, src.read(info), compress_type=info.compress_type)
    for n in sorted(replace):
        dst.write(os.path.join(r, n), n, compress_type=zipfile.ZIP_STORED
                  if n.endswith('.br') else zipfile.ZIP_DEFLATED)
print(f'{out}: {os.path.getsize(out)} bytes')
EOF
rm -f "$R"/*.new.dat.br "$R"/*.transfer.list "$R"/apns-*.xml "$R/apns-mark"
sha256sum "$R/$NAME" | tee "$R/$NAME.sha256"
