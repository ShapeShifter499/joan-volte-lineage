#!/usr/bin/env python3
"""Check the unmodified Android 17 IMS experiment's source-shape boundary."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
source = root / "source-tree"
required = {
    root / "README.md",
    root / "check-overlay-split.py",
    root / "zip-overlay" / "README.md",
    source / "README.md",
    source / "joan-ims-unmodified.mk",
    source / "overlay/frameworks/base/core/res/res/values/config.xml",
    source / "overlay/packages/services/Telephony/res/values/config.xml",
}
actual = {path for path in root.rglob("*") if path.is_file()}
errors = []
if actual != required:
    errors.append("unexpected files: " + ", ".join(
        str(path.relative_to(root)) for path in sorted(actual - required)))
for xml_path in sorted(source.glob("overlay/**/*.xml")):
    try:
        ET.parse(xml_path)
    except ET.ParseError as exc:
        errors.append(f"invalid XML in {xml_path.relative_to(root)}: {exc}")
mk = (source / "joan-ims-unmodified.mk").read_text()
for package in ("ImsStack", "ImsMediaService", "Iwlan", "QualifiedNetworksService"):
    if package not in mk:
        errors.append(f"missing package selection {package}")
for forbidden in (
        "config_imsstack_dedicated_bearer_qos_supported",
        "carrier_volte_available_bool",
        "vendor.qti.hardware.radio",
        "soong_config_set_bool"):
    if forbidden in mk:
        errors.append(f"makefile contains forbidden integration {forbidden}")
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print("source shape OK: package selection only, valid overlays, no carrier/QCI/HAL shim")
