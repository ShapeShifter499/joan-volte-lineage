#!/usr/bin/env bash
# Host unit tests for joan-volte-lineage.
# Builds the host test binary from native/ sources and runs it.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD

CC=${CC:-cc}
CFLAGS="-O2 -Wall -Wextra -Wno-unused-parameter -Isrc"

cd native
SRC="src/util.c src/md5.c src/secagree.c src/sip.c src/config.c"
STUBS="tests/stub_xfrm_ctl.c"
TESTS="tests/test_sip_units.c"

mkdir -p build
$CC $CFLAGS -Isrc -o build/joan-ims-host-test \
    $SRC $STUBS $TESTS 2>/dev/null
./build/joan-ims-host-test

echo "== apn overlay merge"
"$ROOT/tests/apn/run-apn-tests.sh"
"$ROOT/tests/audio/run-agc-tests.sh"

echo "== carrier assets"
"$ROOT/tests/carrier/run-carrier-tests.sh"
"$ROOT/tests/installer/run-installer-tests.sh"
# Real update-binary on loop-mounted ext4 images. Needs root; skips without.
"$ROOT/tests/installer/run-e2e-install.sh"

# The UA suite was NOT run here, and that hole cost four tester builds.
# A stale assertion on the registration expiry went red in alpha57 and
# stayed red through alpha58, 62, 63 and 64 without anyone seeing it,
# because the only gate being run was this file plus project-profile's
# FAST check, and neither invoked it. It also blocked a sibling's work.
"$ROOT/tests/run-ua-tests.sh"

echo "== java sip/crypto (host javac)"
JAVA_SRC="$ROOT/ims-service/src/org/joan/ims"
JAVA_TEST="$ROOT/tests/java"
JAVA_OUT="$ROOT/native/build/java-host"
mkdir -p "$JAVA_OUT"
javac -d "$JAVA_OUT" \
    "$JAVA_SRC/JoanSipCrypto.java" \
    "$JAVA_SRC/JoanSecAgree.java" \
    "$JAVA_SRC/JoanSipBuilder.java" \
    "$JAVA_SRC/JoanAmr.java" \
    "$JAVA_SRC/JoanRtcp.java" \
    "$JAVA_SRC/JoanDtmf.java" \
    "$JAVA_SRC/JoanSessionTimer.java" \
    "$JAVA_SRC/JoanRegInfo.java" \
    "$JAVA_SRC/JoanJitter.java" \
    "$JAVA_SRC/JoanPcmu.java" \
    "$JAVA_SRC/JoanEfDir.java" \
    "$JAVA_SRC/JoanIsim.java" \
    "$JAVA_SRC/JoanXcap.java" \
    "$JAVA_SRC/JoanT140.java" \
    "$JAVA_TEST/org/joan/ims/TestJoanSip.java"
java -cp "$JAVA_OUT" org.joan.ims.TestJoanSip

echo "== rtt wire format + carrier config"
SDK_JAR=${ANDROID_SDK:-$HOME/Android/Sdk}/platforms/android-36/android.jar
RTT_OUT="$ROOT/native/build/java-rtt-host"
mkdir -p "$RTT_OUT"
javac -cp "$SDK_JAR" -d "$RTT_OUT" \
    "$JAVA_SRC/JoanT140.java" \
    "$JAVA_SRC/JoanImsRttConfig.java" \
    "$JAVA_TEST/org/joan/ims/TestJoanRtt.java"
java -cp "$RTT_OUT:$SDK_JAR" org.joan.ims.TestJoanRtt

echo "== sip capture redaction"
REDACT_OUT="$ROOT/native/build/java-redact-host"
mkdir -p "$REDACT_OUT"
javac -d "$REDACT_OUT" \
    "$JAVA_SRC/JoanSipRedact.java" \
    "$JAVA_TEST/org/joan/ims/TestJoanSipCapture.java"
java -cp "$REDACT_OUT" org.joan.ims.TestJoanSipCapture

echo "== shipped xml is well formed"
# A malformed XML in this package does not fail loudly. aapt2 catches the
# manifest, but etc/permissions and etc/default-permissions are parsed by
# the platform at boot and a broken one is simply ignored: the pre-grant
# would never happen and nothing would say so. "--" inside a comment is
# not legal XML and is exactly how this got shipped once.
python3 - "$ROOT" <<'PYXML'
import sys, os, glob
import xml.etree.ElementTree as ET
root = sys.argv[1]
files = [os.path.join(root, "ims-service/AndroidManifest.xml")]
files += sorted(glob.glob(os.path.join(root, "permissions/*.xml")))
files += sorted(glob.glob(os.path.join(root, "rro/AndroidManifest.xml")))
files += sorted(glob.glob(os.path.join(root, "rro-fw/AndroidManifest.xml")))
files += sorted(glob.glob(os.path.join(root, "apn/*.xml")))
bad = 0
for f in files:
    if not os.path.exists(f):
        continue
    try:
        ET.parse(f)
        print("ok   %s parses" % os.path.relpath(f, root))
    except Exception as e:
        print("FAIL %s: %s" % (os.path.relpath(f, root), e))
        bad += 1

# A requested signature|privileged permission with no privapp-permissions
# entry is a FATAL BOOT ERROR under ro.control_privapp_permissions=enforce
# (the LineageOS default), not a silent denial. Nothing in the build
# catches that; the device simply does not come back. So the invariant is
# checked here instead of remembered.
#
# Which permissions are signature|privileged is read from the platform's
# own manifest inside the SDK's android.jar when aapt2 can dump it, rather
# than from a hand-kept list that silently goes stale.
import subprocess, re
PRIVILEGED = {
    "android.permission.MODIFY_PHONE_STATE",
    "android.permission.READ_PRIVILEGED_PHONE_STATE",
    "android.permission.READ_PRECISE_PHONE_STATE",
    "android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS",
    "android.permission.BIND_IMS_SERVICE",
    "android.permission.LOCATION_BYPASS",
    "android.permission.CAPTURE_AUDIO_OUTPUT",
    "android.permission.WRITE_APN_SETTINGS",
}
sdk = os.environ.get("ANDROID_SDK", os.path.expanduser("~/Android/Sdk"))
jar = os.path.join(sdk, "platforms", "android-35", "android.jar")
bts = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2")))
if os.path.exists(jar) and bts:
    dump = subprocess.run([bts[-1], "dump", "xmltree", jar, "--file",
                           "AndroidManifest.xml"], capture_output=True,
                          text=True).stdout
    derived, cur = set(), None
    for line in dump.splitlines():
        t = line.strip()
        if t.startswith("E: "):
            cur = {} if t.startswith("E: permission ") else None
            continue
        if cur is None:
            continue
        m = re.search(r':name\(0x01010003\)="([^"]+)"', t)
        if m:
            cur["name"] = m.group(1)
        m = re.search(r':protectionLevel\(0x01010009\)=(0x[0-9a-f]+)', t)
        if m:
            cur["pl"] = int(m.group(1), 16)
        if "name" in cur and "pl" in cur:
            if cur["pl"] & 0xf == 2 and cur["pl"] & 0x10:
                derived.add(cur["name"])
            cur = None
    if len(derived) > 100:
        PRIVILEGED = derived
        print("ok   %d signature|privileged permissions read from the platform manifest" % len(derived))
mf = ET.parse(os.path.join(root, "ims-service/AndroidManifest.xml")).getroot()
ns = "{http://schemas.android.com/apk/res/android}"
asked = {e.get(ns + "name") for e in mf.iter("uses-permission")}
allow = set()
pa = ET.parse(os.path.join(root, "permissions/org.joan.ims.xml")).getroot()
for e in pa.iter("permission"):
    allow.add(e.get("name"))
missing = sorted((asked & PRIVILEGED) - allow)
if missing:
    for m in missing:
        print("FAIL %s is requested but not allowlisted: this bootloops the device" % m)
    bad += len(missing)
else:
    n = len(asked & PRIVILEGED)
    print("ok   all %d privileged permissions requested are allowlisted" % n)

# And the allowlist is APPEND-ONLY. PackageManager can go on parsing ANY
# earlier build's cached manifest after a flash (see the comment in
# permissions/org.joan.ims.xml), so a privileged permission that any
# manifest in history requested must stay allowlisted forever. Dropping
# BIND_IMS_SERVICE after alpha67 is what bootlooped every upgrade.
hist = set()
try:
    revs = subprocess.run(["git", "-C", root, "log", "--format=%H", "--",
                           "ims-service/AndroidManifest.xml"],
                          capture_output=True, text=True, check=True).stdout.split()
    for r in revs:
        body = subprocess.run(["git", "-C", root, "show",
                               r + ":ims-service/AndroidManifest.xml"],
                              capture_output=True, text=True).stdout
        hist |= set(re.findall(r'uses-permission\s+android:name="([^"]+)"', body))
except Exception as e:
    revs = []
if revs:
    gone = sorted((hist & PRIVILEGED) - allow)
    if gone:
        for m in gone:
            print("FAIL %s was requested by an earlier build and is no longer allowlisted: an upgrade with a stale package cache bootloops" % m)
        bad += len(gone)
    else:
        print("ok   allowlist covers all %d privileged permissions any of %d manifest revisions requested"
              % (len(hist & PRIVILEGED), len(revs)))
else:
    print("skip no git history here; allowlist history not checked")

if bad:
    sys.exit(1)
PYXML

echo "== pani cell id"
PANI_OUT="$ROOT/native/build/java-pani-host"
mkdir -p "$PANI_OUT"
javac -d "$PANI_OUT" \
    "$JAVA_SRC/JoanAccessInfo.java" \
    "$JAVA_TEST/org/joan/ims/TestJoanAccessInfo.java"
java -cp "$PANI_OUT" org.joan.ims.TestJoanAccessInfo

echo "== viettel volte carrier gate"
SDK_JAR=${ANDROID_SDK:-$HOME/Android/Sdk}/platforms/android-36/android.jar
GATE_OUT="$ROOT/native/build/java-gate-host"
mkdir -p "$GATE_OUT"
javac -cp "$SDK_JAR" -d "$GATE_OUT" \
    "$JAVA_SRC/JoanVolteCarrierGate.java" \
    "$JAVA_SRC/JoanImsVoiceConfig.java" \
    "$JAVA_SRC/JoanSessionTimer.java" \
    "$JAVA_SRC/JoanCodecConfig.java" \
    "$JAVA_SRC/JoanCarrierProfile.java" \
    "$JAVA_SRC/JoanSipBuilder.java" \
    "$JAVA_SRC/JoanSipCrypto.java" \
    "$JAVA_SRC/JoanSecAgree.java" \
    "$JAVA_SRC/JoanAmr.java" \
    "$JAVA_TEST/org/joan/ims/TestJoanVolteCarrierGate.java"
exec java -cp "$GATE_OUT:$SDK_JAR" org.joan.ims.TestJoanVolteCarrierGate
