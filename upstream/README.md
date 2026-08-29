# vendor/lge/joan-ims

AP-side IMS (VoLTE) stack for the LG V30 (joan) on LineageOS 22.2.

## Why this exists on the application processor

joan's MPSS exposes **no QMI IMSS**. There is no modem-side IMS to talk to,
so `org.codeaurora.ims` and the CAF `ims-ship` stack are the wrong answer for
this handset: SIP, AKA and the IPsec security association all run on the AP.

Registration (REGISTER 200 with AKAv1-MD5 + IPsec transport mode), an
outgoing Dialer call reaching ACTIVE, and two-way PCMU audio have all been
observed on a real device. See `docs/` in the staging tree for the current
call-setup status, which is **not** finished.

## Layout

    Android.bp                       joan-ims-ua + JoanIms
    joan-ims.mk                      inherit from device/lge/joan/device.mk
    native/                          SIP/AKA/IPsec user agent (C)
    ims-service/                     ImsService + MmTelFeature (Java)
    overlay/                         config_ims_mmtel_package
    permissions/                     telephony.ims feature + privapp perms
    sepolicy/                        joan_ims domain
    root/system_ext/etc/init/        joan-ims.rc

## In-tree vs the recovery zip

The out-of-tree zip (`joan-volte-lineage`) exists because joan already ships
nightlies without this. It carries three compromises that a ROM build must
not inherit, and that this module deliberately drops:

| Zip does | Why | In-tree instead |
|---|---|---|
| Daemon runs as `u:r:netmgrd:s0` | a sideloaded zip cannot add sepolicy, and `netmgrd` already holds the xfrm permissions | own `joan_ims` domain |
| App reaches the daemon over an **unauthenticated** TCP listener on `127.0.0.1:15090` | `connectto` on the unix socket is denied to `priv_app` without policy | authenticated unix socket; the listener is compiled out via `-DJOAN_IMS_BRINGUP_TCP_CTL=0` |
| Installs to `/system` | joan's `/vendor` logical partition has no free space for a recovery write | `system_ext` |

**Do not flash the zip on top of a ROM that includes this module** — you
would install the daemon twice.

## Planned simplification: drop the native daemon

The native helper exists on one premise: that a Java `ImsService` cannot
program the kernel IPsec SAs that 3GPP sec-agree requires, because app
domains are blocked from xfrm netlink by a platform `neverallow`.

That premise is **only true of raw xfrm netlink**. `android.net.IpSecManager`
/ `IpSecTransform` are public framework APIs that establish transport-mode
IPsec on a socket, including `allocateSecurityParameterIndex(address,
requestedSpi)` — which is exactly what sec-agree needs, since the UE chooses
spi-c and spi-s. A privileged app can use them with no special SELinux
policy at all.

If that path works on joan, this module collapses to the Java app: no native
binary, no `joan_ims` domain, no `sepolicy/`, no control socket, and the
recovery zip stops needing any of the compromises above. That is the intended
direction; it is not yet proven on this device.

## Licence

Apache-2.0. See `LICENSE`.
