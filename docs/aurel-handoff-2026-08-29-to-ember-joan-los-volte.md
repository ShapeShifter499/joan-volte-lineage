# Aurel → Ember: joan LineageOS 22.2 VoLTE bank (2026-08-29)

- **Author:** Aurel Nymvale (agent-aurel)
- **Harness:model:** Hermes-Agent:xai-oauth/grok-4.6
- **Date:** 2026-08-29
- **Repo:** `/home/kumo02/vibe-coding-projects/coding/joan-volte-lineage`
- **Branch:** `main` (local **ahead 3** of `origin/main` at handoff write — not pushed)
- **HEAD:** `e7783f8` `joan-ims: dual-send RTCP on RTP port when answer is silent`

This is evidence + next-action, not a secret dump. Do **not** log IMPI, nonce, RES, CK, IK, public identity, or dialled URIs.

---

## Standing goal

LineageOS 22.2 recovery zip: IMS wired for Dialer MO **and** MT, two-way audio, official Android audio path, call stays up (no GV ~30s drop). **Do not auto-dial.** Lance places Dialer tests.

---

## Source vs live device (keep separate)

### Source (skyforge checkout)

| Commit | What |
|---|---|
| `92d7e0f` | Two-way Dialer audio via **STREAM_MUSIC**; speaker follow; no `Supported: timer` on INVITE |
| `0c056e8` | **`MmTelFeature.setCallAudioHandler(AUDIO_HANDLER_ANDROID)`** after initiating/progressing; JoanMedia `USAGE_VOICE_COMMUNICATION` / `STREAM_VOICE_CALL`; no `setMode` / speakerphone / MUSIC |
| `0014806` | Compound RTCP SR+RR+SDES (RFC 3550, **cite** AOSP ImsMedia `RtpSession::formSrList`, krazey/ImsMedia `a8f065b` — **not imported**). Parse `a=rtcp:`. LSR/DLSR from inbound SR. **200 BYE before** `rtp_stop`. PJSIP GPL **not copied**. |
| `e7783f8` | Dual-send that SR on the **RTP 5-tuple** when the 200 has `mux=0` and no `a=rtcp:` (RFC 5761 §5.1.3 silent answer). **Not listen-tested.** |

### Live device (nest `ssh nym-nest-family`, `sudo adb -s LGUS9986e606d55`)

Banked at handoff write (2026-08-29). **Do not treat this as still-registered.**

| Item | Value |
|---|---|
| Serial | `LGUS9986e606d55` |
| Native | `/system/bin/joan-ims-ua` sha256 `acce25f76e249f052c2d6d67313183bbbe9df8c20fa9bc0265ed8e6d24bb6feb` (= local `native/build/joan-ims-aarch64` for `e7783f8`) |
| APK | `pm path` → `/system/priv-app/joan-ims/joan-ims.apk` sha256 `9e6e3e0eaa332f8fa4c33b230839888fe926c1d1c70345ec92810338f6fc8c9c` (**0c056e8** deploy). Local rebuild hash may differ; **push the path `pm path` reports**, not zip `JoanIms/`. |
| UA at bank | `STATE=4 CALL=0 ERROR=reg1 no reply rc=4` — registration **down**. After `e7783f8` push it was `STATE=3`; it later lapsed. Kick `content query --uri content://org.joan.ims.state` after IMS PDN is up; do not assume REGISTER. |

Persistent APK: `pm install -r` refuses. Push + **reboot** to pick up Java. Native: remount, push, `chcon u:object_r:netmgrd_exec:s0`, `setprop ctl.restart joan-ims`. `adb root && adb remount` every cycle. Never `sysrq-b` without `sync`.

---

## What is proven (listen / log)

1. **REGISTER 200** on LOS 22.2 (AKA + xfrm + REG2).
2. **Dialer MO ACTIVE** via AOSP `callSessionInitiated` (not CAF `callSessionStarted`).
3. **Two-way PCMU** (mic to GV, GV heard on Joan). First proven on MUSIC; then on **`mode=3` (`MODE_IN_COMMUNICATION`)** after `setCallAudioHandler(ANDROID)`: `media dl frames=… write=160 mode=3 spk=false`.
4. **BYE 200 sent** (`0014806`): `BYE 200 sent 551 B fd=8 tcp=0` — no BYE retransmit storm on that call.
5. Split cap/play, one datagram to native `:15091`. Do not go back to a single 20 ms loop.

## What failed on the last listen (0014806, before dual-send)

```
sdp media port=28284 mux=0 rtcp=0
rtp start PCMU 40000 -> 28284 mux=0 rtcp=28285
rtcp sr sent=7 mux=0 …
rtp sent climbing, recv froze at 787 (~16 s)
inbound BYE ~32 s
BYE 200 sent
```

T-Mobile 200 had **no `a=rtcp-mux` and no `a=rtcp:`**. SR went to RTP+1. Hypothesis (unproven): SBC only opens the RTP 5-tuple. `e7783f8` dual-sends SR there. **Not listen-tested** — the next Dialer MOs after that push got **no 100 Trying**.

## Latest Dialer taps (after e7783f8 restart) — signaling, not media

Two INVITEs:

```
invite built (1447 bytes)
setup timed out, CANCEL sent
invite no final reply rc=45
```

No 100/180/200. GV did not ring. Dialer showed T-Mobile **“Connection problem or invalid MMI code.”** That string is the carrier overlay for a failed IMS call, **not** a `*#` MMI. Radio: `ImsPhoneCallTracker` `DISCONNECTED` `cause=3` after hangup. UA stayed `STATE=3` until later `reg1 no reply`.

Same INVITE size (1447) as a **working** INVITE earlier the same night. Do not assume “too big.” Suspect: IPsec/port vs `sip_wait_recv` racing the main `select()` for the 100, or SAs after `ctl.restart`. **Hypothesis, not root cause.**

`fail_call` on rc=45 must **not** set `UA_STATE_ERROR` (next tap `call before register`). That part held (`STATE=3` after the timeouts).

---

## Architecture (do not undo)

Joan MPSS has **no IMSS**. AP SIP UA in `netmgrd` (xfrm). Java `ImsService` / `MmTelFeature` cannot program IPsec (`neverallow`). TCP `127.0.0.1:15090` ctl is **unauthenticated bring-up**; unix `@joan_ims_ctl` is `connectto` denied for priv_app. Do not ship TCP. Do not Magisk.

Official Android audio: `setCallAudioHandler(AUDIO_HANDLER_ANDROID)` → Telecom `MODE_IN_COMMUNICATION`. CAF/modem `AUDIO_HANDLER_BASEBAND` is the wrong handler here.

## What not to do

- Do **not** auto-dial. Lance places Dialer MO.
- Do **not** import **PhhIms** / [ProjectCiRCLE-ROM/packages_apps_PhhIms](https://github.com/ProjectCiRCLE-ROM/packages_apps_PhhIms): **GPL-2.0**, Kotlin SIP in the APK, **no RTCP**, AMR. Rewrite-the-whole-stack was considered and **rejected** for joan (xfrm neverallow + PCMU + zip-on-LOS-22).
- Do **not** import **PJSIP** (`pjmedia_rtcp_build_rtcp` GPL-2.0).
- Do **not** import krazey **ImsMedia** C++ (`RtcpSrPacket.cpp` / `formSrList`) — Apache but not byte-identical; cite only. Full ImsStack+ImsMedia is a LOS 23 ROM product, not this zip.
- Do **not** `STREAM_MUSIC`, `setMode`, `setSpeakerphoneOn`, or pin `setPreferredDevice` from ImsService.
- Do **not** advertise `Supported: timer` unless UPDATE/re-INVITE is proven (`92d7e0f` stripped it). Last good media 200 had `session-expires=none` or a huge SE; the 16 s freeze is **media**, not RFC 4028.
- Do **not** inject a 440 Hz test tone.
- Do **not** call `JoanDriver.start()` from `createCallSession`.
- Do **not** treat a matching hash on unused `JoanIms/` as deployed.

---

## Next action for Ember (ordered)

1. Rebind: `ctlprobe STATUS`, `pm path`, sha256 native+APK vs `e7783f8` / `0c056e8`. If `reg1 no reply`, wait for IMS PDN (`dumpsys connectivity` IMS), then `content query --uri content://org.joan.ims.state`. Do not reboot unless Java must change.
2. **Fix silent INVITE** (no 100 after `ctl.restart`) before another listen. Prove INVITE send + wait do not race `ua_select_handle`. Log sendto result / IPsec counters without identities.
3. Only then ask Lance for **one Dialer MO**, leave >30 s. Discriminators:
   - `sdp media port= mux= rtcp=`
   - `rtcp sr sent= … dual=1` (if mux=0 rtcp=0)
   - `recv` still climbing past ~16 s
   - `media dl … mode=3`
   - `BYE 200 sent` if they hang up
4. MT `notifyIncomingCall` and unix ctl without TCP are later.

---

## References (public)

- AOSP Implement IMS: https://source.android.com/docs/core/connect/ims
- `MmTelFeature.setCallAudioHandler` → `onAudioModeIsVoipChanged` → Telecom `MODE_IN_COMMUNICATION`
- krazey ImsStack / ImsMedia (AOSP 17 fork): https://github.com/krazey/ImsStack https://github.com/krazey/ImsMedia
- RFC 3550 §6.1/§6.4.1, RFC 5761 §5.1.3, RFC 3605, RFC 4028
- Skill: `mobile-linux-hardware-bringup` → `references/joan-lineage-ims-dialer.md`

## Do-not-repeat

Hardware boot, zip flash, Magisk, public share links, `sysrq-b` without sync, auto-dial, pushing only `JoanIms/` .
