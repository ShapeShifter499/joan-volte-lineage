# Unmodified Android 17 IMS for joan, source-build shape.
# This file selects packages only; it does not patch ImsStack or admit a
# carrier configuration.

PRODUCT_PACKAGES += \
    ImsMediaService \
    ImsStack \
    Iwlan \
    QualifiedNetworksService

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.telephony.ims.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.telephony.ims.xml
