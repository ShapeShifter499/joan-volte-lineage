#!/usr/bin/env bash
# End to end: the real scripts/update-binary and scripts/update-binary-cleanup,
# run under mksh (recovery's shell) against loop-mounted ext4 images laid
# out like joan's system and product partitions.
#
# run-installer-tests.sh checks functions lifted out of the installer.
# That never caught the failures testers actually hit, because those were
# about what the whole install leaves on disk: an upgrade that bootlooped,
# a full partition, a truncated APN list. Each scenario here builds fresh
# images, installs, and then inspects the partitions the way the next boot
# would see them.
#
# Needs root (loop devices, mounts) and runs in a private mount namespace,
# so nothing leaks onto the host. Skips, loudly, where it cannot run.
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD

need() { command -v "$1" >/dev/null 2>&1; }
if [ "${1:-}" != "--inner" ]; then
  if [ "$(id -u)" != "0" ] || ! need losetup || ! need mkfs.ext4 \
      || ! need unshare || ! need mksh || ! need busybox; then
    echo "e2e installer tests: SKIP (needs root, losetup, mkfs.ext4, unshare, mksh, busybox)"
    exit 0
  fi
  if ! losetup -f >/dev/null 2>&1; then
    echo "e2e installer tests: SKIP (no free loop device)"
    exit 0
  fi
fi

WORK=${JOAN_E2E_WORK:-$ROOT/native/build/e2e-install}

# ----------------------------------------------------------------------
# Inner half: runs inside `unshare -m`, one scenario per invocation.
# ----------------------------------------------------------------------
if [ "${1:-}" = "--inner" ]; then
  SCEN=$2
  LSYS=$3; LPROD=$4; LSYSEXT=$5
  SHELL_UNDER_TEST=${6:-mksh}
  fails=0
  ok()   { printf '  ok: [%s] %s\n' "$SCEN" "$1"; }
  bad()  { printf '  FAIL: [%s] %s\n' "$SCEN" "$1"; fails=$((fails + 1)); }
  check() { if eval "$1"; then ok "$2"; else bad "$2"; fi; }

  # Recovery's view of the world: by-name/mapper nodes, an empty /mnt,
  # and a /tmp that is a different filesystem from the partitions.
  mount -t tmpfs none /dev/block
  mkdir -p /dev/block/mapper
  ln -s "$LSYS" /dev/block/mapper/system
  ln -s "$LPROD" /dev/block/mapper/product
  ln -s "$LSYSEXT" /dev/block/mapper/system_ext
  mount -t tmpfs none /mnt
  mount -t tmpfs none /tmp

  # Tools as recovery has them: toybox where this toybox build has the
  # applet, busybox for the rest (Android's toybox carries cmp, dd, grep
  # and tr; its unzip comes from ziptool), and no SELinux.
  BIN=$WORK/bin
  export PATH="$BIN:$PATH"

  S=/mnt/chk_sys; P=/mnt/chk_prod
  mount_chk() { mkdir -p "$S" "$P"; mount "$LSYS" "$S"; mount "$LPROD" "$P"; }
  umount_chk() { umount "$S" 2>/dev/null || true; umount "$P" 2>/dev/null || true; }
  tree_sum() {
    # Every path with its size and content hash. mtimes are excluded
    # deliberately: this is the "did anything change" fingerprint.
    ( cd "$1" && find . -path ./lost+found -prune -o -type f -print | sort \
      | while read -r f; do printf '%s %s\n' "$f" "$(md5sum < "$f" | cut -c1-32)"; done )
  }
  # Recovery hands the installer a PIPE as its status fd. Pointing it at a
  # regular file instead would not work: every `echo > /proc/self/fd/N`
  # re-opens the file with O_TRUNC, so only the last line would survive.
  run_install() {
    { $SHELL_UNDER_TEST "$ROOT/scripts/update-binary" 3 1 "$WORK/test.zip" 2>&1
      echo "rc=$?" ; } | cat >> "$WORK/out-$SCEN.txt"
    tail -1 "$WORK/out-$SCEN.txt" | sed 's/^rc=//'
  }
  run_uninstall() {
    { $SHELL_UNDER_TEST "$ROOT/scripts/update-binary-cleanup" 3 1 "$WORK/test-uninstall.zip" 2>&1
      echo "rc=$?" ; } | cat >> "$WORK/out-$SCEN-uninstall.txt"
    tail -1 "$WORK/out-$SCEN-uninstall.txt" | sed 's/^rc=//'
  }
  future() { [ "$(stat -c %Y "$1")" -ge 2145000000 ]; }
  out_has() { grep -q -- "$1" "$WORK/out-$SCEN.txt"; }
  no_temps() { ! find "$S" "$P" -name '*.joan-new' | grep -q .; }
  installed_ok() {
    check 'cmp -s "$S/system/priv-app/JoanIms/JoanIms.apk" "$WORK/zip/app/joan-ims.apk"' "apk installed byte-for-byte"
    check 'cmp -s "$S/system/etc/permissions/org.joan.ims.xml" "$ROOT/permissions/org.joan.ims.xml"' "allowlist installed"
    check 'cmp -s "$S/system/etc/default-permissions/org.joan.ims.xml" "$ROOT/permissions/default-permissions-org.joan.ims.xml"' "default grants installed"
    check 'cmp -s "$P/overlay/JoanImsPhoneDefault.apk" "$WORK/zip/app/joan-ims-rro.apk"' "phone overlay installed"
    check 'cmp -s "$P/overlay/JoanFwVolte.apk" "$WORK/zip/app/joan-fw-volte.apk"' "framework overlay installed"
    check 'future "$S/system/priv-app/JoanIms"' "priv-app directory re-dated so PackageManager re-parses it"
    check 'future "$S/system/priv-app/JoanIms/JoanIms.apk"' "apk re-dated"
    check 'future "$P/overlay/JoanImsPhoneDefault.apk" && future "$P/overlay/JoanFwVolte.apk"' "overlays re-dated"
    check 'grep -q "<permission name=\"android.permission.BIND_IMS_SERVICE\"" "$S/system/etc/permissions/org.joan.ims.xml"' "allowlist still covers BIND_IMS_SERVICE"
    check 'no_temps' "no .joan-new temp files left behind"
    check '[ ! -e "$S/system/etc/init/joan-grant.rc" ] && [ ! -e "$S/system/bin/joan-grant.sh" ]' "no boot-time grant on disk"
  }
  viettel_blocks() { grep -c 'joan-viettel-45204-begin' "$1" 2>/dev/null || true; }

  case "$SCEN" in
    fresh)
      rc=$(run_install)
      check '[ "$rc" = 0 ]' "fresh install exits 0 (rc=$rc)"
      check 'out_has "Done. Reboot system."' "prints Done"
      mount_chk
      installed_ok
      check 'cmp -s "$P/etc/apns-conf.xml.joan-orig" "$WORK/apns-orig.xml"' "APN backup is the ROM's list"
      check '[ "$(viettel_blocks "$P/etc/apns-conf.xml")" = 1 ]' "exactly one Viettel block merged"
      check '[ -f "$P/etc/apns-conf.xml.joan-merged" ]' "merge marker written"
      check '[ -f "$S/system/etc/permissions/android.hardware.telephony.ims.xml.joan-added" ]' "IMS feature ownership recorded"
      umount_chk
      ;;
    upgrade67)
      # The state an alpha67 install left, plus the alpha70-73 grant.
      rc=$(run_install)
      check '[ "$rc" = 0 ]' "upgrade over alpha67 exits 0 (rc=$rc)"
      check 'out_has "removed the alpha70-73 boot-time permission grant"' "says it removed the boot-time grant"
      mount_chk
      installed_ok
      check 'cmp -s "$P/etc/apns-conf.xml.joan-orig" "$WORK/apns-orig.xml"' "APN backup still the ROM's original"
      check '[ "$(viettel_blocks "$P/etc/apns-conf.xml")" = 1 ]' "still exactly one Viettel block"
      umount_chk
      ;;
    sysfull)
      mount_chk; tree_sum "$S" > "$WORK/sys-before.txt"; tree_sum "$P" > "$WORK/prod-before.txt"; umount_chk
      rc=$(run_install)
      check '[ "$rc" != 0 ]' "refuses when system is full (rc=$rc)"
      check 'out_has "too little free space"' "names the space problem"
      mount_chk; tree_sum "$S" > "$WORK/sys-after.txt"; tree_sum "$P" > "$WORK/prod-after.txt"
      check 'cmp -s "$WORK/sys-before.txt" "$WORK/sys-after.txt"' "system untouched"
      check 'cmp -s "$WORK/prod-before.txt" "$WORK/prod-after.txt"' "product untouched"
      umount_chk
      ;;
    prodtight)
      rc=$(run_install)
      check '[ "$rc" = 0 ]' "installs with no room for the APN merge (rc=$rc)"
      check 'out_has "WARNING"' "warns about the skipped merge"
      mount_chk
      check 'cmp -s "$P/etc/apns-conf.xml" "$WORK/apns-orig.xml"' "APN list byte-identical to the ROM's"
      check 'cmp -s "$P/overlay/JoanImsPhoneDefault.apk" "$WORK/zip/app/joan-ims-rro.apk"' "overlays installed anyway"
      check 'cmp -s "$S/system/priv-app/JoanIms/JoanIms.apk" "$WORK/zip/app/joan-ims.apk"' "apk installed anyway"
      check 'no_temps' "no .joan-new temp files left behind"
      umount_chk
      ;;
    reflash)
      rc1=$(run_install); mount_chk; cp "$P/etc/apns-conf.xml" "$WORK/apns-first.xml"; umount_chk
      rc2=$(run_install)
      check '[ "$rc1" = 0 ] && [ "$rc2" = 0 ]' "two flashes in a row both exit 0 ($rc1/$rc2)"
      mount_chk
      check 'cmp -s "$P/etc/apns-conf.xml" "$WORK/apns-first.xml"' "second merge is identical to the first"
      check 'cmp -s "$P/etc/apns-conf.xml.joan-orig" "$WORK/apns-orig.xml"' "backup still the ROM's original"
      installed_ok
      umount_chk
      ;;
    rebase)
      rc1=$(run_install)
      mount_chk; cp "$WORK/apns-newrom.xml" "$P/etc/apns-conf.xml"; umount_chk
      rc2=$(run_install)
      check '[ "$rc1" = 0 ] && [ "$rc2" = 0 ]' "flash, ROM replaces list, flash again ($rc1/$rc2)"
      check 'out_has "re-basing"' "notices the ROM's newer list"
      mount_chk
      check 'cmp -s "$P/etc/apns-conf.xml.joan-orig" "$WORK/apns-newrom.xml"' "backup follows the ROM's newer list"
      check 'grep -q "NewRom Carrier" "$P/etc/apns-conf.xml"' "merged list keeps the newer ROM rows"
      check '[ "$(viettel_blocks "$P/etc/apns-conf.xml")" = 1 ]' "exactly one Viettel block"
      umount_chk
      ;;
    syslist)
      rc=$(run_install)
      check '[ "$rc" = 0 ]' "installs when the ROM keeps its list on /system (rc=$rc)"
      mount_chk
      check '[ -f "$P/etc/apns-conf.xml.joan-added" ]' "records that the /product list is ours"
      check '[ "$(viettel_blocks "$P/etc/apns-conf.xml")" = 1 ]' "merged into a /product copy"
      umount_chk
      urc=$(run_uninstall)
      check '[ "$urc" = 0 ]' "uninstall exits 0 (rc=$urc)"
      mount_chk
      check '[ ! -e "$P/etc/apns-conf.xml" ] && [ ! -e "$P/etc/apns-conf.xml.joan-added" ]' "uninstall removes the /product copy it added"
      check 'cmp -s "$S/system/etc/apns-conf.xml" "$WORK/apns-orig.xml"' "the ROM's /system list untouched"
      umount_chk
      ;;
    uninstall)
      rc=$(run_install)
      urc=$(run_uninstall)
      check '[ "$rc" = 0 ] && [ "$urc" = 0 ]' "install then uninstall both exit 0 ($rc/$urc)"
      mount_chk
      check '[ ! -e "$S/system/priv-app/JoanIms" ]' "priv-app removed"
      check '[ ! -e "$S/system/etc/permissions/org.joan.ims.xml" ]' "allowlist removed"
      check '[ ! -e "$S/system/etc/default-permissions/org.joan.ims.xml" ]' "default grants removed"
      check '[ ! -e "$S/system/etc/permissions/android.hardware.telephony.ims.xml" ]' "IMS feature xml removed (it was ours)"
      check '[ ! -e "$P/overlay/JoanImsPhoneDefault.apk" ] && [ ! -e "$P/overlay/JoanFwVolte.apk" ]' "overlays removed"
      check 'cmp -s "$P/etc/apns-conf.xml" "$WORK/apns-orig.xml"' "APN list restored byte-for-byte"
      check '! find "$S" "$P" -name "*joan*" | grep -q .' "nothing named joan left on either partition"
      umount_chk
      ;;
    *) bad "unknown scenario"; ;;
  esac
  exit "$fails"
fi

# ----------------------------------------------------------------------
# Outer half: images, loop devices, one namespace per scenario.
# ----------------------------------------------------------------------
rm -rf "$WORK"
mkdir -p "$WORK/zip/META-INF/com/google/android" "$WORK/zip/app" \
  "$WORK/zip/etc/permissions" "$WORK/zip/etc/default-permissions" \
  "$WORK/zip/apn" "$WORK/zip/scripts" "$WORK/bin"

# Tool farm.
for t in cat chmod cut df dmesg head ls lsattr md5sum cksum mkdir mount mv \
         readlink rm rmdir sed stat sync tail touch umount wc blockdev \
         date echo printf; do
  if toybox "$t" --help >/dev/null 2>&1; then
    ln -sf "$(command -v toybox)" "$WORK/bin/$t"
  else
    ln -sf "$(command -v busybox)" "$WORK/bin/$t"
  fi
done
for t in cmp dd grep tr unzip; do ln -sf "$(command -v busybox)" "$WORK/bin/$t"; done
# toybox umount also frees a loop device unless told -D. On a handset the
# partitions are dm nodes and nothing is freed; here the partitions ARE
# loop devices, and freeing them mid-test pulls the images away.
rm -f "$WORK/bin/umount"
printf '#!/bin/sh\nexec toybox umount -D "$@"\n' > "$WORK/bin/umount"
chmod 755 "$WORK/bin/umount"
printf '#!/bin/sh\nexit 0\n' > "$WORK/bin/chcon"
printf '#!/bin/sh\nexit 0\n' > "$WORK/bin/getprop"
chmod 755 "$WORK/bin/chcon" "$WORK/bin/getprop"

# The package under test. The apk is a stand-in: the installer never
# parses it, only copies and verifies it, and building the real one needs
# the SDK. Everything else is the file that ships.
head -c 190000 /dev/urandom > "$WORK/zip/app/joan-ims.apk"
head -c 8530 /dev/urandom > "$WORK/zip/app/joan-ims-rro.apk"
head -c 8530 /dev/urandom > "$WORK/zip/app/joan-fw-volte.apk"
cp scripts/update-binary "$WORK/zip/META-INF/com/google/android/update-binary"
cp META-INF/com/google/android/updater-script "$WORK/zip/META-INF/com/google/android/"
cp permissions/org.joan.ims.xml permissions/android.hardware.telephony.ims.xml "$WORK/zip/etc/permissions/"
cp permissions/default-permissions-org.joan.ims.xml "$WORK/zip/etc/default-permissions/org.joan.ims.xml"
cp apn/viettel-45204.xml "$WORK/zip/apn/"
cp scripts/merge-viettel-apns.sh "$WORK/zip/scripts/"
python3 - "$WORK" <<'PYZ'
import os, sys, zipfile
work = sys.argv[1]
base = os.path.join(work, 'zip')
with zipfile.ZipFile(os.path.join(work, 'test.zip'), 'w', zipfile.ZIP_DEFLATED) as z:
    for d, _, fs in os.walk(base):
        for f in fs:
            p = os.path.join(d, f)
            z.write(p, os.path.relpath(p, base))
with zipfile.ZipFile(os.path.join(work, 'test-uninstall.zip'), 'w') as z:
    z.writestr('META-INF/com/google/android/updater-script', '# dummy\n')
PYZ

# A world APN list of realistic size (LineageOS ships several hundred KB),
# with the fixture's Viettel rows in it so the merge has work to do.
python3 - "$ROOT/tests/apn/fixtures/orig-apns-conf.xml" "$WORK" <<'PYA'
import sys, os
fix, work = sys.argv[1], sys.argv[2]
body = open(fix).read()
head, tail = body.rsplit('</apns>', 1)
rows = []
for i in range(2500):
    rows.append('    <apn carrier="Synthetic %d" mcc="%03d" mnc="%02d" apn="internet.%d" type="default,supl" />\n'
                % (i, 200 + i % 700, i % 100, i))
open(os.path.join(work, 'apns-orig.xml'), 'w').write(head + ''.join(rows) + '</apns>' + tail)
newrom = head + ''.join(rows[:100]) + \
    '    <apn carrier="NewRom Carrier" mcc="999" mnc="99" apn="newrom" type="default" />\n</apns>' + tail
open(os.path.join(work, 'apns-newrom.xml'), 'w').write(newrom)
PYA

# The alpha67 allowlist, for the upgrade scenario. From git where there
# is history; a checked-in copy of its entries otherwise.
if git rev-parse -q --verify v0.4.0-alpha67 >/dev/null 2>&1; then
  git show v0.4.0-alpha67:permissions/org.joan.ims.xml > "$WORK/alpha67-allowlist.xml"
else
  cat > "$WORK/alpha67-allowlist.xml" <<'X67'
<?xml version="1.0" encoding="utf-8"?>
<permissions><privapp-permissions package="org.joan.ims">
<permission name="android.permission.MODIFY_PHONE_STATE" />
<permission name="android.permission.READ_PRIVILEGED_PHONE_STATE" />
<permission name="android.permission.READ_PRECISE_PHONE_STATE" />
<permission name="android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS" />
<permission name="android.permission.BIND_IMS_SERVICE" />
<permission name="android.permission.LOCATION_BYPASS" />
<permission name="android.permission.RECORD_AUDIO" />
<permission name="android.permission.MODIFY_AUDIO_SETTINGS" />
</privapp-permissions></permissions>
X67
fi

mk_img() {
  # mk_img <file> <MB>
  rm -f "$1"
  truncate -s "${2}M" "$1"
  mkfs.ext4 -q -F -m 0 -b 4096 -I 256 -N 1024 "$1"
}

populate() {
  # populate <scenario> <sysdir> <proddir> <sysextdir>
  sc=$1; s=$2; p=$3; se=$4
  mkdir -p "$s/system/priv-app/Dummy" "$s/system/etc/permissions" \
           "$s/system/etc/init" "$s/system/bin" "$p/overlay" "$p/etc" \
           "$se/bin" "$se/etc/init"
  printf 'ro.system.build.version.sdk=35\nro.build.version.sdk=35\n' > "$s/system/build.prop"
  head -c 50000 /dev/urandom > "$s/system/priv-app/Dummy/Dummy.apk"
  echo '<permissions/>' > "$s/system/etc/permissions/platform.xml"
  if [ "$sc" = syslist ]; then
    cp "$WORK/apns-orig.xml" "$s/system/etc/apns-conf.xml"
  else
    cp "$WORK/apns-orig.xml" "$p/etc/apns-conf.xml"
  fi
  if [ "$sc" = upgrade67 ]; then
    mkdir -p "$s/system/priv-app/JoanIms" "$s/system/etc/default-permissions"
    head -c 185000 /dev/urandom > "$s/system/priv-app/JoanIms/JoanIms.apk"
    cp "$WORK/alpha67-allowlist.xml" "$s/system/etc/permissions/org.joan.ims.xml"
    cp permissions/android.hardware.telephony.ims.xml "$s/system/etc/permissions/"
    echo joan > "$s/system/etc/permissions/android.hardware.telephony.ims.xml.joan-added"
    echo '# alpha73 leftover' > "$s/system/etc/init/joan-grant.rc"
    echo '# alpha73 leftover' > "$s/system/bin/joan-grant.sh"
    head -c 8000 /dev/urandom > "$p/overlay/JoanImsPhoneDefault.apk"
    head -c 8000 /dev/urandom > "$p/overlay/JoanFwVolte.apk"
    cp "$WORK/apns-orig.xml" "$p/etc/apns-conf.xml.joan-orig"
    MERGE_TMP="$WORK/m67" sh scripts/merge-viettel-apns.sh "$WORK/apns-orig.xml" \
      apn/viettel-45204.xml > "$p/etc/apns-conf.xml"
    # What recovery's clock leaves: a directory older than any cache.
    touch -d '2017-06-01 00:00:00' "$s/system/priv-app/JoanIms" \
      "$s/system/priv-app/JoanIms/JoanIms.apk"
  fi
}

fill_to() {
  # fill_to <mountpoint> <KB to leave free>
  dev=$(findmnt -n -o SOURCE "$1")
  echo 0 > "/sys/fs/ext4/$(basename "$dev")/reserved_clusters" 2>/dev/null || true
  avail=$(df -k --output=avail "$1" | tail -1 | tr -d ' ')
  want=$((avail - $2))
  [ "$want" -gt 0 ] && fallocate -l "${want}K" "$1/filler"
  sync
}

CREATED_DEVBLOCK=0
if [ ! -d /dev/block ]; then mkdir /dev/block; CREATED_DEVBLOCK=1; fi
LOOPS=""
cleanup() {
  for l in $LOOPS; do losetup -d "$l" 2>/dev/null || true; done
  [ "$CREATED_DEVBLOCK" = 1 ] && rmdir /dev/block 2>/dev/null || true
}
trap cleanup EXIT

total_fail=0
run_scenario() {
  sc=$1; shell=${2:-mksh}
  mk_img "$WORK/system.img" 24
  mk_img "$WORK/product.img" 16
  mk_img "$WORK/system_ext.img" 8
  rm -f "$WORK/out-$sc.txt" "$WORK/out-$sc-uninstall.txt"
  # Attach once and keep the same devices for populating and for the
  # install. `mount -o loop` would autoclear on umount, and a re-attach
  # can race that teardown onto the same loop number.
  ls=$(losetup -f --show "$WORK/system.img"); LOOPS="$LOOPS $ls"
  lp=$(losetup -f --show "$WORK/product.img"); LOOPS="$LOOPS $lp"
  le=$(losetup -f --show "$WORK/system_ext.img"); LOOPS="$LOOPS $le"
  m=$WORK/mnt; mkdir -p "$m/s" "$m/p" "$m/e"
  mount "$ls" "$m/s"; mount "$lp" "$m/p"; mount "$le" "$m/e"
  populate "$sc" "$m/s" "$m/p" "$m/e"
  case "$sc" in
    sysfull)   fill_to "$m/s" 100 ;;   # the apk alone is ~190 KB
    prodtight) fill_to "$m/p" 400 ;;   # room for overlays, not for backup + merge
  esac
  umount "$m/s" "$m/p" "$m/e"
  if unshare -m --propagation private \
      env JOAN_E2E_WORK="$WORK" bash "$0" --inner "$sc" "$ls" "$lp" "$le" "$shell"; then
    :
  else
    total_fail=$((total_fail + $?))
    echo "  -- installer output ($sc, $shell):"; sed 's/^/     | /' "$WORK/out-$sc.txt" | tail -40
  fi
  losetup -d "$ls" "$lp" "$le"
  LOOPS=""
}

echo "== e2e installer (real update-binary on ext4 images)"
for sc in fresh upgrade67 sysfull prodtight reflash rebase syslist uninstall; do
  run_scenario "$sc" mksh
done
# LineageOS recovery runs mksh; TWRP and OrangeFox run busybox ash.
for sc in fresh upgrade67 uninstall; do
  run_scenario "$sc" "busybox sh"
done

if [ "$total_fail" -ne 0 ]; then
  echo "e2e installer tests: FAIL $total_fail"
  exit 1
fi
echo "e2e installer tests: all passed"
