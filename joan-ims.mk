# Device makefile fragment. From device/lge/joan/device.mk (or joan-common):
#
#     $(call inherit-product, vendor/lge/joan-ims/joan-ims.mk)
#
# LOCAL_PATH here is this file's directory (the joan-ims repo root).

LOCAL_PATH := $(dir $(lastword $(MAKEFILE_LIST)))

PRODUCT_PACKAGES += \
    joan-ims-ua \
    JoanIms

# SELinux policy for the daemon domain. Without joan_ims.te the app cannot
# reach the daemon at all — see upstream/README.md.
BOARD_SEPOLICY_DIRS += $(LOCAL_PATH)upstream/sepolicy

# IMS feature flag the framework reads to construct ImsResolver.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)permissions/android.hardware.telephony.ims.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.telephony.ims.xml \
    $(LOCAL_PATH)permissions/org.joan.ims.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/org.joan.ims.xml

# Tells PhoneGlobals which package implements ImsService.
PRODUCT_PACKAGE_OVERLAYS += $(LOCAL_PATH)overlay
