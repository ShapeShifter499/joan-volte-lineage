#!/usr/bin/env bash
# End to end for the AOSP IMS zip: the installer and uninstaller that
# aosp-ims/tools/make-installer.py generates from joan's scripts, run under
# mksh (recovery's shell) and busybox ash against loop-mounted ext4 images
# laid out like joan's system and product partitions. Derived from
# tests/installer/run-e2e-install.sh; "upgrade67" here is a phone running
# joan alpha67 that flashes the AOSP zip, which must replace joan cleanly.
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

WORK=${JOAN_E2E_WORK:-$ROOT/aosp-ims/work/e2e-install}
GEN=$WORK/gen

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
    # A phone running joan takes the migration zip; everything else the
    # fresh-flash one (which must refuse a phone that has joan).
    case "$SCEN" in upgrade67) inst=$GEN/update-binary-migrate ;; *) inst=$GEN/update-binary ;; esac
    { $SHELL_UNDER_TEST "$inst" 3 1 "$WORK/test.zip" 2>&1
      echo "rc=$?" ; } | cat >> "$WORK/out-$SCEN.txt"
    tail -1 "$WORK/out-$SCEN.txt" | sed 's/^rc=//'
  }
  run_uninstall() {
    { $SHELL_UNDER_TEST "$GEN/update-binary-cleanup" 3 1 "$WORK/test-uninstall.zip" 2>&1
      echo "rc=$?" ; } | cat >> "$WORK/out-$SCEN-uninstall.txt"
    tail -1 "$WORK/out-$SCEN-uninstall.txt" | sed 's/^rc=//'
  }
  future() { [ "$(stat -c %Y "$1")" -ge 2145000000 ]; }
  out_has() { grep -q -- "$1" "$WORK/out-$SCEN.txt"; }
  no_temps() { ! find "$S" "$P" -name '*.joan-new' | grep -q .; }
  installed_ok() {
    check 'cmp -s "$S/system/priv-app/ImsStack/ImsStack.apk" "$WORK/zip/app/ImsStack.apk"' "apk installed byte-for-byte"
    check 'cmp -s "$S/system/etc/permissions/com.android.imsstack.xml" "$ROOT/aosp-ims/permissions/privapp-permissions-com.android.imsstack.xml"' "allowlist installed"
    check 'cmp -s "$S/system/etc/default-permissions/com.android.imsstack.xml" "$ROOT/aosp-ims/permissions/default-permissions-com.android.imsstack.xml"' "default grants installed"
    check 'cmp -s "$S/system/etc/sysconfig/com.android.imsstack.xml" "$ROOT/aosp-ims/permissions/sysconfig-com.android.imsstack.xml"' "sysconfig (power-save exemption) installed"
    check 'cmp -s "$S/system/priv-app/Iwlan/Iwlan.apk" "$WORK/zip/app/Iwlan.apk"' "VoWiFi: IWLAN installed"
    check 'cmp -s "$S/system/priv-app/QualifiedNetworksService/QualifiedNetworksService.apk" "$WORK/zip/app/QualifiedNetworksService.apk"' "VoWiFi: QNS installed"
    check 'cmp -s "$S/system/etc/permissions/com.google.android.iwlan.xml" "$ROOT/aosp-ims/permissions/privapp-permissions-com.google.android.iwlan.xml" && cmp -s "$S/system/etc/permissions/com.android.telephony.qns.xml" "$ROOT/aosp-ims/permissions/privapp-permissions-com.android.telephony.qns.xml"' "VoWiFi: allowlists installed"
    check 'future "$S/system/priv-app/Iwlan" && future "$S/system/priv-app/QualifiedNetworksService"' "VoWiFi: priv-app directories re-dated"
    check 'cmp -s "$P/overlay/ImsStackPhoneOverlay.apk" "$WORK/zip/app/ImsStackPhoneOverlay.apk"' "phone overlay installed"
    check 'cmp -s "$P/overlay/ImsStackFrameworkOverlay.apk" "$WORK/zip/app/ImsStackFrameworkOverlay.apk"' "framework overlay installed"
    check 'future "$S/system/priv-app/ImsStack"' "priv-app directory re-dated so PackageManager re-parses it"
    check 'future "$S/system/priv-app/ImsStack/ImsStack.apk"' "apk re-dated"
    check 'future "$P/overlay/ImsStackPhoneOverlay.apk" && future "$P/overlay/ImsStackFrameworkOverlay.apk"' "overlays re-dated"
    check 'grep -q "<permission name=\"android.permission.MODIFY_PHONE_STATE\"" "$S/system/etc/permissions/com.android.imsstack.xml"' "allowlist covers MODIFY_PHONE_STATE"
    check 'no_temps' "no .joan-new temp files left behind"
    check '[ ! -e "$S/system/etc/init/joan-grant.rc" ] && [ ! -e "$S/system/bin/joan-grant.sh" ]' "no boot-time grant on disk"
    check 'cmp -s "$S/system/build.prop" "$WORK/buildprop-on.txt"' "build.prop: incoming-call handling on, nothing else changed"
    check '[ -f "$S/system/etc/aosp-ims-incoming-calls.flipped" ]' "build.prop flip recorded for the uninstaller"
    check 'cmp -s "$S/system/addon.d/60-aosp-ims.sh" "$ROOT/aosp-ims/zip/addon.d/60-aosp-ims.sh"' "addon.d script installed (kept across LineageOS updates)"
    check 'cmp -s "$S/system/etc/aosp-ims/merge-viettel-apns.sh" "$ROOT/scripts/merge-viettel-apns.sh" && cmp -s "$S/system/etc/aosp-ims/viettel-45204.xml" "$ROOT/apn/viettel-45204.xml"' "addon.d's APN merge inputs installed"
  }
  # What a LineageOS update leaves once backuptool has run the addon.d
  # script: the stack as the zip installed it, on the update's partitions.
  restored_ok() {
    check 'cmp -s "$S/system/priv-app/ImsStack/ImsStack.apk" "$WORK/zip/app/ImsStack.apk"' "update: apk restored byte-for-byte"
    check 'cmp -s "$S/system/etc/permissions/com.android.imsstack.xml" "$ROOT/aosp-ims/permissions/privapp-permissions-com.android.imsstack.xml" && cmp -s "$S/system/etc/default-permissions/com.android.imsstack.xml" "$ROOT/aosp-ims/permissions/default-permissions-com.android.imsstack.xml" && cmp -s "$S/system/etc/sysconfig/com.android.imsstack.xml" "$ROOT/aosp-ims/permissions/sysconfig-com.android.imsstack.xml"' "update: allowlist, default grants and sysconfig restored"
    check 'cmp -s "$S/system/priv-app/Iwlan/Iwlan.apk" "$WORK/zip/app/Iwlan.apk" && cmp -s "$S/system/priv-app/QualifiedNetworksService/QualifiedNetworksService.apk" "$WORK/zip/app/QualifiedNetworksService.apk"' "update: VoWiFi apps restored"
    check 'cmp -s "$S/system/etc/permissions/com.google.android.iwlan.xml" "$ROOT/aosp-ims/permissions/privapp-permissions-com.google.android.iwlan.xml" && cmp -s "$S/system/etc/permissions/com.android.telephony.qns.xml" "$ROOT/aosp-ims/permissions/privapp-permissions-com.android.telephony.qns.xml" && cmp -s "$S/system/etc/sysconfig/com.google.android.iwlan.xml" "$ROOT/aosp-ims/permissions/sysconfig-com.google.android.iwlan.xml"' "update: VoWiFi allowlists and sysconfig restored"
    check 'cmp -s "$P/overlay/ImsStackPhoneOverlay.apk" "$WORK/zip/app/ImsStackPhoneOverlay.apk" && cmp -s "$P/overlay/ImsStackFrameworkOverlay.apk" "$WORK/zip/app/ImsStackFrameworkOverlay.apk"' "update: overlays restored on product"
    check 'cmp -s "$S/system/addon.d/60-aosp-ims.sh" "$ROOT/aosp-ims/zip/addon.d/60-aosp-ims.sh"' "update: addon.d script kept for the next update"
    check 'cmp -s "$S/system/etc/aosp-ims/merge-viettel-apns.sh" "$ROOT/scripts/merge-viettel-apns.sh" && cmp -s "$S/system/etc/aosp-ims/viettel-45204.xml" "$ROOT/apn/viettel-45204.xml"' "update: APN merge inputs restored"
    check '[ "$(stat -c %a "$S/system/priv-app/ImsStack")" = 755 ] && [ "$(stat -c %a "$S/system/priv-app/Iwlan")" = 755 ]' "update: priv-app directories 755"
    check '! find "$S" "$P" -name "*.aosp-ims-new" -o -name "*.joan-new" | grep -q .' "update: no temp files left behind"
  }
  # The OTA's backuptool.sh as LineageOS recovery runs it, with one change:
  # it starts addon.d scripts through the shell under test, because their
  # #!/sbin/sh does not exist on this host.
  stage_backuptool() {
    mkdir -p /tmp/install/bin /mnt/system
    cp "$ROOT/aosp-ims/tests/fixtures/backuptool/backuptool.functions" /tmp/install/bin/
    sed 's#^\( *\)\$script \$stage$#\1$E2E_SHELL $script $stage#' \
      "$ROOT/aosp-ims/tests/fixtures/backuptool/backuptool.sh" > /tmp/install/bin/backuptool.sh
    [ "$(grep -c '\$E2E_SHELL \$script \$stage' /tmp/install/bin/backuptool.sh)" = 1 ]
  }
  run_backuptool() {
    E2E_DYNAMIC=1 E2E_SHELL="$SHELL_UNDER_TEST" $SHELL_UNDER_TEST /tmp/install/bin/backuptool.sh \
      "$1" /dev/block/mapper/system ext4 >> "$WORK/out-$SCEN-ota.txt" 2>&1
  }
  # A LineageOS update as recovery writes it: system, product and
  # system_ext rewritten whole, from a newer build.
  flash_update() {
    for fu_d in "$LSYS" "$LPROD" "$LSYSEXT"; do
      mkfs.ext4 -q -F -m 0 -b 4096 -I 256 -N 1024 "$fu_d"
    done
    mkdir -p /mnt/new_s /mnt/new_p
    mount "$LSYS" /mnt/new_s; mount "$LPROD" /mnt/new_p
    mkdir -p /mnt/new_s/system/priv-app/Dummy /mnt/new_s/system/etc/permissions \
             /mnt/new_s/system/addon.d /mnt/new_s/product /mnt/new_s/system_ext \
             /mnt/new_p/overlay /mnt/new_p/etc
    ln -s /product /mnt/new_s/system/product
    ln -s /system_ext /mnt/new_s/system/system_ext
    head -c 50000 /dev/urandom > /mnt/new_s/system/priv-app/Dummy/Dummy.apk
    echo '<permissions/>' > /mnt/new_s/system/etc/permissions/platform.xml
    case "$SCEN" in
      ota)
        cp "$WORK/buildprop-update.txt" /mnt/new_s/system/build.prop
        cp "$WORK/apns-newrom.xml" /mnt/new_p/etc/apns-conf.xml
        ;;
      otaalt)
        # No incoming-call gate, its own IMS feature file, its APN list on /system.
        cp "$WORK/buildprop-update-plain.txt" /mnt/new_s/system/build.prop
        cp "$WORK/apns-newrom.xml" /mnt/new_s/system/etc/apns-conf.xml
        cp "$WORK/ims-feature-rom.xml" /mnt/new_s/system/etc/permissions/android.hardware.telephony.ims.xml
        ;;
    esac
    umount /mnt/new_s /mnt/new_p
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
      check 'out_has "Removing the joan IMS stack"' "says it replaces joan"
      mount_chk
      installed_ok
      check '[ ! -e "$S/system/priv-app/JoanIms" ]' "joan priv-app removed"
      check '[ ! -e "$S/system/etc/permissions/org.joan.ims.xml" ]' "joan allowlist removed"
      check '[ ! -e "$P/overlay/JoanImsPhoneDefault.apk" ] && [ ! -e "$P/overlay/JoanFwVolte.apk" ]' "joan overlays removed"
      check '[ -f "$S/system/etc/permissions/android.hardware.telephony.ims.xml.joan-added" ]' "IMS feature ownership marker kept"
      check 'cmp -s "$P/etc/apns-conf.xml.joan-orig" "$WORK/apns-orig.xml"' "APN backup still the ROM's original"
      check '[ "$(viettel_blocks "$P/etc/apns-conf.xml")" = 1 ]' "still exactly one Viettel block"
      umount_chk
      ;;
    joanrefuse)
      # joan alpha67 installed, and the FRESH zip flashed on top.
      mount_chk; tree_sum "$S" > "$WORK/sys-before.txt"; tree_sum "$P" > "$WORK/prod-before.txt"; umount_chk
      rc=$(run_install)
      check '[ "$rc" != 0 ]' "fresh zip refuses a phone running joan (rc=$rc)"
      check 'out_has "migrate-from-joan"' "names the migration zip"
      mount_chk; tree_sum "$S" > "$WORK/sys-after.txt"; tree_sum "$P" > "$WORK/prod-after.txt"
      check 'cmp -s "$WORK/sys-before.txt" "$WORK/sys-after.txt"' "system untouched"
      check 'cmp -s "$WORK/prod-before.txt" "$WORK/prod-after.txt"' "product untouched"
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
      check 'cmp -s "$P/overlay/ImsStackPhoneOverlay.apk" "$WORK/zip/app/ImsStackPhoneOverlay.apk"' "overlays installed anyway"
      check 'cmp -s "$S/system/priv-app/ImsStack/ImsStack.apk" "$WORK/zip/app/ImsStack.apk"' "apk installed anyway"
      check 'no_temps' "no .joan-new temp files left behind"
      check 'cmp -s "$S/system/build.prop" "$WORK/buildprop-plain.txt" && [ ! -e "$S/system/etc/aosp-ims-incoming-calls.flipped" ]' "build.prop without the property left alone"
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
      check '[ ! -e "$S/system/priv-app/ImsStack" ]' "priv-app removed"
      check '[ ! -e "$S/system/etc/permissions/com.android.imsstack.xml" ]' "allowlist removed"
      check '[ ! -e "$S/system/etc/default-permissions/com.android.imsstack.xml" ]' "default grants removed"
      check '[ ! -e "$S/system/etc/sysconfig/com.android.imsstack.xml" ]' "sysconfig removed"
      check '[ ! -e "$S/system/priv-app/Iwlan" ] && [ ! -e "$S/system/priv-app/QualifiedNetworksService" ]' "VoWiFi apps removed"
      check '[ ! -e "$S/system/etc/permissions/com.google.android.iwlan.xml" ] && [ ! -e "$S/system/etc/permissions/com.android.telephony.qns.xml" ] && [ ! -e "$S/system/etc/sysconfig/com.google.android.iwlan.xml" ]' "VoWiFi allowlists and sysconfig removed"
      check '[ ! -e "$S/system/etc/permissions/android.hardware.telephony.ims.xml" ]' "IMS feature xml removed (it was ours)"
      check '[ ! -e "$P/overlay/ImsStackPhoneOverlay.apk" ] && [ ! -e "$P/overlay/ImsStackFrameworkOverlay.apk" ]' "overlays removed"
      check 'cmp -s "$P/etc/apns-conf.xml" "$WORK/apns-orig.xml"' "APN list restored byte-for-byte"
      check 'cmp -s "$S/system/build.prop" "$WORK/buildprop-joan.txt"' "build.prop restored byte-for-byte"
      check '! find "$S" "$P" -name "*aosp-ims*" | grep -q .' "no aosp-ims marker or temp file left"
      check '! find "$S" "$P" -name "*joan*" | grep -q .' "nothing named joan left on either partition"
      umount_chk
      ;;
    ota|otaalt)
      # Installed, then a LineageOS update: the OTA's own backuptool runs
      # the addon.d script before and after rewriting the partitions.
      rc=$(run_install)
      check '[ "$rc" = 0 ]' "install before the update exits 0 (rc=$rc)"
      if stage_backuptool; then ok "the OTA's backuptool staged"; else bad "backuptool fixture not staged"; fi
      run_backuptool backup
      flash_update
      run_backuptool restore
      check '! grep -q "not possible" "$WORK/out-$SCEN-ota.txt"' "backuptool ran the addon.d scripts both times"
      mount_chk
      restored_ok
      case "$SCEN" in
        ota)
          check 'cmp -s "$S/system/build.prop" "$WORK/buildprop-update.txt.on"' "update: its build.prop gates incoming calls off again; flipped on, nothing else changed"
          check '[ -f "$S/system/etc/aosp-ims-incoming-calls.flipped" ]' "update: flip recorded for the uninstaller"
          check 'cmp -s "$S/system/etc/permissions/android.hardware.telephony.ims.xml" "$ROOT/permissions/android.hardware.telephony.ims.xml" && [ -f "$S/system/etc/permissions/android.hardware.telephony.ims.xml.joan-added" ]' "update: IMS feature xml ours again, and recorded as ours"
          check 'cmp -s "$P/etc/apns-conf.xml.joan-orig" "$WORK/apns-newrom.xml"' "update: APN backup is the update's own list"
          check '[ "$(viettel_blocks "$P/etc/apns-conf.xml")" = 1 ] && grep -q "NewRom Carrier" "$P/etc/apns-conf.xml"' "update: Viettel rows merged into the update's list"
          check '[ "$(cat "$P/etc/apns-conf.xml.joan-merged")" = "$(md5sum < "$P/etc/apns-conf.xml" | cut -d" " -f1)" ]' "update: APN merge marker matches the live list"
          ;;
        otaalt)
          check 'cmp -s "$S/system/build.prop" "$WORK/buildprop-update-plain.txt" && [ ! -e "$S/system/etc/aosp-ims-incoming-calls.flipped" ]' "update without the gate: build.prop left alone, no marker"
          check 'cmp -s "$S/system/etc/permissions/android.hardware.telephony.ims.xml.joan-orig" "$WORK/ims-feature-rom.xml" && cmp -s "$S/system/etc/permissions/android.hardware.telephony.ims.xml" "$ROOT/permissions/android.hardware.telephony.ims.xml"' "update ships its own IMS feature xml: kept aside, ours in place"
          check '[ "$(viettel_blocks "$P/etc/apns-conf.xml")" = 1 ] && [ -f "$P/etc/apns-conf.xml.joan-added" ] && cmp -s "$S/system/etc/apns-conf.xml" "$WORK/apns-newrom.xml"' "update keeps its APN list on /system: merged into a /product copy, /system untouched"
          ;;
      esac
      umount_chk
      urc=$(run_uninstall)
      check '[ "$urc" = 0 ]' "uninstall after the update exits 0 (rc=$urc)"
      mount_chk
      check '[ ! -e "$S/system/priv-app/ImsStack" ] && [ ! -e "$S/system/addon.d/60-aosp-ims.sh" ] && [ ! -e "$S/system/etc/aosp-ims" ]' "uninstall after the update: stack and addon.d script gone"
      case "$SCEN" in
        ota)
          check 'cmp -s "$S/system/build.prop" "$WORK/buildprop-update.txt"' "uninstall: build.prop is the update's own again"
          check 'cmp -s "$P/etc/apns-conf.xml" "$WORK/apns-newrom.xml"' "uninstall: APN list is the update's own again"
          check '[ ! -e "$S/system/etc/permissions/android.hardware.telephony.ims.xml" ]' "uninstall: IMS feature xml removed (the update has none)"
          ;;
        otaalt)
          check 'cmp -s "$S/system/build.prop" "$WORK/buildprop-update-plain.txt"' "uninstall: build.prop untouched"
          check '[ ! -e "$P/etc/apns-conf.xml" ] && cmp -s "$S/system/etc/apns-conf.xml" "$WORK/apns-newrom.xml"' "uninstall: /product APN copy removed, /system list intact"
          check 'cmp -s "$S/system/etc/permissions/android.hardware.telephony.ims.xml" "$WORK/ims-feature-rom.xml"' "uninstall: the update's own IMS feature xml back"
          ;;
      esac
      check '! find "$S" "$P" -name "*aosp-ims*" | grep -q .' "uninstall: no aosp-ims file left"
      check '! find "$S" "$P" -name "*joan*" | grep -q .' "uninstall: nothing named joan left"
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
  "$WORK/zip/etc/permissions" "$WORK/zip/etc/default-permissions" "$WORK/zip/etc/sysconfig" \
  "$WORK/zip/apn" "$WORK/zip/scripts" "$WORK/bin"

# build.prop as joan's nightly has it (incoming calls gated off for the
# modem IMS), as the installer should leave it, and a ROM without the line.
# The LineageOS version is what backuptool checks before it runs addon.d.
BP_HEAD='ro.system.build.version.sdk=35\nro.build.version.sdk=35\nro.lineage.version=22.2-20260920-NIGHTLY-joan\n'
printf "${BP_HEAD}ro.telephony.block_binder_thread_on_incoming_calls=false\n" > "$WORK/buildprop-joan.txt"
printf "${BP_HEAD}ro.telephony.block_binder_thread_on_incoming_calls=true\n" > "$WORK/buildprop-on.txt"
printf "$BP_HEAD" > "$WORK/buildprop-plain.txt"
# The next nightly's, for the update scenarios: gated off again (and as
# the addon.d script should leave it), and without the line.
BP_UPD='ro.system.build.version.sdk=35\nro.build.version.sdk=35\nro.lineage.version=22.2-20260927-NIGHTLY-joan\nro.build.version.incremental=0a1b2c3d4e\n'
printf "${BP_UPD}ro.telephony.block_binder_thread_on_incoming_calls=false\n" > "$WORK/buildprop-update.txt"
printf "${BP_UPD}ro.telephony.block_binder_thread_on_incoming_calls=true\n" > "$WORK/buildprop-update.txt.on"
printf "$BP_UPD" > "$WORK/buildprop-update-plain.txt"
# An update that declares IMS itself, differently from this package.
printf '<?xml version="1.0" encoding="utf-8"?>\n<permissions>\n    <feature name="android.hardware.telephony.ims" />\n    <!-- the update'"'"'s own -->\n</permissions>\n' \
  > "$WORK/ims-feature-rom.xml"

# The OTA's backuptool (tests/fixtures/backuptool) runs the addon.d script
# in the update scenarios. Where the pinned OTA is at hand (E2E_OTA, set in
# CI), the fixture must be the OTA's own, byte for byte.
if [ -n "${E2E_OTA:-}" ]; then
  for f in backuptool.sh backuptool.functions; do
    unzip -p "$E2E_OTA" "install/bin/$f" | cmp -s - "aosp-ims/tests/fixtures/backuptool/$f" \
      || { echo "tests/fixtures/backuptool/$f is not the one in $E2E_OTA"; exit 1; }
  done
  echo "backuptool fixture: the pinned OTA's own"
fi

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
# joan's recovery maps dynamic partitions; backuptool asks, the installer
# does not need to.
printf '#!/bin/sh\n[ "$1" = ro.boot.dynamic_partitions ] && [ -n "$E2E_DYNAMIC" ] && echo true\nexit 0\n' \
  > "$WORK/bin/getprop"
chmod 755 "$WORK/bin/chcon" "$WORK/bin/getprop"

# The package under test. The apk is a stand-in: the installer never
# parses it, only copies and verifies it, and building the real one needs
# the SDK. Everything else is the file that ships.
python3 aosp-ims/tools/make-installer.py scripts "$GEN" >/dev/null
head -c 190000 /dev/urandom > "$WORK/zip/app/ImsStack.apk"
head -c 8530 /dev/urandom > "$WORK/zip/app/ImsStackPhoneOverlay.apk"
head -c 8530 /dev/urandom > "$WORK/zip/app/ImsStackFrameworkOverlay.apk"
head -c 60000 /dev/urandom > "$WORK/zip/app/Iwlan.apk"
head -c 60000 /dev/urandom > "$WORK/zip/app/QualifiedNetworksService.apk"
cp aosp-ims/permissions/privapp-permissions-com.google.android.iwlan.xml "$WORK/zip/etc/permissions/com.google.android.iwlan.xml"
cp aosp-ims/permissions/privapp-permissions-com.android.telephony.qns.xml "$WORK/zip/etc/permissions/com.android.telephony.qns.xml"
cp "$GEN/update-binary" "$WORK/zip/META-INF/com/google/android/update-binary"
cp META-INF/com/google/android/updater-script "$WORK/zip/META-INF/com/google/android/"
cp aosp-ims/permissions/privapp-permissions-com.android.imsstack.xml "$WORK/zip/etc/permissions/com.android.imsstack.xml"
cp permissions/android.hardware.telephony.ims.xml "$WORK/zip/etc/permissions/"
cp aosp-ims/permissions/default-permissions-com.android.imsstack.xml "$WORK/zip/etc/default-permissions/com.android.imsstack.xml"
cp aosp-ims/permissions/sysconfig-com.android.imsstack.xml "$WORK/zip/etc/sysconfig/com.android.imsstack.xml"
cp aosp-ims/permissions/sysconfig-com.google.android.iwlan.xml "$WORK/zip/etc/sysconfig/com.google.android.iwlan.xml"
cp apn/viettel-45204.xml "$WORK/zip/apn/"
cp scripts/merge-viettel-apns.sh "$WORK/zip/scripts/"
mkdir -p "$WORK/zip/addon.d"
cp aosp-ims/zip/addon.d/60-aosp-ims.sh "$WORK/zip/addon.d/"
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
           "$s/system/etc/init" "$s/system/bin" "$s/system/addon.d" "$p/overlay" "$p/etc" \
           "$se/bin" "$se/etc/init" "$s/product" "$s/system_ext"
  # As in joan's system image: mount points for the other partitions at
  # the root, and links to them from system/.
  ln -s /product "$s/system/product"
  ln -s /system_ext "$s/system/system_ext"
  if [ "$sc" = prodtight ]; then
    cp "$WORK/buildprop-plain.txt" "$s/system/build.prop"   # a ROM without the property
  else
    cp "$WORK/buildprop-joan.txt" "$s/system/build.prop"    # joan: incoming calls gated off
  fi
  head -c 50000 /dev/urandom > "$s/system/priv-app/Dummy/Dummy.apk"
  echo '<permissions/>' > "$s/system/etc/permissions/platform.xml"
  if [ "$sc" = syslist ]; then
    cp "$WORK/apns-orig.xml" "$s/system/etc/apns-conf.xml"
  else
    cp "$WORK/apns-orig.xml" "$p/etc/apns-conf.xml"
  fi
  if [ "$sc" = upgrade67 ] || [ "$sc" = joanrefuse ]; then
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
# backuptool mounts product and system_ext at /product and /system_ext, as
# on the phone. The mounts stay in each scenario's namespace; the empty
# mount points it creates are removed again.
MADE_DIRS=""
for d in /product /system_ext; do [ -e "$d" ] || MADE_DIRS="$MADE_DIRS $d"; done
LOOPS=""
cleanup() {
  for l in $LOOPS; do losetup -d "$l" 2>/dev/null || true; done
  [ "$CREATED_DEVBLOCK" = 1 ] && rmdir /dev/block 2>/dev/null || true
  for d in $MADE_DIRS; do rmdir "$d" 2>/dev/null || true; done
}
trap cleanup EXIT

total_fail=0
run_scenario() {
  sc=$1; shell=${2:-mksh}
  mk_img "$WORK/system.img" 24
  mk_img "$WORK/product.img" 16
  mk_img "$WORK/system_ext.img" 8
  rm -f "$WORK/out-$sc.txt" "$WORK/out-$sc-uninstall.txt" "$WORK/out-$sc-ota.txt"
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
    if [ -f "$WORK/out-$sc-ota.txt" ]; then
      echo "  -- backuptool output ($sc, $shell):"; sed 's/^/     | /' "$WORK/out-$sc-ota.txt" | tail -40
    fi
  fi
  losetup -d "$ls" "$lp" "$le"
  LOOPS=""
  for d in $MADE_DIRS; do rmdir "$d" 2>/dev/null || true; done
}

echo "== e2e AOSP IMS installer (generated update-binary on ext4 images)"
for sc in fresh upgrade67 joanrefuse sysfull prodtight reflash rebase syslist uninstall ota otaalt; do
  run_scenario "$sc" mksh
done
# LineageOS recovery runs mksh; TWRP and OrangeFox run busybox ash.
for sc in fresh upgrade67 joanrefuse uninstall ota; do
  run_scenario "$sc" "busybox sh"
done

if [ "$total_fail" -ne 0 ]; then
  echo "e2e AOSP IMS installer tests: FAIL $total_fail"
  exit 1
fi
echo "e2e AOSP IMS installer tests: all passed"
