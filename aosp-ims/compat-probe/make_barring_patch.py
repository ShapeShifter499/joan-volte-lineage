#!/usr/bin/env python3
"""Generate a minimal frameworks/base patch from exact LineageOS 22.2 input.

The real getter is the AOSP implementation. No ImsStack/ImsMedia file changes,
reflection shims, generated framework-method stubs or runtime bytecode rewriting.
The A17-only feature flag annotation is omitted; system-current is updated.
"""
import argparse
import difflib
import hashlib
import json
from pathlib import Path

BASE = '5c05c66e872c907a925f633b69f11661c59e7ad0'
SOURCE_SHA256 = '17970a81407381d409e46aef41c019fcfd453d619b28d305f8d44007bef33710'
API_SHA256 = '51e3846f4c566cff2930536d3d4a9efb12b8e964b07a1826c8770093e327ca1b'
GETTER = '''    /**
     * Return the cell that reported the BarringInfo.
     *
     * @return the {@link CellIdentity} of the cell that is broadcasting this barring information.
     *
     * @hide
     */
    @SystemApi
    public @Nullable CellIdentity getCellIdentity() {
        return mCellIdentity;
    }

'''


def checked(path, expected):
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError(f'Wrong source identity: {path}')
    return data.decode()


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError('Ambiguous/missing anchor')
    return text.replace(old, new, 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--system-api', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    original = checked(args.source, SOURCE_SHA256)
    api = checked(args.system_api, API_SHA256)
    anchor = '    /** @hide */\n    @SystemApi\n    public @NonNull BarringInfo createLocationInfoSanitizedCopy() {'
    changed = replace_once(original, anchor, GETTER + anchor)
    api_anchor = ('  public final class BarringInfo implements android.os.Parcelable {\n'
                  '    ctor public BarringInfo();\n'
                  '    method @NonNull public android.telephony.BarringInfo createLocationInfoSanitizedCopy();\n')
    changed_api = replace_once(api, api_anchor, api_anchor +
                              '    method @Nullable public android.telephony.CellIdentity getCellIdentity();\n')
    args.out.mkdir(parents=True, exist_ok=False)
    patches = []
    manifest = {'base': BASE, 'ims_modified': False, 'target': 'frameworks/base', 'files': {}}
    for name, before, after in [
        ('telephony/java/android/telephony/BarringInfo.java', original, changed),
        ('core/api/system-current.txt', api, changed_api),
    ]:
        p = args.out / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(after)
        patches += list(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                            fromfile='a/' + name, tofile='b/' + name))
        manifest['files'][name] = {'before_sha256': hashlib.sha256(before.encode()).hexdigest(),
                                   'after_sha256': hashlib.sha256(after.encode()).hexdigest()}
    patch = args.out / 'frameworks-base-barring-info.patch'
    patch.write_text(''.join(patches))
    manifest['patch_sha256'] = hashlib.sha256(patch.read_bytes()).hexdigest()
    (args.out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
