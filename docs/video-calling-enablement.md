# Video calling (ViLTE): what enabling it takes

Written 2026-09-27 (Fulgor, ZCode:GLM-5.3-Flash) after verifying the
gating in the Android 17 sources (`aosp-ims/work/src/ImsStack`,
`android-17.0.0_r1`, commit `1e3981c2`). Alpha1 ships with video
**off**: flipping carrier flags before the media path is proven would
put a video button on users' dialers that makes broken calls.

## How ImsStack gates video

Same pattern as VoLTE, entirely AP-side — no RCS or Google-services
involvement:

- The MMTel feature registers `CAPABILITY_TYPE_VIDEO` like any other
  capability (`imsservice/mmtel/ImsFeatureManager.enableFeature(...)`,
  `ImsRegistrationTracker`); the Dialer shows the video button when the
  registered capabilities include it.
- The device-level gate is `core/config/ServiceCaps.isVtEnabledByDevice()`:
  framework resource **`config_device_vt_available`**, overridable at
  the bench with `persist.dbg.vt_avail_ovr=1` — exactly the trap
  documented for `config_device_volte_available` in
  `upstream/VOLTE-PLATFORM-SETUP.md`.
- Carrier-level capability key:
  `CarrierConfigManager.Ims.KEY_CAPABILITY_TYPE_VIDEO_INT_ARRAY`
  (`ims.capability_type_video_int_array`). Our importer keeps `ims.`
  keys, but Google's Pixel data carries **none** (0 hits in
  `aosp-ims/carrier/lineage-pixel-ims.xml`) — the carrier admission has
  to come from our own rules or AOSP's default asset. What Google's
  data does carry: the QNS video handover thresholds
  (`qns.video_eutran_rsrp_int_array`, `qns.video_ngran_ssrsrp_int_array`,
  `qns.video_wifi_rssi_int_array`) — already imported.

## The enablement package

1. `config_device_vt_available=true` in the framework overlay: the zip's
   `zip/rro-fw/res/values/config.xml`, and the device patch's
   `overlay/frameworks/base/core/res/res/values/config.xml`.
2. Per-carrier VT admission: verify which key AOSP 15/17 CarrierConfig
   uses for video availability on the target carriers, add to the
   filterless block or per-carrier data, extend
   `tests/check-carrier-config.py` with the same key.
3. `CAMERA` into the media process's default permissions
   (`rootdir/system_ext/etc/default-permissions/default-permissions-ims.xml`
   in the device patch; `zip/grant-permissions.sh` + the
   `default-permissions` file in the zip). The signature-only
   `ACCESS_SURFACE_FLINGER` concern applies to video surfaces — the
   path may still need the platform key (source build) or a workaround.
4. Rebuild, then the bench ladder below.

## Bench test ladder

Each step gates the next; stop at the first failure and take logs.

1. **Capability advertisement**: after IMS registers,
   `adb shell dumpsys telephony.registry | grep -i video` and
   `dumpsys telecom` show the video capability on the phone account;
   the Dialer renders the video-call button.
2. **Signaling**: place a video INVITE (to a peer that answers; a
   second joan on the bench, or a carrier VoLTE number that ignores
   video). Expected without a working media path: SIP 200 with a video
   answer or a graceful fallback to audio — logcat
   `imsmedia|ImsMediaFramework|VideoSession`.
3. **Media**: both directions' video frames flow; `dumpsys media.metrics`
   and the ImsMedia trace show the video session bound, and the camera
   is opened by the media process
   (`dumpsys media.camera | grep -A2 <uid>`).
4. **ViLTE over Wi-Fi**: same on the IWLAN transport, once QNS moves the
   session.

Consumer ViLTE is carrier-dependent and being retired in the US; steps
2-4 may need a second bench phone (two V30s on the same carrier) rather
than a live carrier peer.
