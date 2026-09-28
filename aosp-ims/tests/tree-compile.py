#!/usr/bin/env python3
"""Compile every libimsstack and libimsmedia source as a LineageOS 22.2
tree build would: Android 15's clang, and Soong's warnings as errors.

The zip's native build (tools/build-native.sh) compiles with the NDK's
clang and warnings off. A tree build uses Android 15's own clang
(clang-r536225) with Soong's global warning flags and each module's own,
-Werror included, so a warning that compiler finds in Android 17's code
would stop the build. This takes each compile from the zip build's
ninja files (sources, defines, include paths), swaps in that compiler
and those flags, and checks the syntax of every source.

A warning planted in a copy of one source must fail, or the check is
not live. Flags: Android 15's build/soong cc/config (global.go, arm64_device.go:
the global warning lists, the no-override ones last, as Soong orders
them) and each module's own warning flags as Soong computes them
(imscheck_test.go's TestDumpNativeFlags).

Usage: tree-compile.py <clang-19> <clang resource dir> <soong cc/config dir>
           <module flags file> <build.ninja>...
"""
import os
import re
import shlex
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor


def golist(path, name):
    text = open(path, encoding='utf-8').read()
    m = re.search(r'\t' + name + r' = \[\]string\{(.*?)\n\t\}', text, re.S)
    if not m:
        raise SystemExit(f'{path}: {name} not found')
    return re.findall(r'"([^"]*)"', re.sub(r'//[^\n]*', '', m.group(1)))


def warning_flag(f):
    return f.startswith('-W')


def main(clang, resource_dir, config, module_flags, *ninjas):
    g = os.path.join(config, 'global.go')
    arm64 = os.path.join(config, 'arm64_device.go')
    base = [f for f in golist(g, 'commonGlobalCflags') + golist(g, 'deviceGlobalCflags')
            + golist(arm64, 'arm64Cflags') if warning_flag(f)]
    cpp = [f for f in golist(g, 'commonGlobalCppflags') + golist(g, 'deviceGlobalCppflags')
           if warning_flag(f)]
    last = [f for f in golist(g, 'noOverrideGlobalCflags') + golist(g, 'noOverride64GlobalCflags')
            if warning_flag(f)]
    modules = {}
    for line in open(module_flags, encoding='utf-8'):
        name, _, _, flags = line.rstrip('\n').split('|', 3)
        modules[name] = [f for f in shlex.split(flags) if warning_flag(f)]
    if not any('-Werror' in f for flags in modules.values() for f in flags):
        raise SystemExit(f'{module_flags}: no module carries -Werror; wrong flags file?')

    jobs = []
    for ninja in ninjas:
        text = open(ninja, encoding='utf-8').read()
        for m in re.finditer(r'^build \S+/obj/([^/]+)/\S+\.o: (cc|cxx) (\S+)\n  flags = (.*)$',
                             text, re.M):
            module, kind, src, flags = m.groups()
            if module not in modules:
                raise SystemExit(f'{module}: no Soong flags for it')
            flags = shlex.split(flags)
            keep = [f for f in flags if f != '-w' and not f.startswith(('--target=', '-O'))]
            cmd = ([clang] + (['--driver-mode=g++'] if kind == 'cxx' else [])
                   + ['-target', 'aarch64-linux-android35', '-resource-dir', resource_dir]
                   + keep + base + (cpp if kind == 'cxx' else []) + modules[module] + last
                   + ['-fsyntax-only', '-fno-color-diagnostics', src])
            jobs.append((module, src, cmd))
    if not jobs:
        raise SystemExit('no compiles found in the ninja files')

    # The check is live: a warning planted in a copy of a source fails it.
    import tempfile
    module, src, cmd = next(j for j in jobs if j[2][1] == '--driver-mode=g++')
    with tempfile.NamedTemporaryFile('w', suffix='.cpp', delete=False) as tmp:
        tmp.write(open(src, encoding='utf-8', errors='replace').read())
        tmp.write('\nstatic int aosp_ims_control() { int unset; return unset; }\n')
    control = subprocess.run(cmd[:-1] + [tmp.name], capture_output=True, text=True)
    os.unlink(tmp.name)
    if control.returncode == 0 or '-Werror' not in control.stderr:
        raise SystemExit('a planted warning did not fail the compile: the check is not live')

    def run(job):
        r = subprocess.run(job[2], capture_output=True, text=True)
        return job, r.returncode, r.stderr

    failed = []
    with ThreadPoolExecutor(os.cpu_count() or 4) as pool:
        for job, rc, err in pool.map(run, jobs):
            if rc != 0:
                failed.append((job[1], [l for l in err.splitlines() if 'error:' in l][:3]))
    for src, errors in failed[:30]:
        print(f'FAIL {src}')
        for e in errors:
            print(f'    {e}')
    print(f'{len(jobs)} sources in {len(modules)} modules, Android 15 clang with Soong\'s '
          f'warnings as errors: {"OK" if not failed else f"{len(failed)} FAIL"}')
    return 1 if failed else 0


if __name__ == '__main__':
    if len(sys.argv) < 6:
        raise SystemExit(__doc__)
    sys.exit(main(*sys.argv[1:]))
