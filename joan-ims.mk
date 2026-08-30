# Device makefile fragment. From device/lge/joan/device.mk (or joan-common):
#
#     $(call inherit-product, vendor/lge/joan-ims/joan-ims.mk)
#
# LOCAL_PATH here is this file's directory (the joan-ims repo root).

LOCAL_PATH := $(dir $(lastword $(MAKEFILE_LIST)))

PRODUCT_PACKAGES += \
    JoanIms \
    JoanImsPhoneDefault

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)permissions/android.hardware.telephony.ims.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/android.hardware.telephony.ims.xml \
    $(LOCAL_PATH)permissions/org.joan.ims.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/org.joan.ims.xml
