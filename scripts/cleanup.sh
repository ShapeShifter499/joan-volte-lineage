#!/sbin/sh
# Cleanup installer for legacy joan‑ims in /vendor
# This runs in Lineage recovery (adb sideload).
set -e

# Remount /vendor rw (dynamic partitions may need special handling)
mount -o rw,remount /vendor 2>/dev/null || true

# Delete legacy files if they exist
if [ -f /vendor/bin/joan-ims ]; then
  rm -f /vendor/bin/joan-ims
  echo "Removed /vendor/bin/joan-ims"
fi
if [ -f /vendor/etc/init/joan-ims.rc ]; then
  rm -f /vendor/etc/init/joan-ims.rc
  echo "Removed /vendor/etc/init/joan-ims.rc"
fi

sync
echo "Legacy joan‑ims cleanup complete."
exit 0
