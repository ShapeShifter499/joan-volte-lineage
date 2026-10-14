#!/bin/bash
# Everything, in order: sources and inputs, Java check, native libraries,
# the ImsStack and CarrierSettings APKs and overlays, the VoWiFi APKs, and the
# three flashable zips.
set -euo pipefail
T=$(cd "$(dirname "$0")" && pwd)
"$T/setup-workdir.sh"
"$T/build-java.sh"
"$T/build-native.sh"
"$T/build-apk.sh"
"$T/build-wfc.sh"
"$T/pack-zip.sh"
