#!/usr/bin/env python3
"""Adapt an upstream platform app's manifest for an install without the
ROM's platform key.

- Drop android:sharedUserId: android.uid.system and android.uid.phone can
  only be joined by an APK signed with the platform key.
- Set android:usesNonSdkApi on <application>: a system app may then use
  the hidden APIs platform_apis code calls
  (ApplicationInfo.isAllowedToUseHiddenApis).
- Give the target SDK the platform's (Soong does the same for platform
  apps); aapt2 would otherwise take minSdkVersion.

Usage: patch-manifest.py <in> <out>
"""
import sys
import xml.etree.ElementTree as ET

A = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', A)
ET.register_namespace('tools', 'http://schemas.android.com/tools')


def main(src, out):
    tree = ET.parse(src)
    root = tree.getroot()
    root.attrib.pop(f'{{{A}}}sharedUserId', None)
    app = root.find('application')
    app.set(f'{{{A}}}usesNonSdkApi', 'true')
    sdk = root.find('uses-sdk')
    if sdk is None:
        sdk = ET.SubElement(root, 'uses-sdk')
    sdk.set(f'{{{A}}}targetSdkVersion', '35')
    if sdk.get(f'{{{A}}}minSdkVersion') is None:
        sdk.set(f'{{{A}}}minSdkVersion', '31')
    tree.write(out, encoding='utf-8', xml_declaration=True)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
