#!/usr/bin/env bash
# The installer's device-mapper unlock, tested against a fake sysfs.
#
# scripts/update-binary only ever runs inside recovery, so this logic had
# no coverage at all -- which is how a read-only dm node came to be read
# as "the ROM is full" on a partition with fifteen times the free space
# this package needs. The functions are lifted out of the shipped script
# rather than copied, so the test cannot drift away from what installs.
set -euo pipefail
cd "$(dirname "$0")/../.."
SRC=scripts/update-binary
fail=0
check() {
  if [ "$1" = "0" ]; then printf '  ok: %s\n' "$2"
  else printf '  FAIL: %s\n' "$2"; fail=$((fail + 1)); fi
}

# Lift the two functions out of the installer verbatim.
sed -n '/^unlock_device() {/,/^}/p;/^device_of() {/,/^}/p' "$SRC" > /tmp/joan-inst-fns.sh
grep -q 'unlock_device()' /tmp/joan-inst-fns.sh || { echo "  FAIL: unlock_device not found in $SRC"; exit 1; }
grep -q 'device_of()' /tmp/joan-inst-fns.sh || { echo "  FAIL: device_of not found in $SRC"; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
run_case() {
  # run_case <node> <force_ro contents or "none"> ; echoes "rc:value"
  node="$1"; ro="$2"
  rm -rf "$WORK/sys" "$WORK/dev"; mkdir -p "$WORK/dev"
  : > "$WORK/dev/$(basename "$node")"
  if [ "$ro" != "none" ]; then
    mkdir -p "$WORK/sys/block/$(basename "$node")"
    printf '%s' "$ro" > "$WORK/sys/block/$(basename "$node")/force_ro"
  fi
  # Reproduce the function with /sys redirected into the sandbox.
  sed "s#/sys/block/#$WORK/sys/block/#" /tmp/joan-inst-fns.sh > "$WORK/fns.sh"
  # shellcheck disable=SC1090
  rc=$( . "$WORK/fns.sh"; unlock_device "$WORK/dev/$(basename "$node")" >/dev/null 2>&1; echo $? )
  val="-"
  [ -f "$WORK/sys/block/$(basename "$node")/force_ro" ] &&
    val=$(cat "$WORK/sys/block/$(basename "$node")/force_ro")
  echo "$rc:$val"
}

why_case() {
  # why_case <node> <force_ro contents or "none"> ; echoes UNLOCK_WHY
  node="$1"; ro="$2"
  rm -rf "$WORK/sys" "$WORK/dev"; mkdir -p "$WORK/dev"
  : > "$WORK/dev/$(basename "$node")"
  if [ "$ro" != "none" ]; then
    mkdir -p "$WORK/sys/block/$(basename "$node")"
    printf '%s' "$ro" > "$WORK/sys/block/$(basename "$node")/force_ro"
  fi
  sed "s#/sys/block/#$WORK/sys/block/#" /tmp/joan-inst-fns.sh > "$WORK/fns.sh"
  # shellcheck disable=SC1090
  ( . "$WORK/fns.sh"; unlock_device "$WORK/dev/$(basename "$node")" >/dev/null 2>&1
    printf '%s' "$UNLOCK_WHY" )
}

# UNLOCK_WHY has to name which of the four refusal paths was taken. An
# InfinityX report on alpha49 said the dm node "could not be cleared"
# when the message could not actually distinguish that from there being
# no dm node, no force_ro, or a node already writable -- so one
# screenshot was not enough to diagnose it.
w=$(why_case dm-3 1)
case "$w" in *cleared*) check 0 "why reports a successful clear (got $w)";;
  *) check 1 "why reports a successful clear (got $w)";; esac

w=$(why_case dm-3 0)
case "$w" in *already-writable*) check 0 "why distinguishes an already-writable node (got $w)";;
  *) check 1 "why distinguishes an already-writable node (got $w)";; esac

w=$(why_case dm-3 none)
case "$w" in *no-force_ro-in-sysfs*) check 0 "why distinguishes a missing force_ro (got $w)";;
  *) check 1 "why distinguishes a missing force_ro (got $w)";; esac

w=$(why_case sda7 1)
case "$w" in *not-a-dm-node*) check 0 "why distinguishes a non-dm device (got $w)";;
  *) check 1 "why distinguishes a non-dm device (got $w)";; esac

# A read-only dm node is the case that matters: it must be flipped.
r=$(run_case dm-3 1)
check "$([ "$r" = "0:0" ] && echo 0 || echo 1)" \
      "a read-only dm node is cleared and reports success (got $r)"

# Already writable: nothing to do, and it must not claim it acted.
r=$(run_case dm-3 0)
check "$([ "${r%%:*}" = "1" ] && echo 0 || echo 1)" \
      "an already-writable dm node is left alone (got $r)"

# No force_ro attribute at all -- older kernels, or not really a dm node.
r=$(run_case dm-9 none)
check "$([ "${r%%:*}" = "1" ] && echo 0 || echo 1)" \
      "a dm node without force_ro is refused, not guessed at (got $r)"

# A real partition is not a dm node and must be left completely alone:
# static-partition devices have nothing to unlock and must not be touched.
r=$(run_case sda17 1)
check "$([ "$r" = "1:1" ] && echo 0 || echo 1)" \
      "a non-dm block device is ignored and left read-only (got $r)"

# device_of pulls the backing device out of mount output, with no awk.
out=$(
  . /tmp/joan-inst-fns.sh
  mount() {
    printf '%s\n' \
      "/dev/block/dm-0 on /system_root type ext4 (ro,seclabel,relatime)" \
      "/dev/block/dm-3 on /mnt/joan_system type ext4 (rw,seclabel)" \
      "/dev/block/dm-5 on /mnt/joan_product type ext4 (rw,seclabel)"
  }
  device_of /mnt/joan_system
)
check "$([ "$out" = "/dev/block/dm-3" ] && echo 0 || echo 1)" \
      "device_of finds the backing device for a mount point (got $out)"
# grep exits 1 on no match; the installer runs without set -e, so an
# unmounted point simply yields the empty string there. Reproduce that
# rather than letting this harness's own set -e turn it into a crash.
out=$(
  . /tmp/joan-inst-fns.sh
  mount() { printf '%s\n' "/dev/block/dm-0 on /system_root type ext4 (ro)"; }
  device_of /mnt/joan_system || true
) || true
check "$([ -z "$out" ] && echo 0 || echo 1)" \
      "device_of is empty for a mount point that is not mounted (got '$out')"

if [ "$fail" -ne 0 ]; then
  echo "installer tests: FAIL $fail"; exit 1
fi
echo "installer tests: all passed"
