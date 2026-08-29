# Device makefile fragment for the AP-side IMS stack.
#
# From device/lge/joan/device.mk:
#
#     $(call inherit-product, vendor/lge/joan-ims/joan-ims.mk)
#
# LOCAL_PATH here is this file's directory (the joan-ims repo root).

LOCAL_PATH := $(dir $(lastword $(MAKEFILE_LIST)))

PRODUCT_PACKAGES += \
    joan-ims-ua \
    JoanIms

# SELinux policy giving the daemon its own domain and letting the app reach
# it over the authenticated unix socket. Without this the app cannot talk to
# the daemon at all -- see README.md.
#
# BOARD_SEPOLICY_DIRS is gone in Android 15. The daemon and app are
# system_ext, so their policy is system_ext private policy, matching how
# device/lge/joan-common declares its own.
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(LOCAL_PATH)sepolicy

# IMS feature flag the framework reads to construct ImsResolver.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)permissions/android.hardware.telephony.ims.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/android.hardware.telephony.ims.xml

# Tells PhoneGlobals which package implements ImsService
# (config_ims_mmtel_package).
PRODUCT_PACKAGE_OVERLAYS += $(LOCAL_PATH)overlay
