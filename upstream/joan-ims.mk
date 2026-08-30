# Device makefile fragment for the AP-side IMS stack (Java ImsService).
#
# From device/lge/joan/device.mk:
#
#     $(call inherit-product, vendor/lge/joan-ims/joan-ims.mk)

LOCAL_PATH := $(dir $(lastword $(MAKEFILE_LIST)))

PRODUCT_PACKAGES += \
    JoanIms \
    JoanImsPhoneDefault

# IMS feature flag the framework reads to construct ImsResolver.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)../permissions/android.hardware.telephony.ims.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/android.hardware.telephony.ims.xml

# Tells PhoneGlobals which package implements ImsService. Shipped as the
# JoanImsPhoneDefault RRO above, the same apk the zip installs.
