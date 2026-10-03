# Unmodified Android 17 IMS experiment

This is an experiment, not a release. It starts from the AOSP IMS alpha1
work and asks a narrower question: can the V30 keep Android 17 ImsStack
unmodified, and move the V30-specific workarounds into a small overlay?

Do not flash this branch. It contains no new installer and has not been
built or tested on a phone.

## The two layouts

- `zip-overlay/` is the future recovery-zip side. It holds only the
  overlay resources that a zip can install over an already-built stack.
- `source-tree/` is the future LineageOS source-build side. It holds the
  same overlay in the form a device tree can add without editing
  `packages/modules/ImsStack`.

Both layouts currently carry the same one resource. Keeping the copies
identical is deliberate: the zip and source paths must not drift into
two different workarounds.

## What is actually isolated

The one implemented workaround is the incoming-call framework switch:

```text
ro.telephony.block_binder_thread_on_incoming_calls=true
```

The existing alpha1 branch already sets that property in three places:
the source device patch, the recovery installer, and the systemless
module. This experiment gathers that same property into one named
overlay so it is no longer hidden inside the broader IMS integration.

This does not modify ImsStack. It changes a LineageOS telephony property
because the V30 tree sets it false for its old modem-IMS arrangement.

## What is not implemented

- There is no radio HAL 1.6 shim. The current V30 radio interface remains
  the Android 1.4 radio declared by the device tree. A HAL upgrade is a
  separate, much larger project and is not implied by these files.
- There is no dedicated-bearer or QCI workaround yet. The public
  krazey/ImsStack fork has a device-level switch,
  `config_imsstack_dedicated_bearer_qos_supported`, which disables QoS
  precondition waits and allows voice on the default bearer. That switch
  exists in the fork, not in unmodified upstream ImsStack, so copying its
  behavior here would violate the unmodified-stack goal.
- No carrier behavior is claimed. In particular, this does not say that
  any carrier's QCI or dedicated-bearer behavior is understood or fixed.
- No zip has been packed, no source tree has been built, and no phone has
  been tested.

## Boundary

`claude/aosp-ims-a15-backport` remains the patched Android 17 stack.
This branch does not replace it and does not remove any of its patches.
It only reserves a clean place to test which V30 changes can live outside
ImsStack.
