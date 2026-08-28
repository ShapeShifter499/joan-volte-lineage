#!/sbin/sh
# Cleanup zip: remove legacy joan-ims artifacts from /vendor
# Delete binary
rm -f /vendor/bin/joan-ims
# Delete old init rc
rm -f /vendor/etc/init/joan-ims.rc
exit 0
