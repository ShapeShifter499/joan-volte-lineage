# joan-volte-lineage

Open-source VoLTE (AP SIP UA) as a **TWRP / Lineage recovery zip** for
LG V30 (`joan` / US998 / H930 / H932). No Magisk. No stock `Ims6` blobs.

This is not a full Android `ImsService` yet. It installs the portable 3GPP UA
plus **appended CIL sepolicy** so an init service can run Enforcing.

## Staged ROM (not in this git tree)

`/data/models/joan-lineage-22.2/` (SHA256 verified 2026-08-26):

- `lineage-22.2-20260823-nightly-joan-signed.zip`
- `recovery.img` (Lineage; optional if you already have TWRP)
- `MindTheGapps-15.0.0-arm64-20250812_214357.zip` — [Lineage GApps wiki](https://wiki.lineageos.org/gapps/) ARM64 / LOS 22

Flash order in **Lineage recovery** (TWRP cannot apply this ROM’s dynamic partitions):

1. Lineage zip (sideload)
2. MindTheGapps (sideload; skip signature)
3. `out/joan-volte-lineage-recovery-unsigned.zip` (sideload; skip signature)

The VoLTE zip is a shell `update-binary` (same shape as MindTheGapps). It remounts **vendor ext4 rw** and **appends** CIL to `vendor_sepolicy.cil`. It does not replace vendor sepolicy. A bad CIL can fail boot; installer keeps `vendor_sepolicy.cil.bak-joan-imsd`.

Do not flash `abl` / `xbl` / `tz` / `hyp` / `keymaster` / `laf` from stock.

## How sepolicy is applied

Init does not glob extra `.te` files. The installer **appends**
`sepolicy/joan-imsd.cil` onto a CIL file init already loads, in order:

1. `/product/etc/selinux/product_sepolicy.cil`
2. else `/vendor/etc/selinux/vendor_sepolicy.cil`

It also appends `file_contexts` lines. A marker (`joan-imsd-cil-v1`) makes
re-flashing idempotent. A bad CIL can fail boot (init aborts policy load);
the zip backs up the original `.cil` next to it as `.bak-joan-imsd`.

## Build the zip

```sh
./scripts/pack-zip.sh
# -> out/joan-volte-unsigned.zip
```

## Runtime note

Android 15 does not ship Python. The zip currently installs the portable
Python UA under `/system/etc/joan-imsd/` and a wrapper at `/system/bin/joan-ims`.
The wrapper will no-op with a log until a bionic `python3` (or a native UA)
is on PATH. Kernel IPsec on Lineage joan 4.4 is already `INET6_ESP=y`.
