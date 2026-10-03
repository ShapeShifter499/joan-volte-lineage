# Unmodified Android 17 IMS experiment

This experiment follows the boundary Hansol described: do not backport or
patch ImsStack. The Android 17 stack should remain unmodified, while the
V30-specific accommodation lives outside it.

This is not a release. Nothing here has been packed, flashed, built into
LineageOS, or tested on a phone.

## What krazey actually does

The relevant public fork is:

https://github.com/krazey/ImsStack

Its current tree is based on AOSP Android 17 and is not a source backport.
For Android 16, its README selects one Soong configuration variable:

```make
$(call soong_config_set_bool,imsstack_namespace,use_android16_telephony_compat,true)
```

`java/Android.bp` uses that variable to compile one of two files:

- Android 17: `DomainSelectionEmergencyModeMonitor.java`
- Android 16: `DomainSelectionEmergencyModeMonitorCompat.java`

Both expose the same small class and methods. The Android 16 file is a
no-op adapter because Android 16 lacks the Android 17 domain-selection
emergency callback. That is a build-time file selection, not a runtime
overlay and not a line-by-line backport of the stack.

The same fork also exposes product overlay resources, including
`config_imsstack_dedicated_bearer_qos_supported`. Setting it false disables
dedicated-bearer QoS waits and allows voice on the default bearer. That is
the closest public match to Hansol's "bypass a QCI timeout" description,
but it is a fork-owned feature. It is not present in unmodified upstream
AOSP ImsStack, so this experiment does not copy it.

## The two intended layouts

- `zip-overlay/` is reserved for a recovery zip that can change V30
  configuration without changing ImsStack.
- `source-tree/` is reserved for the same configuration when someone builds
  LineageOS from source.

They are intentionally empty. No V30-only property or HAL shim has been
promoted into either layout yet.

## Not claimed

- No radio HAL 1.6 implementation or compatibility shim is included.
- No QCI or dedicated-bearer workaround is included.
- No compatibility with LineageOS 22.2 has been established. Android 15 may
  need more than krazey's Android 16 emergency-monitor adapter.
- The older patched branch remains available, but it is not the model for
  this experiment.
