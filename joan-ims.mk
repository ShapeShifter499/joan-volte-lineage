# Device makefile fragment for the AP-side IMS stack (Java ImsService).
#
# Drop this repo in at vendor/lge/joan-ims/, then from
# device/lge/joan/device.mk (or joan-common):
#
#     $(call inherit-product, vendor/lge/joan-ims/joan-ims.mk)
#
# LOCAL_PATH here is this file's directory (the repo root).

LOCAL_PATH := $(dir $(lastword $(MAKEFILE_LIST)))

PRODUCT_PACKAGES += \
    JoanIms \
    JoanImsPhoneDefault

# The IMS feature flag the framework reads before it will construct
# ImsResolver at all. system_ext, matching the app.
#
# org.joan.ims.xml is deliberately NOT copied here: it is installed by the
# privapp-permissions-org.joan.ims.xml prebuilt_etc in Android.bp, which
# puts it on system_ext alongside the app. Copying it here as well
# installed the same allowlist onto two partitions.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)permissions/android.hardware.telephony.ims.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/android.hardware.telephony.ims.xml

# Not set here, because a ROM build has a better mechanism than the zip's:
#
#   config_device_volte_available -> device tree framework-res overlay
#
# The zip ships that as the rro-fw/ overlay because a flashable zip cannot
# edit a device tree. In a ROM build, set it in your own overlay instead
# and do not build rro-fw/. See upstream/VOLTE-PLATFORM-SETUP.md.
