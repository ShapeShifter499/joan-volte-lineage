# When the zip will not install

Two failures account for every install report we have had. Neither is
what its error message first looks like, so both are written down here
with the evidence that settled them.

## 1. "It installed but nothing happened" — a stale zip

By far the most common, and not an install failure at all.

GitHub's `/releases/latest` served **v0.3.0** long after newer builds
existed, because later releases were marked pre-release. Two separate
users installed it from that link. v0.3.0 predates the whole capability
layer, so it binds, reports `registered`, and still cannot place a call:
`capabilities={ }`, no ImsPhone, and the dialer falls back to CS.

Check the build the phone actually reports, never the file name:

```sh
adb shell content query --uri content://org.joan.ims.state/state
```

`build=2026-08-29-imsservice-only` is the v0.3.0 marker — that string was
hardcoded in `JoanStateProvider` until 2026-08-31. Anything current
reports a version name and code instead, e.g. `0.4.0-alpha34 (44)`.

If it says the old marker, the fix is a current zip, not debugging.

## 2. "Not enough space" — almost certainly a read-only partition

The installer refuses rather than half-installing, which is correct. But
one of its refusals used to name free space in a way that reads like a
verdict:

```
system is writable but the write failed (3220 KB free); cannot install
```

That branch does **not** mean the partition is full. It means the mount
did not report `ro` and a five-byte write failed anyway.

### The worked example

An InfinityX build (Android 15, SDK 35 — new enough) hit this, and the
ROM was blamed. Measured afterwards from the ext4 superblocks in its own
zip:

| filesystem | free | free inodes |
| --- | --- | --- |
| `system` | 3.14 MiB | 132 |
| `product` | 4.44 MiB | 191 |
| `system_ext` | 1.93 MiB | 73 |

This package is about **210 KB and 8 inodes** — the APK, two small RRO
overlays and two permission XMLs. It fits in the tightest of those
fifteen times over. That ROM also uses dynamic partitions with **2.25 GiB
still unallocated inside `super`**. Space was never the constraint.

### What it actually is

A logical partition is a dm-linear device, and the bootloader hands it
over with `force_ro` set. Nothing in a sideload clears it. The filesystem
then mounts and *reports* read-write while every write returns `EROFS` —
which is exactly the message above.

The installer now clears that flag itself (`unlock_device` in
`scripts/update-binary`), through sysfs and `blockdev` both, and retries
the write once before giving up. **So the first thing to try is simply a
current zip.** The probe below is for when that still fails.

## Probe: what state are the partitions really in

Run in recovery, over `adb shell` or a recovery terminal. Block 1 is
read-only. Block 2 writes a five-byte file and deletes it.

```sh
for p in system product system_ext; do
  d=/dev/block/mapper/$p
  [ -b "$d" ] || d=/dev/block/by-name/$p
  [ -b "$d" ] || { echo "== $p  NO DEVICE"; continue; }
  r=$(readlink -f "$d")
  echo "== $p  dev=$r  force_ro=$(cat /sys/block/${r##*/}/force_ro 2>/dev/null || echo n/a)"
done
echo "--- mounts ---"
mount | grep -Ei "system|product|vendor"
echo "--- free KB ---"
df -k | grep -Ei "system|product|Filesystem"
echo "--- free inodes ---"
df -i | grep -Ei "system|product|Filesystem"
```

Reading it:

- **`force_ro=1` on a `/dev/block/dm-*` device** is the answer, and a
  current installer clears it. If an older zip was used, this is why it
  failed.
- **`force_ro=0`, or no dm device**, means something else — the `mount`
  line is then the thing to look at, specifically whether the options
  contain `ro`.
- The `df` lines are there to close the space question, not open it.
  Compare them against 210 KB and 8 inodes before believing "full".

Block 2, only if block 1 showed `force_ro=1` and you want confirmation
before cutting a build:

```sh
d=$(readlink -f /dev/block/mapper/system)
echo "before: $(cat /sys/block/${d##*/}/force_ro 2>/dev/null)"
echo 0 > /sys/block/${d##*/}/force_ro 2>/dev/null
blockdev --setrw "$d" 2>/dev/null
echo "after:  $(cat /sys/block/${d##*/}/force_ro 2>/dev/null)"

m=$(mount | grep " $d " | head -1 | sed 's/^[^ ]* on \(.*\) type .*/\1/')
if [ -z "$m" ]; then mkdir -p /mnt/joan_probe; mount -o rw "$d" /mnt/joan_probe && m=/mnt/joan_probe; own=1; fi
echo "mounted at: ${m:-NOT MOUNTED}"
[ -n "$m" ] && mount -o rw,remount "$m" 2>/dev/null
if [ -n "$m" ] && echo joan > "$m/.joan_probe" 2>/dev/null; then
  echo "WRITE OK"; rm -f "$m/.joan_probe"
else
  echo "WRITE STILL FAILS"
fi
[ "${own:-0}" = "1" ] && umount /mnt/joan_probe 2>/dev/null
```

`WRITE OK` after `after: 0` confirms it. `WRITE STILL FAILS` means the
read-only-node theory is wrong for that device and the `mount` options
from block 1 are the next thing to read.

## If the partition genuinely has no room

It has not happened yet, and the numbers above are why it is unlikely.
If it ever does, in rough order of effort:

- **`/product/priv-app`** usually has more room than `/system`, and LG's
  own `Ims6.apk` ships at `/product/priv-app/Ims6/Ims6.apk` on this
  handset — a proven location for a privileged ImsService here.
- **A systemless module** (Magisk/KernelSU) overlays `priv-app` and the
  permission XMLs from `/data` and touches no partition at all.
- **Grow the logical partition** — `lptools resize` plus `resize2fs`,
  into `super`'s unallocated space. Real, and absurd for 210 KB.
- **A ROM with normal headroom**, such as official LineageOS 22.2.
