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
PRIVILEGED = {
    "android.permission.MODIFY_PHONE_STATE",
    "android.permission.READ_PRIVILEGED_PHONE_STATE",
    "android.permission.READ_PRECISE_PHONE_STATE",
    "android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS",
    "android.permission.BIND_IMS_SERVICE",
    "android.permission.LOCATION_BYPASS",
}
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
