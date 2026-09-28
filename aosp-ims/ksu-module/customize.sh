# KernelSU/Magisk installer for the AOSP IMS stack (joan).
# The zip already contains the system/ tree in place; the default
# unzip puts everything into $MODPATH. Only checks and prints here.

if [ "$API" -lt 35 ]; then
  abort "This module targets Android 15 (API 35). This device reports API $API."
fi

DEVICE=$(getprop ro.product.device 2>/dev/null)
ui_print "- Device: ${DEVICE:-unknown}, API $API"
ui_print "- The stack installs systemless: /system is not modified."
ui_print "- Runtime permissions: granted by default-permissions at boot, or"
ui_print "  Settings > Apps > ImsStack > Permissions. The IWLAN tunnel"
ui_print "  app-op is set by service.sh on every boot."
ui_print "- Reboot is required after install or removal."
