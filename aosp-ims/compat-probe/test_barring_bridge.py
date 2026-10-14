#!/usr/bin/env python3
"""Offline tests of the external framework bridge; scratch evidence is retained."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from make_barring_patch import API_SHA256, SOURCE_SHA256, checked, replace_once

HERE = Path(__file__).resolve().parent
KIT = HERE.parent
WORK = KIT / 'work'
ROOT = WORK / 'shim-proof'
JDK = Path('/usr/lib/jvm/java-21-openjdk/bin')
BASECP = ':'.join(str(p) for p in [WORK / 'fwcls/framework.jar',
                                   WORK / 'sdk/35/module-lib/android.jar'])
SOURCE = ROOT / 'platform-reference/a15-BarringInfo.java'
API = ROOT / 'platform-chain-01/a15-system-current.txt'


class BridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.out = Path(tempfile.mkdtemp(prefix='bridge-tests-', dir=ROOT))
        cls.patchdir = cls.out / 'framework'
        cls.call(['python3', HERE / 'make_barring_patch.py', '--source', SOURCE,
                  '--system-api', API, '--out', cls.patchdir], 'generate')
        cls.fc = cls.out / 'framework-classes'
        cls.fc.mkdir()
        cls.call([JDK / 'javac', '-proc:none', '-d', cls.fc, '-classpath', BASECP,
                  cls.patchdir / 'telephony/java/android/telephony/BarringInfo.java'] +
                 [KIT / 'stubs/java/android/annotation' / (s + '.java')
                  for s in ('IntDef', 'NonNull', 'Nullable')], 'compile-framework')

    @classmethod
    def call(cls, args, name, expected=0, cwd=None):
        r = subprocess.run([str(a) for a in args], text=True, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, timeout=90, cwd=cwd)
        (cls.out / (name + '.log')).write_text(r.stdout)
        if r.returncode != expected:
            raise AssertionError(f'{name}: expected {expected}, got {r.returncode}:\n{r.stdout}')
        return r.stdout

    def test_source_and_api_are_exact_pinned_inputs(self):
        checked(SOURCE, SOURCE_SHA256)
        checked(API, API_SHA256)

    def test_wrong_source_fails_closed(self):
        bad = self.out / 'bad.java'
        bad.write_bytes(SOURCE.read_bytes() + b'\n')
        with self.assertRaises(ValueError):
            checked(bad, SOURCE_SHA256)

    def test_anchors_fail_closed(self):
        with self.assertRaises(ValueError):
            replace_once('aaa', 'a', 'b')
        with self.assertRaises(ValueError):
            replace_once('aaa', 'x', 'b')

    def test_patch_applies_to_exact_target_and_reproduces_files(self):
        target = self.out / 'apply-check'
        target.mkdir()
        mapping = [('telephony/java/android/telephony/BarringInfo.java', SOURCE),
                   ('core/api/system-current.txt', API)]
        for rel, src in mapping:
            p = target / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, p)
        self.call(['git', 'init', '-q', target], 'init')
        patch = self.patchdir / 'frameworks-base-barring-info.patch'
        self.call(['git', 'apply', '--check', patch], 'patch-check', cwd=target)
        self.call(['git', 'apply', patch], 'patch-apply', cwd=target)
        for rel, _ in mapping:
            self.assertEqual((target / rel).read_bytes(), (self.patchdir / rel).read_bytes())

    def test_original_framework_cannot_satisfy_the_getter(self):
        red = self.out / 'red'
        red.mkdir()
        # This minimal caller uses no fixtures. Failure must be the missing
        # real framework method, not a test constructor or dependency error.
        src = red / 'NeedsGetter.java'
        src.write_text('import android.telephony.BarringInfo; class NeedsGetter { '
                       'Object read(BarringInfo b) { return b.getCellIdentity(); } }\n')
        log = self.call([JDK / 'javac', '-proc:none', '-d', red, '-classpath', BASECP, src],
                        'red-missing-getter', expected=1)
        self.assertIn('method getCellIdentity()', log)
        self.assertIn('1 error', log)

    def test_getter_bytecode_reads_the_existing_field(self):
        log = self.call([JDK / 'javap', '-c', '-p', '-classpath', self.fc,
                         'android.telephony.BarringInfo'], 'bytecode')
        section = log.split('getCellIdentity();', 1)[1].split('\n\n', 1)[0]
        self.assertIn('getfield', section)
        self.assertIn('mCellIdentity:Landroid/telephony/CellIdentity;', section)
        self.assertIn('areturn', section)
        self.assertNotIn('invoke', section)
        self.assertNotIn('aconst_null', section)

    def test_actual_framework_class_with_host_identity_fixture(self):
        host = self.out / 'host-fixture'
        host.mkdir()
        self.call([JDK / 'javac', '-proc:none', '-d', host, '-classpath',
                   str(self.fc) + ':' + BASECP,
                   HERE / 'fixtures/android/telephony/CellIdentity.java',
                   HERE / 'BarringInfoHostTest.java'], 'host-compile')
        before = hashlib.sha256((self.fc / 'android/telephony/BarringInfo.class').read_bytes()).hexdigest()
        log = self.call([JDK / 'java', '-cp', str(host) + ':' + str(self.fc) + ':' + BASECP,
                         'BarringInfoHostTest'], 'host-runtime')
        self.assertIn('checks=9', log)
        self.assertEqual(log.count('PASS '), 9)
        self.assertFalse((self.fc / 'android/telephony/CellIdentity.class').exists())
        after = hashlib.sha256((self.fc / 'android/telephony/BarringInfo.class').read_bytes()).hexdigest()
        self.assertEqual(before, after)


if __name__ == '__main__':
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(BridgeTests))
    report = {'tests': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors),
              'host_fixture_assertions_expected': 9, 'android_runtime_tested': False,
              'full_framework_build': False, 'evidence': str(BridgeTests.out)}
    (BridgeTests.out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    raise SystemExit(not result.wasSuccessful())
