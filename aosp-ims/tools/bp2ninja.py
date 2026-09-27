#!/usr/bin/env python3
"""Build Soong cc modules outside the Android tree.

Reads the Android.bp files of the given source trees, resolves the cc
modules a target needs (defaults, header/static/shared libs, exported
include dirs) the way Soong does, and writes a build.ninja that compiles
them with the NDK's clang against a platform sysroot:

  - platform headers and libc++ from the matching AOSP release,
  - the device's own shared libraries to link against (libbinder,
    libutils, the platform libc++ ...), so the result has the exact ABI
    of the ROM it will run on.

Modules outside the given trees (libbinder, liblog ...) come from a JSON
file: {name: {"include": [dirs], "lib": "libX.so" | null}}.

Only the Blueprint subset these trees use is supported.
"""
import argparse
import fnmatch
import glob
import json
import os
import re
import shlex
import sys

# ---------------------------------------------------------------- parser

TOKEN = re.compile(r'''
    (?P<ws>\s+) |
    (?P<lc>//[^\n]*) |
    (?P<bc>/\*.*?\*/) |
    (?P<str>"(?:[^"\\]|\\.)*") |
    (?P<num>-?\d+) |
    (?P<id>[A-Za-z_][A-Za-z0-9_.]*) |
    (?P<op>\+=|[{}\[\](),:=+])
''', re.S | re.X)


def tokenize(text, path):
    pos, out = 0, []
    while pos < len(text):
        m = TOKEN.match(text, pos)
        if not m:
            raise SystemExit(f'{path}: cannot tokenize at {text[pos:pos + 40]!r}')
        pos = m.end()
        kind = m.lastgroup
        if kind in ('ws', 'lc', 'bc'):
            continue
        out.append((kind, m.group(kind)))
    return out


class Parser:
    def __init__(self, toks, path, variables):
        self.t, self.i, self.path, self.vars = toks, 0, path, variables

    def peek(self, k=0):
        return self.t[self.i + k] if self.i + k < len(self.t) else (None, None)

    def take(self, want=None):
        tok = self.t[self.i]
        if want and tok[1] != want:
            raise SystemExit(f'{self.path}: expected {want!r}, got {tok[1]!r}')
        self.i += 1
        return tok

    def file(self):
        modules = []
        while self.i < len(self.t):
            kind, val = self.peek()
            nkind, nval = self.peek(1)
            if kind == 'id' and nval in ('=', '+='):
                self.take(); self.take()
                v = self.expr()
                if nval == '+=':
                    v = add(self.vars[val], v)
                self.vars[val] = v
            elif kind == 'id' and nval == '{':
                self.take(); self.take('{')
                modules.append((val, self.props('}')))
            else:
                raise SystemExit(f'{self.path}: unexpected {val!r}')
        return modules

    def props(self, end):
        out = {}
        while self.peek()[1] != end:
            _, key = self.take()
            self.take(':')
            out[key] = self.expr()
            if self.peek()[1] == ',':
                self.take()
        self.take(end)
        return out

    def expr(self):
        v = self.operand()
        while self.peek()[1] == '+':
            self.take()
            v = add(v, self.operand())
        return v

    def operand(self):
        kind, val = self.take()
        if kind == 'str':
            return json.loads(val)
        if kind == 'num':
            return int(val)
        if val == '[':
            items = []
            while self.peek()[1] != ']':
                items.append(self.expr())
                if self.peek()[1] == ',':
                    self.take()
            self.take(']')
            return items
        if val == '{':
            return self.props('}')
        if kind == 'id' and val in ('true', 'false'):
            return val == 'true'
        if kind == 'id' and val == 'select':
            # select(...) is not needed by the cc modules built here; keep
            # the default branch if there is one, else nothing.
            depth, start = 0, self.i
            while True:
                _, v = self.take()
                depth += {'(': 1, ')': -1}.get(v, 0)
                if depth == 0:
                    break
            return []
        if kind == 'id':
            if val not in self.vars:
                raise SystemExit(f'{self.path}: unknown variable {val}')
            return self.vars[val]
        raise SystemExit(f'{self.path}: bad operand {val!r}')


def add(a, b):
    if isinstance(a, list) and isinstance(b, list):
        return a + b
    if isinstance(a, str) and isinstance(b, str):
        return a + b
    if isinstance(a, dict) and isinstance(b, dict):
        return merge(a, b)
    raise SystemExit(f'cannot add {type(a)} and {type(b)}')


def merge(base, over):
    out = dict(base)
    for k, v in over.items():
        if k in out and isinstance(out[k], list) and isinstance(v, list):
            out[k] = out[k] + v
        elif k in out and isinstance(out[k], dict) and isinstance(v, dict):
            out[k] = merge(out[k], v)
        else:
            out[k] = v
    return out

# ---------------------------------------------------------------- modules

CC_TYPES = {'cc_defaults', 'cc_library', 'cc_library_static',
            'cc_library_shared', 'cc_library_headers'}


class Module:
    def __init__(self, mtype, props, bpdir):
        self.type, self.props, self.dir = mtype, props, bpdir
        self.name = props.get('name')


def load_trees(roots):
    mods = {}
    for root in roots:
        for bp in sorted(glob.glob(os.path.join(root, '**', 'Android.bp'),
                                   recursive=True)):
            if '/tests/' in bp or '/test/' in bp:
                continue
            text = open(bp, encoding='utf-8').read()
            p = Parser(tokenize(text, bp), bp, {})
            for mtype, props in p.file():
                if mtype in CC_TYPES and 'name' in props:
                    mods[props['name']] = Module(mtype, props, os.path.dirname(bp))
                elif mtype == 'filegroup' and 'name' in props:
                    FILEGROUPS[props['name']] = Module(mtype, props, os.path.dirname(bp))
    return mods


FILEGROUPS = {}


def resolved(mods, m, seen=None):
    """Module props with its defaults applied, Soong-style."""
    seen = seen or set()
    props = {}
    for d in m.props.get('defaults', []):
        if d in seen:
            continue
        if d not in mods:
            raise SystemExit(f'{m.name}: unknown defaults {d}')
        props = merge(props, resolved(mods, mods[d], seen | {d}))
    own = {k: v for k, v in m.props.items() if k != 'defaults'}
    return merge(props, own)


def expand_srcs(mdir, patterns, excludes):
    out = []
    for pat in patterns:
        if pat.startswith(':'):
            fg = FILEGROUPS.get(pat[1:])
            if fg is None:
                raise SystemExit(f'{mdir}: unknown filegroup {pat}')
            out += expand_srcs(fg.dir, fg.props.get('srcs', []),
                               fg.props.get('exclude_srcs', []))
            continue
        hits = sorted(glob.glob(os.path.join(mdir, pat), recursive=True))
        if not hits and not any(c in pat for c in '*?['):
            raise SystemExit(f'{mdir}: missing source {pat}')
        out += hits
    ex = [os.path.join(mdir, e) for e in excludes]
    res = []
    for f in out:
        if any(fnmatch.fnmatch(f, e) or f == e for e in ex):
            continue
        if f not in res:
            res.append(f)
    return res


class Graph:
    def __init__(self, mods, external, root_map):
        self.mods, self.ext, self.root_map = mods, external, root_map
        self._props = {}

    def props(self, name):
        if name not in self._props:
            self._props[name] = resolved(self.mods, self.mods[name])
        return self._props[name]

    def is_ext(self, name):
        return name not in self.mods

    def ext_entry(self, name):
        if name not in self.ext:
            raise SystemExit(f'no definition for external module {name}')
        return self.ext[name]

    def exported_includes(self, name, seen=None):
        seen = seen or set()
        if name in seen:
            return []
        seen = seen | {name}
        if self.is_ext(name):
            e = self.ext_entry(name)
            out = list(e.get('include', []))
            for dep in e.get('reexport', []):
                out += self.exported_includes(dep, seen)
            return out
        p = self.props(name)
        mdir = self.mods[name].dir
        out = [os.path.join(mdir, d) for d in p.get('export_include_dirs', [])]
        reexp = (p.get('export_header_lib_headers', []) +
                 p.get('export_static_lib_headers', []) +
                 p.get('export_shared_lib_headers', []))
        for dep in reexp:
            out += self.exported_includes(dep, seen)
        return out

    def map_root(self, d):
        for prefix, target in self.root_map.items():
            if d == prefix or d.startswith(prefix + '/'):
                return os.path.join(target, d[len(prefix):].lstrip('/'))
        raise SystemExit(f'include_dirs entry {d} has no mapping')

    def include_flags(self, name):
        p = self.props(name)
        mdir = self.mods[name].dir
        dirs = [mdir]
        dirs += [os.path.join(mdir, d) for d in p.get('local_include_dirs', [])]
        dirs += [os.path.join(mdir, d) for d in p.get('export_include_dirs', [])]
        dirs += [self.map_root(d) for d in p.get('include_dirs', [])]
        for dep in (p.get('header_libs', []) + p.get('static_libs', []) +
                    p.get('whole_static_libs', []) + p.get('shared_libs', [])):
            dirs += self.exported_includes(dep)
        seen, out = set(), []
        for d in dirs:
            d = os.path.normpath(d)
            if d not in seen:
                seen.add(d)
                out.append(d)
        return out

    def static_closure(self, name, seen=None):
        """Static libs to link, in dependency order (Soong propagates the
        static deps of static libs to the final link)."""
        order = []

        def visit(n):
            if n in seen_all or self.is_ext(n):
                return
            seen_all.add(n)
            p = self.props(n)
            for d in p.get('static_libs', []) + p.get('whole_static_libs', []):
                visit(d)
            order.append(n)
        seen_all = set()
        p = self.props(name)
        for d in p.get('static_libs', []) + p.get('whole_static_libs', []):
            visit(d)
        return list(reversed(order))

    def shared_closure(self, name):
        out = []
        todo = [name] + self.static_closure(name)
        for n in todo:
            for d in self.props(n).get('shared_libs', []):
                if d not in out:
                    out.append(d)
        return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--tree', action='append', required=True)
    ap.add_argument('--external', required=True)
    ap.add_argument('--root-map', required=True,
                    help='JSON {aosp path prefix: local dir} for include_dirs')
    ap.add_argument('--target', action='append', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--config', required=True,
                    help='JSON toolchain config (cc, cxx, cflags ...)')
    args = ap.parse_args()

    mods = load_trees(args.tree)
    ext = json.load(open(args.external))
    root_map = json.load(open(args.root_map))
    cfg = json.load(open(args.config))
    g = Graph(mods, ext, root_map)

    out = os.path.abspath(args.out)
    lines = [
        f'cc = {cfg["cc"]}',
        f'cxx = {cfg["cxx"]}',
        f'ar = {cfg["ar"]}',
        'rule cc',
        '  command = $cc -MD -MF $out.d $flags -c $in -o $out',
        '  depfile = $out.d',
        '  deps = gcc',
        '  description = CC $in',
        'rule cxx',
        '  command = $cxx -MD -MF $out.d $flags -c $in -o $out',
        '  depfile = $out.d',
        '  deps = gcc',
        '  description = CXX $in',
        'rule ar',
        '  command = rm -f $out && $ar crsD $out $in',
        '  description = AR $out',
        'rule link',
        '  command = $cxx $ldflags -o $out $in $libs',
        '  description = LINK $out',
    ]
    built_static = set()
    finals = []

    def compile_module(name):
        p = g.props(name)
        incs = g.include_flags(name)
        cflags = cfg['cflags'] + p.get('cflags', [])
        common = ' '.join(shlex.quote(f) for f in cflags) + ' ' + \
            ' '.join('-I' + shlex.quote(d) for d in incs) + ' ' + \
            ' '.join('-I' + shlex.quote(d) for d in cfg.get('global_includes', []))
        cxxflags = common + ' ' + ' '.join(cfg['cppflags'] + p.get('cppflags', []))
        conly = common + ' ' + ' '.join(cfg['conlyflags'] + p.get('conlyflags', []))
        objs = []
        srcs = expand_srcs(g.mods[name].dir, p.get('srcs', []), p.get('exclude_srcs', []))
        for s in srcs:
            rel = os.path.relpath(s, '/')
            obj = os.path.join(out, 'obj', name, rel) + '.o'
            if s.endswith('.c'):
                lines.append(f'build {obj}: cc {s}\n  flags = {conly}')
            else:
                lines.append(f'build {obj}: cxx {s}\n  flags = {cxxflags}')
            objs.append(obj)
        return objs

    for target in args.target:
        for lib in g.static_closure(target):
            if lib in built_static:
                continue
            built_static.add(lib)
            objs = compile_module(lib)
            a = os.path.join(out, 'lib', lib + '.a')
            lines.append(f'build {a}: ar {" ".join(objs)}')
        objs = compile_module(target)
        archives = [os.path.join(out, 'lib', l + '.a') for l in g.static_closure(target)]
        whole = set(g.props(target).get('whole_static_libs', []))
        libs = []
        for sh in g.shared_closure(target):
            e = g.ext_entry(sh) if g.is_ext(sh) else None
            if e is None:
                raise SystemExit(f'{target}: shared dep {sh} built here is not supported')
            if e.get('lib'):
                libs.append(e['lib'])
        so = os.path.join(out, target + '.so')
        ldflags = ' '.join(cfg['ldflags'] + [f'-Wl,-soname,{target}.so'])
        arch_part = []
        for a in archives:
            nm = os.path.basename(a)[:-2]
            if nm in whole:
                arch_part.append(f'-Wl,--whole-archive {a} -Wl,--no-whole-archive')
            else:
                arch_part.append(a)
        lines.append(f'build {so}: link {" ".join(objs)} | {" ".join(archives)}\n'
                     f'  ldflags = {ldflags}\n'
                     f'  libs = -Wl,--start-group {" ".join(arch_part)} -Wl,--end-group '
                     f'{" ".join(libs)} {" ".join(cfg["ldlibs"])}')
        # `$in` for link lists objs only; archives go through $libs.
        finals.append(so)
    lines.append('default ' + ' '.join(finals))
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, 'build.ninja'), 'w') as f:
        f.write('\n'.join(lines) + '\n')
    print(f'wrote {out}/build.ninja for {", ".join(args.target)}')


if __name__ == '__main__':
    main()
