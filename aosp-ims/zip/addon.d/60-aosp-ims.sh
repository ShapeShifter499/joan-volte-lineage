#!/sbin/sh
#
# ADDOND_VERSION=3
#
# /system/addon.d/60-aosp-ims.sh
#
# Keeps the AOSP IMS stack (com.android.imsstack, IWLAN and QNS) across
# LineageOS updates. A LineageOS update rewrites system, product and
# system_ext; its backuptool runs this before and after. The files the
# aosp-ims zip installed are backed up and put back, and the two edits it
# made to the ROM's own files are made again on the update's: the
# incoming-call line in build.prop and Viettel's rows in the APN list.
# Afterwards the phone is as if the zip had been flashed onto the update,
# and the zip's uninstaller works on it the same way.
#
# Installed by the aosp-ims zip and the repacked ROM; the uninstall zip
# removes it. Version 3: backuptool mounts product and system_ext first,
# and product is reached through the system image's product link.

. /tmp/backuptool.functions

list_files() {
cat <<EOF
priv-app/ImsStack/ImsStack.apk
priv-app/Iwlan/Iwlan.apk
priv-app/QualifiedNetworksService/QualifiedNetworksService.apk
etc/permissions/com.android.imsstack.xml
etc/permissions/com.google.android.iwlan.xml
etc/permissions/com.android.telephony.qns.xml
etc/default-permissions/com.android.imsstack.xml
etc/sysconfig/com.android.imsstack.xml
etc/sysconfig/com.google.android.iwlan.xml
etc/aosp-ims/merge-viettel-apns.sh
etc/aosp-ims/viettel-45204.xml
product/overlay/ImsStackPhoneOverlay.apk
product/overlay/ImsStackFrameworkOverlay.apk
EOF
}

# What the installation had, beyond its files, kept with the backup.
FLAGS=$C/aosp-ims
IC=ro.telephony.block_binder_thread_on_incoming_calls
FEAT=$S/etc/permissions/android.hardware.telephony.ims.xml
APNS=$S/product/etc/apns-conf.xml
CON=u:object_r:system_file:s0

say() {
  echo "aosp-ims: $1"
}

# put <src> <dst>: written beside <dst>, compared, renamed over it. On any
# failure <dst> is left as it was and the temporary file removed.
put() {
  rm -f "$2.aosp-ims-new"
  if cat "$1" > "$2.aosp-ims-new" 2>/dev/null && cmp -s "$1" "$2.aosp-ims-new" \
      && chmod 644 "$2.aosp-ims-new" && mv -f "$2.aosp-ims-new" "$2"; then
    chcon "$CON" "$2" 2>/dev/null
    return 0
  fi
  rm -f "$2.aosp-ims-new"
  return 1
}

# mark <file> <text>
mark() {
  echo "$2" > "$1" && chmod 644 "$1" && { chcon "$CON" "$1" 2>/dev/null; true; }
}

# The installer's checksum, from whichever tool this recovery has.
sum_of() {
  so=$(md5sum "$1" 2>/dev/null | cut -d" " -f1)
  [ -n "$so" ] || so=$(cksum "$1" 2>/dev/null | cut -d" " -f1,2 | tr " " ":")
  echo "$so"
}

size_of() {
  wc -c < "$1" | tr -d ' '
}

backup() {
  list_files | while read -r f; do
    backup_file "$S/$f"
  done
  [ -f "$S/priv-app/ImsStack/ImsStack.apk" ] || return 0
  mkdir -p "$FLAGS"
  # The IMS feature file, where the installer recorded that it put it.
  if { [ -f "$FEAT.joan-added" ] || [ -f "$FEAT.joan-orig" ]; } && [ -f "$FEAT" ]; then
    cp "$FEAT" "$FLAGS/ims-feature.xml"
  fi
  [ -f "$S/etc/aosp-ims-incoming-calls.flipped" ] && mark "$FLAGS/incoming-calls" 1
  if [ -f "$APNS.joan-merged" ] || [ -f "$APNS.joan-added" ]; then
    mark "$FLAGS/apns" 1
  fi
}

restore() {
  list_files | while read -r f; do
    [ -f "$C/$S/$f" ] && restore_file "$S/$f"
  done
}

# As the installer decides it: the file is ours where the update has none
# (or the same), else the update's is kept aside for the uninstaller.
restore_feature() {
  src=$FLAGS/ims-feature.xml
  [ -f "$src" ] || return 0
  if [ ! -f "$FEAT" ] || cmp -s "$FEAT" "$src"; then
    put "$src" "$FEAT" && mark "$FEAT.joan-added" joan
  else
    put "$FEAT" "$FEAT.joan-orig" && put "$src" "$FEAT"
  fi || say "IMS feature file not restored"
}

# The update's build.prop gates incoming calls off again (joan's tree
# does, for the modem IMS this replaces): one line flipped, as the
# installer does, and the marker the uninstaller flips it back by.
restore_incoming_calls() {
  [ -f "$FLAGS/incoming-calls" ] || return 0
  bp=$S/build.prop
  grep -q "^$IC=false\$" "$bp" || return 0
  sed "s/^$IC=false\$/$IC=true/" "$bp" > /tmp/aosp-ims-build.prop
  # false -> true: one byte shorter, and nothing else changed.
  if [ "$(size_of /tmp/aosp-ims-build.prop)" = "$(($(size_of "$bp") - 1))" ] \
      && [ "$(grep -c "^$IC=true\$" /tmp/aosp-ims-build.prop)" = 1 ] \
      && put /tmp/aosp-ims-build.prop "$bp"; then
    mark "$S/etc/aosp-ims-incoming-calls.flipped" "$IC=false"
    say "build.prop: framework incoming-call handling on"
  else
    say "build.prop left as the update ships it"
  fi
  rm -f /tmp/aosp-ims-build.prop
}

# Viettel 45204's IMS and XCAP rows, merged into the update's own APN
# list with the installer's checks, and its backup and marker.
restore_apns() {
  [ -f "$FLAGS/apns" ] || return 0
  merge=$S/etc/aosp-ims/merge-viettel-apns.sh
  rows=$S/etc/aosp-ims/viettel-45204.xml
  [ -f "$merge" ] && [ -f "$rows" ] || return 0
  if [ -f "$APNS" ]; then
    base=$APNS
  elif [ -f "$S/etc/apns-conf.xml" ]; then
    base=$S/etc/apns-conf.xml
  else
    return 0
  fi
  out=/tmp/aosp-ims-apns.xml
  rm -rf /tmp/aosp-ims-apn-blocks
  if MERGE_TMP=/tmp/aosp-ims-apn-blocks sh "$merge" "$base" "$rows" > "$out" \
      && grep -q 'joan-viettel-45204-begin' "$out" \
      && sed -n '/joan-viettel-45204-begin/,/joan-viettel-45204-end/p' "$out" \
          | grep -q 'apn="ims"' \
      && sed -n '/joan-viettel-45204-begin/,/joan-viettel-45204-end/p' "$out" \
          | grep -q 'apn="xcap"' \
      && [ "$(grep -c 'apn="v-internet"' "$out")" = 1 ] \
      && [ "$(size_of "$out")" -ge "$(size_of "$base")" ]; then
    if [ "$base" = "$APNS" ]; then
      if put "$base" "$APNS.joan-orig"; then
        if put "$out" "$APNS"; then
          mark "$APNS.joan-merged" "$(sum_of "$APNS")"
          say "APN list: Viettel 45204's IMS rows merged into the update's list"
        else
          rm -f "$APNS.joan-orig"
          say "APN list left as the update ships it (no room on product)"
        fi
      fi
    elif mkdir -p "$(dirname "$APNS")" && put "$out" "$APNS"; then
      mark "$APNS.joan-added" joan
      say "APN list: Viettel 45204's IMS rows merged into a /product copy"
    else
      say "APN list left as the update ships it (no room on product)"
    fi
  else
    say "APN list left as the update ships it (merge failed)"
  fi
  rm -rf "$out" /tmp/aosp-ims-apn-blocks
}

post_restore() {
  [ -d "$FLAGS" ] || return 0
  for d in priv-app/ImsStack priv-app/Iwlan priv-app/QualifiedNetworksService \
           etc/default-permissions etc/sysconfig etc/aosp-ims; do
    [ -d "$S/$d" ] || continue
    chmod 755 "$S/$d"
    chcon "$CON" "$S/$d" 2>/dev/null
  done
  restore_feature
  restore_incoming_calls
  restore_apns
}

case "$1" in
  backup)
    backup
  ;;
  restore)
    restore
  ;;
  pre-backup)
    # Stub
  ;;
  post-backup)
    # Stub
  ;;
  pre-restore)
    # Stub
  ;;
  post-restore)
    post_restore
  ;;
esac
