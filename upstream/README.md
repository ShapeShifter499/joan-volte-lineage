# In-tree LineageOS 22 drop-in
#
# This repository is meant to be included in a LineageOS tree as a vendor
# module, not only flashed as a recovery zip. The zip exists because joan
# already ships nightlies without this; a ROM build is the path that can
# load sepolicy and drop the unauthenticated loopback control socket.
#
# Layout expected by Android.bp / joan-ims.mk (repo root):
#
#     vendor/lge/joan-ims/     (this git)
#       Android.bp
#       joan-ims.mk
#       native/
#       ims-service/
#       overlay/
#       permissions/
#       root/system/etc/init/joan-ims.rc
#       upstream/sepolicy/
#
# device/lge/joan/device.mk (or joan-common.mk):
#
#     $(call inherit-product, vendor/lge/joan-ims/joan-ims.mk)
#
# Then BOARD_SEPOLICY_DIRS and PRODUCT_PACKAGES come from joan-ims.mk.
# Rebuild the ROM; do not also flash the recovery zip on top of that
# image (you would double-install the daemon).
#
# What the zip cannot do, and a ROM build can:
#
#   - Load joan_ims.te so the daemon has its own domain instead of
#     borrowing netmgrd, and so priv_app can connectto the authenticated
#     unix control socket. JOAN_IMS_BRINGUP_TCP_CTL is compiled out in
#     Android.bp for that reason.
#   - Sign JoanIms with the platform certificate (privileged: true).
#   - Ship the static overlay that sets config_ims_mmtel_package.
#
# sepolicy in upstream/sepolicy/ is the intended shape. It has not been
# loaded on a device — a sideloaded zip cannot append policy on joan.
# Iterate against audit2allow on the first ROM boot.
#
# Architecture reminder (do not "fix" this by shipping CAF ims-ship):
# joan's MPSS has no QMI IMSS. SIP lives on the application processor.
# org.codeaurora.ims is the wrong stack for this handset.
