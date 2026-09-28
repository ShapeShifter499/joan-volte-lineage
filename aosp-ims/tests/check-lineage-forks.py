#!/usr/bin/env python3
"""LineageOS 22.2's own changes on the IMS path, checked against a review.

LineageOS builds most of Android 15 from AOSP's tag (LINEAGE_AOSP_TAG in
upstream.lock) and forks some projects. This takes each fork on the path
between the phone process and an IMS service, at the commit upstream.lock
pins (the ROM's), diffs the parts the review covered against the same AOSP
tag, and requires every file that differs to be in tests/lineage-forks.txt
with the same diff (its sha256) and a note on what it means for this stack.
A system property that LineageOS's added code names must be listed there
too, with what the kit does about it. It also checks that the projects the
review took as AOSP's own (CarrierConfig, frameworks/opt/net/ims, ImsMedia,
the Telephony module) are still unforked at that tag in LineageOS's
manifest, and that the Android 15 inputs of the carrier-config import are
that tag's.

--list prints the files that differ, with their diff hashes, and the
properties, in the review file's format (for a new review after moving the
pins). --heads diffs LineageOS's current lineage-22.2 heads instead of the
pins: what LineageOS changed since the review.

Usage: check-lineage-forks.py [--heads] [--list]
Needs git and network access; fetches blobless into $WORK/lineage-forks.
"""
import difflib
import hashlib
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REVIEW = os.path.join(HERE, 'tests', 'lineage-forks.txt')
AOSP = 'https://android.googlesource.com/platform/'
LINEAGE = 'https://github.com/LineageOS/'

# name: (AOSP project, LineageOS repository, upstream.lock pin, reviewed paths)
FORKS = {
    'frameworks/base': ('frameworks/base', 'android_frameworks_base',
                        'LINEAGE_FRAMEWORKS_BASE_COMMIT', [
                            'telephony',
                            'core/res/res/values/config.xml',
                            'core/java/android/content/pm/PackagePartitions.java',
                            'services/core/java/com/android/server/SystemConfig.java',
                            'services/core/java/com/android/server/TelephonyRegistry.java',
                            'services/core/java/com/android/server/audio/AudioService.java',
                            'services/core/java/com/android/server/location',
                            'services/core/java/com/android/server/net/NetworkPolicyManagerService.java',
                            'services/core/java/com/android/server/pm/PackageManagerService.java',
                            'services/core/java/com/android/server/pm/Settings.java',
                            'services/core/java/com/android/server/pm/permission',
                        ]),
    'frameworks/opt/telephony': ('frameworks/opt/telephony', 'android_frameworks_opt_telephony',
                                 'LINEAGE_OPT_TELEPHONY_COMMIT', ['src']),
    'packages/services/Telephony': ('packages/services/Telephony',
                                    'android_packages_services_Telephony',
                                    'LINEAGE_TELESERVICE_COMMIT',
                                    ['src', 'AndroidManifest.xml', 'res/values', 'res/xml']),
    'packages/providers/TelephonyProvider': ('packages/providers/TelephonyProvider',
                                             'android_packages_providers_TelephonyProvider',
                                             'LINEAGE_TELEPHONYPROVIDER_COMMIT',
                                             ['src', 'assets', 'AndroidManifest.xml']),
    'packages/services/Iwlan': ('packages/services/Iwlan', 'android_packages_services_Iwlan',
                                'LINEAGE_IWLAN_COMMIT', ['.']),
}
# Taken as AOSP's own at the tag: LineageOS's manifest must still say so.
UNFORKED = ['packages/apps/CarrierConfig', 'frameworks/opt/net/ims',
            'packages/modules/ImsMedia', 'packages/modules/Telephony']
# A system property named in added code.
PROPERTY = re.compile(r'"((?:ro|persist|sys|vendor|debug|telephony)\.[A-Za-z0-9_.]+)"')


def lock():
    out = {}
    for line in open(os.path.join(HERE, 'upstream.lock'), encoding='utf-8'):
        m = re.match(r'([A-Z0-9_]+)=(.*)', line.strip())
        if m:
            out[m.group(1)] = m.group(2)
    return out


def git(repo, *args, data=False):
    r = subprocess.run(['git', '-C', repo, *args], capture_output=True)
    if r.returncode:
        raise SystemExit(f'git {" ".join(args)} in {repo}: {r.stderr.decode(errors="replace")}')
    return r.stdout if data else r.stdout.decode()


def fetch(url, ref, repo):
    """The commit ref names, fetched blobless (depth 1) into repo."""
    if not os.path.isdir(os.path.join(repo, '.git')):
        os.makedirs(repo, exist_ok=True)
        git(repo, 'init', '-q')
        git(repo, 'remote', 'add', 'origin', url)
    if re.fullmatch(r'[0-9a-f]{40}', ref):
        have = subprocess.run(['git', '-C', repo, 'cat-file', '-e', ref + '^{commit}'],
                              capture_output=True).returncode == 0
        if have:
            return ref
    for i in range(5):
        r = subprocess.run(['git', '-C', repo, 'fetch', '-q', '--depth', '1',
                            '--filter=blob:none', 'origin', ref], capture_output=True)
        if r.returncode == 0:
            return git(repo, 'rev-parse', 'FETCH_HEAD^{commit}').strip()
        time.sleep(4 * (i + 1))
    raise SystemExit(f'cannot fetch {ref} from {url}: {r.stderr.decode(errors="replace")}')


def files(repo, commit, paths):
    """{path: blob id} under paths at commit."""
    out = {}
    for line in git(repo, 'ls-tree', '-r', '-z', commit, '--', *paths).split('\0'):
        if line:
            meta, path = line.split('\t', 1)
            out[path] = meta.split()[2]
    return out


def diff_hash(path, a, b):
    """sha256 (12 hex) of the unified diff between two versions, and its added lines."""
    try:
        at, bt = a.decode(), b.decode()
    except UnicodeDecodeError:
        return hashlib.sha256(a + b'\0' + b).hexdigest()[:12], []
    lines = list(difflib.unified_diff(at.splitlines(keepends=True), bt.splitlines(keepends=True),
                                      'a/' + path, 'b/' + path))
    added = [l[1:] for l in lines if l.startswith('+') and not l.startswith('+++')]
    return hashlib.sha256(''.join(lines).encode()).hexdigest()[:12], added


def review():
    """({(fork, path): (hash, note)}, {property: note})."""
    diffs, props = {}, {}
    for n, line in enumerate(open(REVIEW, encoding='utf-8'), 1):
        line = line.rstrip('\n')
        if not line.strip() or line.startswith('#'):
            continue
        parts = line.split(None, 3)
        if parts[0] == 'property' and len(parts) >= 3:
            props[parts[1]] = line.split(None, 2)[2]
        elif len(parts) == 4 and re.fullmatch(r'[0-9a-f]{12}', parts[2]):
            diffs[(parts[0], parts[1])] = (parts[2], parts[3])
        else:
            raise SystemExit(f'{REVIEW}:{n}: not "<fork> <path> <hash> <note>" '
                             f'or "property <name> <note>"')
    return diffs, props


def check_manifest(L, work, tag):
    repo = os.path.join(work, 'manifest')
    commit = fetch(L['LINEAGE_MANIFEST_URL'], L['LINEAGE_MANIFEST_COMMIT'], repo)
    root = ET.fromstring(git(repo, 'show', commit + ':default.xml'))
    errors = []
    aosp = [r for r in root.findall('remote') if r.get('name') == 'aosp']
    if not aosp or aosp[0].get('revision') != 'refs/tags/' + tag:
        errors.append(f'the manifest\'s aosp remote is not at refs/tags/{tag}')
    projects = {p.get('path', p.get('name')): p for p in root.findall('project')}
    for path in UNFORKED:
        p = projects.get(path)
        if p is None or p.get('remote') != 'aosp' or p.get('revision'):
            errors.append(f'{path} is no longer AOSP\'s at the tag in LineageOS\'s manifest')
    for name, (_, repo_name, _, _) in FORKS.items():
        p = projects.get(name)
        if p is None or p.get('name') != 'LineageOS/' + repo_name:
            errors.append(f'{name} is not LineageOS/{repo_name} in LineageOS\'s manifest')
    return errors


def main(argv):
    heads, listing = '--heads' in argv, '--list' in argv
    L = lock()
    tag = L['LINEAGE_AOSP_TAG']
    work = os.path.join(os.environ.get('WORK', os.path.join(HERE, 'work')), 'lineage-forks')
    errors = check_manifest(L, work, tag)
    reviewed, props = review()
    seen, found_props = set(), {}
    for name, (aosp_project, repo_name, pin, paths) in FORKS.items():
        key = name.replace('/', '_')
        a_repo, b_repo = os.path.join(work, key + '-aosp'), os.path.join(work, key + '-lineage')
        a = fetch(AOSP + aosp_project, 'refs/tags/' + tag, a_repo)
        b = fetch(LINEAGE + repo_name, 'refs/heads/lineage-22.2' if heads else L[pin], b_repo)
        # The carrier-config import's Android 15 inputs must be this same tag.
        for lock_key, project in (('AOSP_FRAMEWORKS_BASE_A15_COMMIT', 'frameworks/base'),
                                  ('AOSP_TELEPHONYPROVIDER_A15_COMMIT',
                                   'packages/providers/TelephonyProvider')):
            if name == project and L[lock_key] != a:
                errors.append(f'{lock_key} is not {tag} ({a})')
        fa, fb = files(a_repo, a, paths), files(b_repo, b, paths)
        changed = sorted(p for p in set(fa) | set(fb) if fa.get(p) != fb.get(p))
        print(f'{name}: {len(changed)} files differ from {tag} (LineageOS {b[:12]})')
        for path in changed:
            ablob = git(a_repo, 'cat-file', 'blob', fa[path], data=True) if path in fa else b''
            bblob = git(b_repo, 'cat-file', 'blob', fb[path], data=True) if path in fb else b''
            h, added = diff_hash(path, ablob, bblob)
            seen.add((name, path))
            for m in PROPERTY.finditer(''.join(added)):
                found_props.setdefault(m.group(1), f'{name} {path}')
            if listing:
                print(f'{name} {path} {h} {reviewed.get((name, path), ("", "?"))[1]}')
                continue
            if (name, path) not in reviewed:
                errors.append(f'{name} {path}: LineageOS changes it, not reviewed ({h})')
            elif reviewed[(name, path)][0] != h:
                errors.append(f'{name} {path}: LineageOS\'s change differs from the one '
                              f'reviewed ({reviewed[(name, path)][0]} -> {h})')
    if listing:
        for prop, where in sorted(found_props.items()):
            print(f'property {prop} {props.get(prop, "? (" + where + ")")}')
        if errors:
            print('\n'.join('FAIL: ' + e for e in errors))
            sys.exit(1)
        return
    for key in sorted(set(reviewed) - seen):
        errors.append(f'{key[0]} {key[1]}: reviewed as changed, but it is {tag}\'s now')
    for prop, where in sorted(found_props.items()):
        if prop not in props:
            errors.append(f'property {prop} (in {where}): LineageOS reads it, not reviewed')
    for prop in sorted(set(props) - set(found_props)):
        errors.append(f'property {prop}: reviewed, but no LineageOS change names it now')
    if errors:
        print('\n'.join('FAIL: ' + e for e in errors))
        sys.exit(1)
    print(f'LineageOS\'s changes on the IMS path: {len(seen)} files and {len(found_props)} '
          f'properties, all as reviewed in tests/lineage-forks.txt')


if __name__ == '__main__':
    main(sys.argv[1:])
