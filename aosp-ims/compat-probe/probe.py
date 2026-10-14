#!/usr/bin/env python3
"""Java compatibility probe; never modifies or packages the IMS inputs.

Uses existing A15 toolchain/framework inputs read-only. This is NOT a full
Soong build, runtime test, or flashable artifact. Diagnostic compile failures
are retained verbatim; missing framework methods are never silently stubbed.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import time
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parents[1]
WORK = HERE / 'work'
JDK = Path('/usr/lib/jvm/java-21-openjdk/bin')
VARIANTS = {
    'aosp': ('1e3981c23117d75d5ad304b32bb030327ad33eaa',
             '0101cb4caf48dbcc3b8fbc6ab3e41137b90b8e67'),
    'krazey': ('5ff784d265a94b8227f8fcc93bf915f7635283f0',
               'cb1dd74e9d5e7b7693dfc5870037607eb58d8ea3'),
}
# Compile dependencies, not an assertion of the ROM's exact AndroidX versions.
ANDROIDX = [('appcompat', 'appcompat', '1.7.0'), ('core', 'core', '1.13.1')]


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args])


def inventory(root):
    return {str(p.relative_to(root)): digest(p)
            for p in sorted(root.rglob('*')) if p.is_file()}


def export(repo, commit, target):
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Expected exact SHA-1 commit, not a moving ref')
    assert git(repo, 'rev-parse', commit).decode().strip() == commit
    archive = git(repo, 'archive', '--format=tar', commit)
    target.mkdir(parents=True, exist_ok=False)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tf:
        for member in tf.getmembers():
            path = Path(member.name)
            if path.is_absolute() or '..' in path.parts or not (member.isfile() or member.isdir()):
                raise ValueError(f'Unsupported archive member: {member.name}')
        tf.extractall(target, filter='data')
    # Check every archived regular file against the Git blob, not just its
    # before/after hash. git archive may respect export-ignore attributes.
    tracked = {}
    for entry in git(repo, 'ls-tree', '-r', '-z', commit).split(b'\0'):
        if not entry:
            continue
        metadata, path = entry.split(b'\t', 1)
        mode, kind, oid = metadata.split()
        if kind == b'blob':
            tracked[path.decode()] = oid.decode()
    actual = inventory(target)
    for rel in actual:
        data = (target / rel).read_bytes()
        oid = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if tracked.get(rel) != oid:
            raise RuntimeError(f'Export differs from Git: {rel}')
    return {'commit': commit, 'tree': git(repo, 'rev-parse', commit + '^{tree}').decode().strip(),
            'files': actual, 'not_exported': sorted(set(tracked) - set(actual))}


def fetch_androidx(dest):
    dest.mkdir()
    evidence, jars = [], []
    for group, artifact, version in ANDROIDX:
        base = f'https://dl.google.com/dl/android/maven2/androidx/{group}/{artifact}/{version}'
        url = f'{base}/{artifact}-{version}.aar'
        with urllib.request.urlopen(url, timeout=90) as response:
            data = response.read()
        with urllib.request.urlopen(url + '.sha1', timeout=30) as response:
            published = response.read().decode().strip()
        if hashlib.sha1(data).hexdigest() != published:
            raise RuntimeError(f'Publisher checksum mismatch: {url}')
        aar = dest / f'{artifact}-{version}.aar'
        aar.write_bytes(data)
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            if z.testzip() is not None:
                raise RuntimeError('Corrupt AAR')
            for name in z.namelist():
                if Path(name).is_absolute() or '..' in Path(name).parts:
                    raise RuntimeError('Unsafe AAR member')
            jar = dest / f'{artifact}-{version}.jar'
            jar.write_bytes(z.read('classes.jar'))
        jars.append(jar)
        evidence.append({'url': url, 'sha256': digest(aar), 'publisher_sha1': published,
                         'classes_sha256': digest(jar)})
    return jars, evidence


def run(command, log, *, timeout=240):
    command = [str(x) for x in command]
    started = time.monotonic()
    with log.open('w') as stream:
        stream.write(json.dumps(command) + '\n')
        stream.flush()
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    return {'argv': command, 'returncode': result.returncode,
            'seconds': round(time.monotonic() - started, 2), 'log': str(log)}


def sources(root):
    return sorted(root.rglob('*.java'))


def prepare(out, stack, media, sdk, bt):
    for folder in ('gen', 'rgen-stack', 'rgen-media', 'res'):
        (out / folder).mkdir()
    commands = []
    hal = WORK / 'hwif/radio/aidl/aidl_api'
    version = hal / 'android.hardware.radio.ims.media/2'
    radio = hal / 'android.hardware.radio/4'
    hal_hash = (version / '.hash').read_text().splitlines()[-1]
    for f in sorted(version.rglob('*.aidl')) + [radio / 'android/hardware/radio/AccessNetwork.aidl']:
        commands.append([bt / 'aidl', '--lang=java', '--structured', '--stability=vintf',
                         '--version=2', '--hash=' + hal_hash, '-I' + str(version),
                         '-I' + str(radio), '-o', out / 'gen', f])
    for root in (media / 'framework/src', stack / 'java/src'):
        for f in sorted(root.rglob('*.aidl')):
            commands.append([bt / 'aidl', '--lang=java',
                             '-p' + str(sdk / 'platforms/android-35/framework.aidl'),
                             '-I' + str(root), '-I' + str(HERE / 'stubs/aidl'),
                             '-o', out / 'gen', f])
    for name, root, manifest in (
            ('stack', stack / 'java', 'AndroidManifest-lib.xml'),
            ('media', media / 'service', 'AndroidManifest.xml')):
        commands.append([bt / 'aapt2', 'compile', '--dir', root / 'res',
                         '-o', out / 'res' / f'{name}.zip'])
        commands.append([bt / 'aapt2', 'link', '--manifest', root / manifest,
                         '-I', WORK / 'sdk/35/public/android.jar',
                         '--java', out / f'rgen-{name}', '-o', out / 'res' / f'{name}.apk',
                         out / 'res' / f'{name}.zip', '--auto-add-overlay', '--non-final-ids',
                         '--static-lib', '--merge-only'])
    with (out / 'prepare.log').open('w') as stream:
        for cmd in commands:
            cmd = [str(x) for x in cmd]
            stream.write(json.dumps(cmd) + '\n')
            stream.flush()
            subprocess.run(cmd, check=True, stdout=stream, stderr=subprocess.STDOUT, timeout=90)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--variant', choices=VARIANTS, required=True)
    ap.add_argument('--stack-repo', type=Path, required=True)
    ap.add_argument('--media-repo', type=Path, required=True)
    ap.add_argument('--attempt', type=Path, required=True)
    ap.add_argument('--barring-framework-dir', type=Path,
                    help='Exact real framework getter backport from make_barring_patch.py')
    args = ap.parse_args()
    out = args.attempt.resolve()
    out.mkdir(parents=True, exist_ok=False)
    report = {'variant': args.variant, 'runtime_tested': False, 'full_soong_build': False,
              'framework_members_stubbed': False, 'harness': str(Path(__file__).resolve()),
              'harness_sha256': digest(Path(__file__)), 'gates': {}}
    stack, media = out / 'src/ImsStack', out / 'src/ImsMedia'
    stack_sha, media_sha = VARIANTS[args.variant]
    report['sources'] = {'ImsStack': export(args.stack_repo, stack_sha, stack),
                         'ImsMedia': export(args.media_repo, media_sha, media)}
    try:
        sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
        bt = sdk / 'build-tools/36.0.0'
        if not bt.is_dir():
            raise RuntimeError(f'Missing exact build tools: {bt}')
        jars, report['androidx'] = fetch_androidx(out / 'androidx')
        prepare(out, stack, media, sdk, bt)
        ml = WORK / 'sdk/35/module-lib'
        cp = [WORK / 'fwcls' / f'{s}.jar' for s in
              ('framework', 'telephony-common', 'ims-common', 'framework-location')]
        cp += [ml / 'android.jar'] + sorted(ml.glob('framework-*.jar'))
        cp += [ml / 'android.net.ipsec.ike.jar', WORK / 'deps/androidx-annotation.jar',
               WORK / 'deps/libphonenumber.jar'] + jars
        report['classpath'] = {str(p): digest(p) for p in cp}
        stub_paths = sources(HERE / 'stubs/java')
        report['annotation_and_aconfig_standins'] = {str(p): digest(p) for p in stub_paths}
        report['resource_gate'] = 'static-library merge only; final resource link NOT tested'
        if args.barring_framework_dir:
            framework = args.barring_framework_dir.resolve()
            source = framework / 'telephony/java/android/telephony/BarringInfo.java'
            if digest(source) != '7fd67ce1803dd74a3dae79272e61645ce37180029875abab3b848383b81517f9':
                raise RuntimeError('Not the qualified real BarringInfo source backport')
            fc = out / 'framework-classes'
            fc.mkdir()
            report['gates']['framework_getter_compile'] = run(
                [JDK / 'javac', '-proc:none', '-d', fc, '-classpath',
                 ':'.join(str(p) for p in cp), source] +
                [HERE / 'stubs/java/android/annotation' / (s + '.java')
                 for s in ('IntDef', 'NonNull', 'Nullable')], out / 'framework-getter.log')
            if report['gates']['framework_getter_compile']['returncode']:
                raise RuntimeError('Framework getter did not compile')
            cp.insert(0, fc)
            report['framework_backport'] = {'source': str(source), 'sha256': digest(source),
                                           'compiled_files': inventory(fc)}
        ss = sources(stack / 'java/src')
        if args.variant == 'krazey':
            # Exactly the selection already supported by this upstream fork.
            ss = [p for p in ss if p.name != 'DomainSelectionEmergencyModeMonitor.java']
            report['source_selection'] = 'upstream use_android16_telephony_compat=true'
        else:
            report['source_selection'] = 'all AOSP Java sources; no exclusions'
        ss += sources(media / 'framework/src') + sources(out / 'gen')
        ss += sources(out / 'rgen-stack') + stub_paths
        (out / 'stack.srcs').write_text('\n'.join(str(p) for p in ss) + '\n')
        report['stack_source_count'] = len(ss)
        target = out / 'classes-stack'
        target.mkdir()
        base = [JDK / 'javac', '-J-Xmx4g', '-encoding', 'UTF-8', '-nowarn', '-proc:none',
                '-Xmaxerrs', '200', '-source', '21', '-target', '21']
        report['gates']['java_stack'] = run(base + ['-d', target, '-classpath',
                         ':'.join(str(p) for p in cp), '@' + str(out / 'stack.srcs')],
                         out / 'javac-stack.log')
        # Compile media independently: never borrow a partially emitted ImsStack
        # classes directory after an expected stack compile failure.
        ms = sources(media / 'framework/src') + sources(media / 'service/src')
        ms += [p for p in sources(out / 'gen')
               if 'com/android/imsstack/' not in str(p.relative_to(out / 'gen'))]
        ms += sources(out / 'rgen-media') + stub_paths
        report['media_source_count'] = len(ms)
        (out / 'media.srcs').write_text('\n'.join(str(p) for p in ms) + '\n')
        mt = out / 'classes-media'
        mt.mkdir()
        report['gates']['java_media'] = run(base + ['-d', mt, '-classpath',
                     ':'.join(str(p) for p in cp), '@' + str(out / 'media.srcs')],
                     out / 'javac-media.log')
    except Exception as exc:
        report['harness_error'] = str(exc)
    finally:
        unchanged = all(inventory(p) == report['sources'][name]['files']
                        for name, p in [('ImsStack', stack), ('ImsMedia', media)])
        report['source_bytes_unchanged'] = unchanged
        (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        summary = {k: report.get(k) for k in ('variant', 'stack_source_count',
                   'source_bytes_unchanged', 'harness_error')}
        summary['gates'] = {k: {field: value for field, value in v.items() if field != 'argv'}
                            for k, v in report['gates'].items()}
        summary['report'] = str(out / 'report.json')
        print(json.dumps(summary, indent=2))
    if not unchanged or 'harness_error' in report:
        return 2
    return 0 if all(g['returncode'] == 0 for g in report['gates'].values()) else 1


if __name__ == '__main__':
    raise SystemExit(main())
