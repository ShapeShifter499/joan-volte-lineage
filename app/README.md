# Compile the JoanIms app (out of tree)

The ImsService lives in `../ims-service`. That is the VoLTE stack:
REGISTER, INVITE, RTP, AKA, and IPsec via `IpSecManager`. There is no
native daemon to build or install.

## Host tests

```sh
./tests/run-host-tests.sh
```

## Recovery zip (sideload)

Needs a JDK, Android SDK `build-tools` + `platforms/android-36`, and
`keytool`. From the repo root:

```sh
./scripts/pack-zip.sh
# -> out/joan-volte-recovery.zip
./scripts/pack-cleanup-zip.sh
# -> out/joan-volte-uninstall.zip
```

Flash `joan-volte-recovery.zip` in Lineage recovery. Flash
`joan-volte-uninstall.zip` to take it off again.

## In-tree

For a LineageOS 22 product inherit, see `../upstream/`.
