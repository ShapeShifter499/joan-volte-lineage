#!/bin/bash
# Check a ROM zip from tools/repack-rom.sh the way recovery and the first
# boot will see it:
#
# - each transfer list writes exactly the partition size that
#   dynamic_partitions_op_list gives the partition (recovery resizes the
#   partition to that first; a longer image would not fit);
# - each image decompresses to that size and is clean ext4;
# - every file the IMS stack adds is present, root-owned, mode 644 and
#   labelled system_file, as the rest of the image is;
# - the version says UNOFFICIAL, so the updater never offers an official
#   nightly over it;
# - with an apk directory, the apps in the image are the ones built.
#
# Usage: check-rom.sh <rom.zip> [<out/apk dir>]
# Needs brotli, e2fsck and debugfs; no root.
set -euo pipefail
ZIP=$1
APK=${2:-}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fails=0
ok() { echo "  ok: $1"; }
bad() { echo "  FAIL: $1"; fails=$((fails + 1)); }

unzip -q -o "$ZIP" dynamic_partitions_op_list \
    system.transfer.list system.new.dat.br product.transfer.list product.new.dat.br \
    META-INF/com/google/android/update-binary META-INF/com/google/android/updater-script -d "$T"
for p in system product; do
    size=$(awk -v p="$p" '$1 == "resize" && $2 == p { print $3 }' "$T/dynamic_partitions_op_list")
    blocks=$(sed -n 2p "$T/$p.transfer.list")
    if [ -n "$size" ] && [ "$((blocks * 4096))" = "$size" ]; then
        ok "$p: transfer list writes the whole partition ($size bytes)"
    else
        bad "$p: transfer list writes $blocks blocks, the partition is ${size:-?} bytes"
    fi
    brotli -d -f "$T/$p.new.dat.br" -o "$T/$p.img"
    rm -f "$T/$p.new.dat.br"
    [ "$(stat -c %s "$T/$p.img")" = "$size" ] && ok "$p: image is the partition size" \
        || bad "$p: image is $(stat -c %s "$T/$p.img") bytes"
    e2fsck -fn "$T/$p.img" >/dev/null 2>&1 && ok "$p: e2fsck clean" || bad "$p: e2fsck"
done

# check <image> <path>: present, root-owned, 0644, system_file.
check() {
    local img=$T/$1.img path=$2 st label
    st=$(debugfs -R "stat $path" "$img" 2>/dev/null)
    if ! grep -q '^Inode:' <<< "$st"; then
        bad "$1:$path missing"
        return
    fi
    label=$(debugfs -R "ea_get $path security.selinux" "$img" 2>/dev/null | tr -d '\0')
    if grep -q 'User: *0 *Group: *0' <<< "$st" && grep -q 'Mode: *0644' <<< "$st" \
        && grep -q 'u:object_r:system_file:s0' <<< "$label"; then
        ok "$1:$path"
    else
        bad "$1:$path owner, mode or label: $(grep -o 'User:.*' <<< "$st" | head -1) $label"
    fi
}
for f in priv-app/ImsStack/ImsStack.apk priv-app/Iwlan/Iwlan.apk \
         priv-app/QualifiedNetworksService/QualifiedNetworksService.apk \
         etc/permissions/com.android.imsstack.xml etc/permissions/com.google.android.iwlan.xml \
         etc/permissions/com.android.telephony.qns.xml etc/permissions/android.hardware.telephony.ims.xml \
         etc/default-permissions/com.android.imsstack.xml etc/sysconfig/com.android.imsstack.xml \
         etc/sysconfig/com.google.android.iwlan.xml; do
    check system "/system/$f"
done
for f in overlay/ImsStackPhoneOverlay.apk overlay/ImsStackFrameworkOverlay.apk etc/apns-conf.xml \
         etc/apns-conf.xml.joan-orig etc/apns-conf.xml.joan-merged; do
    check product "/$f"
done
debugfs -R "cat /etc/apns-conf.xml" "$T/product.img" 2>/dev/null > "$T/apns.xml"
debugfs -R "cat /etc/apns-conf.xml.joan-orig" "$T/product.img" 2>/dev/null > "$T/apns-orig.xml"
grep -q 'joan-viettel-45204-begin' "$T/apns.xml" \
    && ok "product: Viettel IMS APNs merged" || bad "product: Viettel IMS APNs missing"
[ "$(debugfs -R "cat /system/build.prop" "$T/system.img" 2>/dev/null \
    | grep -c '^ro.telephony.block_binder_thread_on_incoming_calls=true$')" = "1" ] \
    && ok "system: framework incoming-call handling enabled" \
    || bad "system: framework incoming-call handling not enabled"
# As the uninstall zip will see them: the backup is the ROM's own list, and
# the marker is the checksum of the live one (else it re-bases).
if ! grep -q 'joan-viettel' "$T/apns-orig.xml" && [ "$(wc -c < "$T/apns-orig.xml")" -gt 200 ]; then
    ok "product: APN backup is the ROM's own list"
else
    bad "product: APN backup is not the ROM's own list"
fi
[ "$(debugfs -R "cat /etc/apns-conf.xml.joan-merged" "$T/product.img" 2>/dev/null)" \
    = "$(md5sum < "$T/apns.xml" | cut -d' ' -f1)" ] \
    && ok "product: APN merge marker matches the live list" || bad "product: APN merge marker is stale"

prop=$(debugfs -R "cat /system/build.prop" "$T/system.img" 2>/dev/null)
grep -qx 'ro.lineage.releasetype=UNOFFICIAL' <<< "$prop" && ok "releasetype UNOFFICIAL" \
    || bad "releasetype is not UNOFFICIAL"
ver=$(sed -n 's/^ro.lineage.version=//p' <<< "$prop")
case $ver in
    *-UNOFFICIAL-*) ok "version $ver" ;;
    *) bad "version '$ver' is not marked UNOFFICIAL" ;;
esac

if [ -n "$APK" ]; then
    for a in ImsStack Iwlan QualifiedNetworksService; do
        debugfs -R "dump /system/priv-app/$a/$a.apk $T/$a.apk" "$T/system.img" >/dev/null 2>&1
        cmp -s "$T/$a.apk" "$APK/$a.apk" && ok "$a.apk is the one built" || bad "$a.apk differs from $APK"
    done
    for o in ImsStackPhoneOverlay ImsStackFrameworkOverlay; do
        debugfs -R "dump /overlay/$o.apk $T/$o.apk" "$T/product.img" >/dev/null 2>&1
        cmp -s "$T/$o.apk" "$APK/$o.apk" && ok "$o.apk is the one built" || bad "$o.apk differs from $APK"
    done
fi
[ -s "$T/META-INF/com/google/android/update-binary" ] && ok "updater present" || bad "no update-binary"
if unzip -Z1 "$ZIP" | grep -qE '^META-INF/(MANIFEST\.MF|CERT\.)'; then
    bad "stale LineageOS signature files left in the zip"
fi

if [ "$fails" = 0 ]; then
    echo "ROM checks: all passed"
else
    echo "ROM checks: $fails FAILED"
    exit 1
fi
