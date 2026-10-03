# Source-build overlay

This directory is the source-build half of the experiment. A future
device-tree change can add the property in `device-overlay/system.prop`
without editing Android 17 ImsStack.

The matching recovery-zip half is `../zip-overlay/system.prop`. The two
files are intentionally identical. If one changes, change and review the
other in the same commit.

This directory does not add a radio HAL, a QCI bypass, or a carrier
configuration. It also does not claim that the property alone makes IMS
work.
