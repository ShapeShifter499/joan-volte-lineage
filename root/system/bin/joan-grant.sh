#!/system/bin/sh
# Grant the runtime permissions joan needs, once, at boot.
#
# Ordered deliberately: ACCESS_BACKGROUND_LOCATION is refused unless the
# foreground permission is already held, so the coarse/fine pair goes
# first. Every grant is best effort -- a permission the platform does not
# define, or a grant the policy refuses, must not stop the rest.
#
# Nothing here is required for registration or for a call to connect.
# RECORD_AUDIO decides whether the other side hears anything; the
# location permissions decide only whether P-Access-Network-Info can
# carry utran-cell-id-3gpp.
PKG=org.joan.ims
# Once ever, not once per boot.
#
# `on property:sys.boot_completed=1` fires on every boot, so without a
# marker this would re-grant what the user had deliberately revoked, every
# time they rebooted, silently. A permission screen that can be overruled
# by a reboot is not consent. The marker lives where the shell uid can
# write and survives reboots; a factory reset clears it, which is the
# right moment to ask again.
MARK=/data/local/tmp/.joan-grant-done
if [ -f "$MARK" ]; then
    log -t joan-grant "already run once; leaving the user's choices alone"
    exit 0
fi
for p in \
    android.permission.RECORD_AUDIO \
    android.permission.ACCESS_COARSE_LOCATION \
    android.permission.ACCESS_FINE_LOCATION \
    android.permission.ACCESS_BACKGROUND_LOCATION
do
    pm grant "$PKG" "$p" 2>/dev/null || true
done
: > "$MARK" 2>/dev/null || true
log -t joan-grant "runtime permission grants attempted for $PKG"
