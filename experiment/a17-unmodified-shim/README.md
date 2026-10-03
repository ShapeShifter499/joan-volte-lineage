# Unmodified Android 17 IMS experiment

This experiment shapes a LineageOS 22.2 / Android 15 integration while
following Hansol's boundary: do not backport or patch ImsStack. The
Android 17 stack remains unmodified. V30-specific accommodation belongs
outside it.

This is not a release. Nothing here has been packed, flashed, built into
a LineageOS tree, or tested on a phone.

## Proposed source-build shape

Copy `source-tree/joan-ims-unmodified.mk` into a joan-common product
makefile, or inherit it from there:

```make
$(call inherit-product-if-exists, packages/modules/ImsMedia/imsmedia.mk)
include device/lge/joan-common/joan-ims-unmodified.mk
```

The makefile only asks the tree for the components that keep ImsStack
unmodified: `ImsStack`, `ImsMediaService`, `Iwlan`,
`QualifiedNetworksService`, and the IMS feature permission. A full tree
still has to supply Android 17 `packages/modules/ImsStack` and the paired
Android 17 `packages/modules/ImsMedia`.

The framework and Telephony overlays are examples to add to
`device/lge/joan-common/overlay`:

- `source-tree/overlay/frameworks/base/core/res/res/values/config.xml`
- `source-tree/overlay/packages/services/Telephony/res/values/config.xml`

They select AOSP's IWLAN/QNS packages and make `com.android.imsstack`
the MMTEL and RCS package. They do not admit a carrier and do not alter a
radio HAL.

## What krazey actually does

The public fork is https://github.com/krazey/ImsStack. Its current tree
is based on AOSP Android 17 and is not a source backport. For Android 16,
one Soong variable selects a compatibility source file:

```make
$(call soong_config_set_bool,imsstack_namespace,use_android16_telephony_compat,true)
```

`java/Android.bp` then compiles
`DomainSelectionEmergencyModeMonitorCompat.java` instead of the Android
17 implementation. That is a build-time source selection, not a runtime
overlay and not a stack backport. LineageOS 22.2 is Android 15, so that
Android 16 adapter is evidence of a technique, not proof that Android 15
needs only the same adapter.

krazey also exposes `config_imsstack_dedicated_bearer_qos_supported` to
disable dedicated-bearer QoS waits. That is fork-owned and is not in
unmodified upstream AOSP ImsStack, so it is intentionally absent here.

## Recovery-zip side

`zip-overlay/` remains intentionally empty. A recovery zip can install
configuration, but it cannot reproduce a build-time source selection or
claim that the result is an unmodified stack.

## Not claimed

- No radio HAL 1.6 implementation or compatibility shim is included.
- No QCI or dedicated-bearer workaround is included.
- No carrier configuration is included.
- Android 15 API compatibility has not been established. That requires
  syncing the pinned Android 17 projects into a real LineageOS 22.2 tree
  and letting Soong report the first missing API or dependency.
