# vendor/lge/joan-ims (LineageOS 22)

AP-side IMS (VoLTE) for the LG V30 (joan). SIP, AKA, and IPsec run in
the Java `ImsService` over public `IpSecManager` APIs. There is no
native daemon and no loopback control socket.

## Inherit

Copy this directory to `vendor/lge/joan-ims` (or keep it as a git
submodule). In `device/lge/joan/device.mk`:

```
$(call inherit-product, vendor/lge/joan-ims/joan-ims.mk)
```

`joan-ims.mk` expects the ImsService sources at `ims-service/` next to
this makefile (this repo's layout). If you vendor only this folder,
point `LOCAL_PATH` at a checkout that also contains `ims-service/` and
`permissions/`.

## Layout

    Android.bp          JoanIms privileged app
    joan-ims.mk         PRODUCT_PACKAGES + overlays + IMS feature xml
    rro/                JoanImsPhoneDefault RRO: config_ims_mmtel_package
    permissions/        telephony.ims feature + privapp allowlist

## Do not

- Do not start a `joan-ims` init service.
- Do not listen on `127.0.0.1:15090`.
- Do not flash the recovery zip on a ROM that already inherits this
  module — you would install the app twice.

## Licence

Apache-2.0. See `LICENSE`.
