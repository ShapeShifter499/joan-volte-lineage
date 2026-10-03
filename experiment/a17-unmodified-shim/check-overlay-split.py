#!/usr/bin/env python3
"""Check the unmodified-IMS experiment stays within its declared boundary."""

from pathlib import Path
import sys

root = Path(__file__).resolve().parent
zip_prop = root / "zip-overlay" / "system.prop"
source_prop = root / "source-tree" / "device-overlay" / "system.prop"
required = "ro.telephony.block_binder_thread_on_incoming_calls=true"
forbidden_markers = (
    "config_imsstack_dedicated_bearer_qos_supported",
    "vendor.qti.hardware.radio@",
    "IRadio/slot1",
)

errors = []
zip_text = zip_prop.read_text()
source_text = source_prop.read_text()
if zip_text != source_text:
    errors.append("zip and source overlays differ")
for name, text in (("zip", zip_text), ("source", source_text)):
    active = [
        line.strip() for line in text.splitlines()
        if line.strip() and not line.startswith("#")
    ]
    if active != [required]:
        errors.append(f"{name} overlay does not contain exactly the incoming-call property")
    for marker in forbidden_markers:
        if marker in text:
            errors.append(f"{name} overlay contains undeclared workaround marker {marker}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print("overlay split OK: one identical incoming-call property, no HAL or QCI shim")
