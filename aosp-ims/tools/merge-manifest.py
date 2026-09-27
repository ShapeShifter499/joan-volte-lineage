#!/usr/bin/env python3
"""Merge ImsMedia's service into ImsStack's manifest for the single-APK build.

Upstream ships two apps. ImsMediaService runs as android.uid.phone, which only
the ROM's platform key can join, so a build installed without that key (the
flashable zip, the repacked ROM) puts the ImsMedia service into the ImsStack
package, in ImsMedia's own process, and drops the shared UID.

Usage: merge-manifest.py <ImsStack manifest> <ImsMedia manifest> <out>
                         <versionCode> <versionName>
"""
import sys
import xml.etree.ElementTree as ET

A = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', A)
ET.register_namespace('androidprv', 'http://schemas.android.com/apk/prv/res/android')


def a(name):
    return f'{{{A}}}{name}'


def main(stack_path, media_path, out, code, name):
    stack = ET.parse(stack_path)
    media = ET.parse(media_path)
    sroot, mroot = stack.getroot(), media.getroot()
    media_pkg = mroot.get('package')
    media_process = media_pkg

    sroot.set(a('versionCode'), code)
    sroot.set(a('versionName'), name)

    # Permissions ImsMedia declares and requests.
    have = {e.get(a('name')) for e in sroot.findall('uses-permission')}
    declared = {e.get(a('name')) for e in sroot.findall('permission')}
    app = sroot.find('application')
    insert_at = list(sroot).index(app)
    for e in mroot.findall('permission'):
        if e.get(a('name')) not in declared:
            sroot.insert(insert_at, e)
            insert_at += 1
    for e in mroot.findall('uses-permission'):
        if e.get(a('name')) not in have:
            sroot.insert(insert_at, e)
            insert_at += 1
            have.add(e.get(a('name')))

    # Application: our wrapper picks ImsStack or ImsMedia behaviour by
    # process; hidden API access for a system app not signed with the
    # platform key (ApplicationInfo.isAllowedToUseHiddenApis).
    app.set(a('name'), 'com.android.imsstack.ImsStackZipApp')
    app.set(a('usesNonSdkApi'), 'true')
    # The native libraries load straight from the APK, stored and aligned.
    app.set(a('extractNativeLibs'), 'false')

    # ImsMedia's components, fully qualified, in ImsMedia's process.
    mapp = mroot.find('application')
    for comp in mapp:
        cname = comp.get(a('name'))
        if cname.startswith('.'):
            comp.set(a('name'), media_pkg + cname)
        comp.set(a('process'), media_process)
        if comp.tag == 'service':
            # Only this package binds it now.
            comp.set(a('exported'), 'false')
        app.append(comp)

    # Upstream builds set the target SDK to the platform's; aapt2 would
    # otherwise take minSdkVersion (31).
    uses_sdk = sroot.find('uses-sdk')
    if uses_sdk is None:
        uses_sdk = ET.SubElement(sroot, 'uses-sdk')
    uses_sdk.set(a('targetSdkVersion'), '35')

    stack.write(out, encoding='utf-8', xml_declaration=True)


if __name__ == '__main__':
    if len(sys.argv) != 6:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
