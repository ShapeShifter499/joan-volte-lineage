# VoWiFi (Wi-Fi Calling) on joan — feasibility, measured 2026-08-29

Status: **not implemented, and not yet proven possible.** This records what
was measured so the next attempt does not start from guesswork.

## What VoWiFi actually needs

Over LTE the phone is already inside the carrier network, so IMS traffic
goes straight to the P-CSCF. Over Wi-Fi it is on the open internet, so it
must first build an IKEv2/IPsec tunnel to the carrier's **ePDG** (Evolved
Packet Data Gateway), authenticating with the SIM over EAP-AKA. Once that
tunnel is up the phone is logically inside the carrier network again and
the *same* IMS registration and SIP calling runs through it.

So VoWiFi is the VoLTE stack we already have, plus an ePDG tunnel in front
of it. The SIP UA barely changes: bind to the tunnel interface instead of
`rmnet_dataN`, and take the P-CSCF from the IKE config payload instead of
from PCO.

## Measured on device

| Probe | Result |
|---|---|
| `lshal \| grep iwlan` | `vendor.qti.hardware.data.iwlan@1.0::IIWlan/slot1` and `/slot2` registered, 0 clients |
| `pidof vendor.qti.iwlan` | **NOT RUNNING** (package installed and enabled, never started) |
| `find /vendor -iname '*epdg*' -o -iname '*ike*'` | **nothing** |
| LG IMS blobs (`libims.lge.so`, `Ims6.apk`) | absent — LineageOS strips `com.lge.ims` |
| IMS APN network types | `LTE\|IWLAN\|LTE_CA\|NR` — already provisioned for IWLAN |
| `NetworkRegistrationInfo` WLAN slot | present, `accessNetworkTechnology=IWLAN`, `NOT_REG_OR_SEARCHING` |
| CarrierConfig as shipped | `carrier_wfc_ims_available_bool=false`, `carrier_default_wfc_ims_enabled_bool=false`, `carrier_wfc_supports_wifi_only_bool=false` |
| WFC force-enabled via `cmd phone cc set-value` + `wfc_ims_enabled=1` | **zero** IWLAN networks, **zero** `iwlan\|epdg\|ike` log lines |

The last row is the important one, and it is **not** a verdict. The
framework does not request IMS-over-WLAN until an `ImsService` advertises
`REGISTRATION_TECH_IWLAN`, and ours does not. Nothing tried, so nothing
failed. All of the above was reverted; the overrides were non-persistent.

## The shape of the problem is the VoLTE story again

Stock LG did IMS on the application processor (`libims.lge.so` /
`TMUSAoSRegistration.cpp`), LineageOS strips it, and the gap has to be
rebuilt on the AP. The T-Mobile V30 shipped working Wi-Fi Calling, so the
OEM solved ePDG somewhere — and since no ePDG blob survives in Lineage's
vendor partition, the most likely answer is that LG did that on the AP too.

The framework scaffolding and the carrier provisioning are both already
present. The implementation is what is missing.

## Plan, cheapest first

1. **CarrierConfig** — flip `carrier_wfc_ims_available_bool`,
   `carrier_default_wfc_ims_enabled_bool`,
   `carrier_wfc_supports_wifi_only_bool` and the `wfc_ims_mode_int`
   defaults. Overlay or `cc set-values-from-xml`. Makes the Wi-Fi Calling
   toggle exist. Cheap.
2. **Advertise IWLAN from our ImsService** — declare
   `REGISTRATION_TECH_IWLAN` and add a WLAN entry to
   `ImsFeatureConfiguration`. **This is the next decisive experiment**: it
   is small, zip-deployable, and it is what makes the framework actually
   ask for a tunnel. Whether the QTI stack then attempts an ePDG bring-up
   or stays silent is the answer we do not have.
3. **The ePDG tunnel** — the real project. Either the QTI path
   (`vendor.qti.iwlan` + the `IIWlan` HAL, which on msm8998 normally
   delegates to the modem — the same modem with no IMSS), or AOSP's
   `packages/services/Iwlan`, which builds the tunnel on the AP with
   `android.net.ipsec.ike` (IKEv2 + EAP-AKA) and needs
   `MANAGE_IPSEC_TUNNELS`, a signature|privileged permission our
   platform-signed app already qualifies for. Switching between them is
   the three `config_wlan_*_service_package` /
   `config_qualified_networks_service_package` overlay strings in
   `device/lge/joan-common`.
4. **ePDG discovery** — DNS for
   `epdg.epc.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org` plus the carrier-config
   ePDG address keys.
5. **The SIP UA** — bind to the tunnel interface; P-CSCF from the IKE
   config payload. AKA, sec-agree, dialog handling all unchanged.

Steps 1, 2, 4 and 5 are modest and zip-deployable. Step 3 is the project,
and if AOSP `IwlanService` is required the zip has to ship it, since it is
not installed today.

## Do not

- Do not assume the QTI IWLAN HAL being registered means a tunnel can be
  built. It is alive with zero clients and its userspace service is not
  even running. Structural presence is not functional capability — this
  device has already taught that lesson once.
- Do not read "no QMI IMSS" as "no ePDG". They are separate services; the
  modem may well do one and not the other.
