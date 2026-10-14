#!/usr/bin/env python3
"""Offline tests for the Android 15 IARI backport.

These are HOST tests over exact pinned public sources. They prove the backport
is well-formed and correctly anchored. They do NOT prove it compiles inside a
LineageOS tree, links, installs, or works on a phone.

Deliberately asserted, because each one is a real design constraint:
  * every insertion anchor resolves exactly once (a wrong anchor aborts, so a
    silent partial backport is impossible);
  * the generated patches apply to PRISTINE pinned inputs and reproduce the
    generator output byte-for-byte;
  * the A17 aconfig gate is genuinely absent from A15, which is why it is
    dropped rather than copied;
  * EVENT_GET_IARI_DONE = 48 does not collide with any existing A15
    SIMRecords event number.
"""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
SHIM = REPO / 'work/shim-proof'
GEN = HERE / 'make_iari_backport.py'

PRISTINE = {
    'a15-TelephonyManager.java': SHIM / 'platform-reference/a15-TelephonyManager.java',
    'a15-PhoneSubInfoController.java':
        SHIM / 'platform-chain-01/a15-PhoneSubInfoController.java',
    'a15-IccRecords.java': SHIM / 'iari-chain-01/a15-IccRecords.java',
    'a15-IsimUiccRecords.java': SHIM / 'iari-chain-01/a15-IsimUiccRecords.java',
    'a15-SIMRecords.java': SHIM / 'iari-chain-01/a15-SIMRecords.java',
    'a15-IPhoneSubInfo.aidl': SHIM / 'iari-chain-01/a15-IPhoneSubInfo.aidl',
}

REQUIRED = ['a15-IPhoneSubInfo.aidl', 'a15-TelephonyManager.java',
            'a15-PhoneSubInfoController.java', 'a15-IccRecords.java',
            'a15-IsimUiccRecords.java', 'a15-SIMRecords.java']


def latest_attempt():
    dirs = sorted((d for d in SHIM.glob('iari-backport-*') if d.is_dir()),
                  key=lambda d: d.stat().st_mtime)
    if not dirs:
        raise RuntimeError('no iari-backport-* attempt directory; run '
                           'make_iari_backport.py first')
    return dirs[-1]


class IariBackportTests(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.attempt = latest_attempt()
        cls.report = json.loads((cls.attempt / 'report.json').read_text())

    # -- structural -------------------------------------------------------
    def test_every_required_file_was_patched(self):
        for name in REQUIRED:
            self.assertTrue((self.attempt / 'patched' / name).exists(),
                            f'no patched output for {name}')
            self.assertTrue((self.attempt / f'{name}.patch').exists(),
                            f'no patch for {name}')

    def test_insertions_actually_changed_the_files(self):
        for name, info in self.report['files'].items():
            self.assertGreater(info['insertions'], 0, name)
            self.assertGreater(info['patched_bytes'], info['input_bytes'], name)
            self.assertNotEqual(info['input_sha256'], info['patched_sha256'], name)

    def test_brace_balance_is_preserved(self):
        """The insertion must not open or close a block it does not balance."""
        for name in REQUIRED:
            orig = PRISTINE[name].read_text()
            patched = (self.attempt / 'patched' / name).read_text()
            delta = ((patched.count('{') - patched.count('}'))
                     - (orig.count('{') - orig.count('}')))
            self.assertEqual(delta, 0, f'{name} brace balance shifted by {delta}')

    # -- patches are real -------------------------------------------------
    def test_patches_apply_to_pristine_inputs_and_reproduce_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            for name, src in PRISTINE.items():
                shutil.copy2(src, work / name)
            subprocess.run(['git', 'init', '-q', '.'], cwd=work, check=True)
            for name in REQUIRED:
                patch = self.attempt / f'{name}.patch'
                proc = subprocess.run(['git', 'apply', '--check', str(patch)],
                                      cwd=work, capture_output=True, text=True)
                self.assertEqual(proc.returncode, 0,
                                 f'{name} does not apply:\n{proc.stderr}')
            subprocess.run(['git', 'apply'] + [str(self.attempt / f'{n}.patch')
                                               for n in REQUIRED],
                           cwd=work, check=True)
            for name in REQUIRED:
                got = (work / name).read_bytes()
                want = (self.attempt / 'patched' / name).read_bytes()
                self.assertEqual(hashlib.sha256(got).hexdigest(),
                                 hashlib.sha256(want).hexdigest(),
                                 f'{name}: applied patch != generator output')

    # -- the two deliberate design constraints ---------------------------
    def test_a17_aconfig_flag_is_absent_from_a15(self):
        """Documents WHY the A17 gate is dropped rather than copied."""
        tm = PRISTINE['a15-TelephonyManager.java'].read_text()
        self.assertNotIn('support_ims_registration_event_download', tm)
        self.assertNotIn('SUPPORT_IMS_REGISTRATION_EVENT_DOWNLOAD', tm)
        apitxt = (SHIM / 'platform-chain-01/a15-system-current.txt').read_text()
        self.assertNotIn('support_ims_registration_event_download', apitxt)

    def test_backported_api_is_not_flag_gated(self):
        """The dropped gate must be visible in the code, not silently absent."""
        tm = (self.attempt / 'patched/a15-TelephonyManager.java').read_text()
        i = tm.index('public void requestUiccIari(')
        block = tm[max(0, i - 1400):i]
        self.assertIn('@SystemApi', block)
        self.assertIn('READ_PRIVILEGED_PHONE_STATE', block)
        self.assertNotIn('@FlaggedApi', block,
                         'a FlaggedApi here would reference a nonexistent A15 flag')
        self.assertTrue(self.report['dropped_a17_aconfig_gate'])

    def test_iari_event_number_does_not_collide(self):
        """A17 uses 48; that slot must be free in A15's SIMRecords."""
        import re
        sim = PRISTINE['a15-SIMRecords.java'].read_text()
        used = {int(m) for m in re.findall(
            r'=\s*(\d+)\s*\+\s*SIM_RECORD_EVENT_BASE', sim)}
        self.assertNotIn(48, used, 'EVENT_GET_IARI_DONE=48 would collide')
        patched = (self.attempt / 'patched/a15-SIMRecords.java').read_text()
        self.assertIn('EVENT_GET_IARI_DONE = 48 + SIM_RECORD_EVENT_BASE', patched)
        after = {int(m) for m in re.findall(
            r'=\s*(\d+)\s*\+\s*SIM_RECORD_EVENT_BASE', patched)}
        self.assertEqual(len(after), len(used) + 1)

    def test_api_txt_is_reported_not_guessed(self):
        entries = self.report['api_txt_required_entries']
        self.assertEqual(len(entries), 3)
        self.assertFalse((SHIM / 'iari-chain-01/a15-system-current.txt.patched')
                         .exists(),
                         'api txt must not be hand-edited; metalava owns it')


if __name__ == '__main__':
    unittest.main(verbosity=2)
