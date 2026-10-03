#!/usr/bin/env python3
"""Keep this experiment inside the unmodified-stack boundary."""

from pathlib import Path
import sys

root = Path(__file__).resolve().parent
allowed = {
    root / "README.md",
    root / "check-overlay-split.py",
    root / "source-tree" / "README.md",
    root / "zip-overlay" / "README.md",
}
actual = {path for path in root.rglob("*") if path.is_file()}
errors = []
if actual != allowed:
    errors.append("unexpected experiment files: " + ", ".join(
        str(path.relative_to(root)) for path in sorted(actual - allowed)
    ))
for path in (root / "source-tree" / "README.md", root / "zip-overlay" / "README.md"):
    text = path.read_text()
    if "No change is included yet" not in text and "No overlay is included yet" not in text:
        errors.append(f"{path.name} no longer declares that it is empty")
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print("unmodified-stack boundary OK: no overlay or shim has been added")
