#!/system/bin/sh
# IWLAN builds its ePDG tunnel only with the MANAGE_IPSEC_TUNNELS app-op
# allowed; installed without the ROM's platform key, only root or adb can
# set it. This runs as root on every boot, so the grant survives module
# updates and app data clears. Idempotent.
MODDIR=${0%/*}
appops set com.google.android.iwlan MANAGE_IPSEC_TUNNELS allow 2>/dev/null
