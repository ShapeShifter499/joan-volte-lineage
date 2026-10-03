# Source-build side

This directory now contains the first Android 15 / LineageOS 22.2 shape:

- `joan-ims-unmodified.mk` selects the unmodified stack's packages.
- `overlay/` contains the framework and Telephony package selections.

It deliberately contains no carrier admission, QCI/QoS workaround, radio
HAL shim, or Soong backport patch. Before claiming more, sync Android 17
`ImsStack` and its paired `ImsMedia` into a real LineageOS 22.2 tree and
record the first Soong/javac failure without editing ImsStack.
