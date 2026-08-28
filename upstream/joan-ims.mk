# Device makefile fragment. Include from device/lge/joan/device.mk:
#
#     $(call inherit-product, path/to/joan-ims.mk)

PRODUCT_PACKAGES += \
    joan-ims-ua \
    JoanIms

# SELinux policy for the daemon domain. Without joan_ims.te the app cannot
# reach the daemon at all -- see upstream/README.md.
BOARD_SEPOLICY_DIRS += path/to/upstream/sepolicy

# The IMS feature declaration the framework reads to bind an ImsService.
PRODUCT_COPY_FILES += \
    path/to/overlay/joan-ims-features.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/joan-ims-features.xml
