# The audio quality mechanism, against AOSP ImsMedia and LG

Signed-off-by: Lance <Gero3977@gmail.com>
Assisted-by: Claude-Code:claude-opus-5
Date: 2026-09-19

Written because the same subsystem was debugged four times from four
symptoms, and each pass found one more divergence in one function that a
single structural read would have found at once. The point of this file
is that the next person compares the whole shape before touching a
constant.

## Two references, not one -- LG's media is on the AP after all

An earlier version of this file said LG had no playout buffer and that
its media ran on the modem. That was wrong, and wrong because only one
library had been looked in. `libims.lge.so` is the signalling engine and
genuinely carries nothing but `GetJitterBufferSize`,
`SetCumulativeJitter` and `CheckJitterBufferUpdate`, which are RTCP
reporting accessors. The media lives elsewhere on the same device:

- `/product/lib64/libimsmmpf.lge.so` -- LG's multimedia platform
  framework, with **`MMPFJitterBuffer`**, a full media graph
  (`MMPFGraphRx`/`MMPFGraphTx`), RTP payload encoders and decoders, and a
  voice source and renderer. C++ symbols, demangles cleanly.
- `/vendor/lib64/lib-rtpcore.so` -- Qualcomm's RTP core, exporting
  `qvp_rtp_get_playout_delay`, `qvp_rtp_empty_jitter_buffer` and
  `qvp_rtcp_get_rx_jitter`.

So joan has two AP-side references for this subsystem, not one, and they
agree with each other on the two questions that were open.

**Sizing is parameterised identically in both.**
`MMPFJitterBuffer::SetJitterBufferSize(unsigned int, unsigned int,
unsigned int)` takes three values, exactly as AOSP's
`SetJitterBufferSize(nInit, nMin, nMax)` does. joan's initial 4,
`MIN_DEPTH` 3 and `MAX_DEPTH` 9 are the same three parameters. LG's
actual numbers are not recoverable from configuration -- a sweep of all
224 distinct keys extracted from `Ims6.apk` finds nothing about jitter,
buffering, playout, delay or effects -- so LG's values are compiled in.

**Neither reference attaches any audio effect.** AOSP's ImsMedia has no
AEC, NS or AGC in either direction. LG's framework has only *video*
effects: `RequestVideoEffect`, `GetVideoEffectMode`,
`UpdateVideoEffectMode`, and nothing for audio at all. Two independent
implementations, both relying on the platform's voice-communication path
to do the processing. joan attaches `AutomaticGainControl` and
`AcousticEchoCanceler` and is alone in that.

On the handsets that have reported, the AGC half is moot anyway: every
trace shows `platform_agc=false`, meaning
`AutomaticGainControl.isAvailable()` returns false and it never attaches.
The AEC half was unmeasured until alpha73 added `media effects agc= aec=`
to the trace. One tester run decides it.

`SLACK` has no counterpart in either reference and remains joan's own:
AOSP bounds the raw queue at `MAX_QUEUE_SIZE`, 150 frames, and LG exposes
no equivalent at all.

## Four subsystems, not one

| AOSP | joan | state |
|---|---|---|
| `JitterNetworkAnalyser` — sizing | `JoanJitter.adapt()` | matches |
| `AudioJitterBuffer` — Add/Get/DTX/drop-rate/Resync/redundancy | `JoanJitter` offer/poll | mostly; redundancy absent |
| `MediaQualityAnalyzer` + `RtcpXrEncoder` — measurement | — | **absent** |
| `ImsMediaAudioSource`/`Player` — capture and playout | `JoanMedia` | diverges, see below |

Every earlier pass compared only the first two.

## What matches, verified line by line

`adapt()` is `GetNextJitterBufferSize`: target =
`(worst_transit_offset * weight + ROUNDUP_MARGIN) / PACKET_INTERVAL`,
jump straight to target when it exceeds the current depth, decrease only
after a settling period. Same constants, and they were checked rather
than assumed:

| | AOSP | joan |
|---|---|---|
| min depth | `AUDIO_JITTER_BUFFER_MIN_SIZE` 3 | `MIN_DEPTH` 3 |
| max depth | `AUDIO_JITTER_BUFFER_MAX_SIZE` 9 | `MAX_DEPTH` 9 |
| start | `AUDIO_JITTER_BUFFER_START_SIZE` 4 | initial `depth` 4 |
| frame | `FRAME_INTERVAL` 20 ms | `PACKET_INTERVAL_MS` 20 |
| roundup | `ROUNDUP_MARGIN` 10 ms | `ROUNDUP_MARGIN_MS` 10 |
| weight | `MARGIN_WEIGHT` 1.0 | `BUFFER_WEIGHT` 1 |
| decrease after | `BUFFER_DECREASE_TH` 2000 ms | `DECREASE_THRESHOLD_MS` 2000 |
| decrease step | `BUFFER_IN_DECREASE_SIZE` 2 | `DECREASE_STEP` 2 |
| history | `MAX_JITTER_LIST_SIZE` 150 | `MAX_HISTORY` 150 |
| drop window | `DROP_WINDOW` 5000 ms | `DROP_WINDOW_MS` 5000 |
| DTX thresholds | 80 / 35 % | `RESET_THRESHOLD_DTX` 80 / `_NO_DTX` 35 |

`MIN_DEPTH` was 2 until 2026-09-19 and was the only one that did not
match.

## What diverges, deliberately

**Queue bound.** AOSP drains to `mMaxJitterBufferSize` for the first three
seconds of a call and to `MAX_QUEUE_SIZE` (150 frames, three seconds)
afterwards, and every drain is a `Resync` that re-anchors the playout
timestamp in the same operation. joan bounds continuously at
`depth + SLACK`. That bound is why a tester heard stutter with
`depth=9 loss=0% late=0 trimmed=16` of 575 frames: at the ceiling the
adaptation had already decided the link needed the deepest buffer and the
bound discarded sixteen frames anyway. `SLACK` went 3 -> 6 as a middle
position, and `qpeak` was added so the next trace sizes it from the
observed burst rather than from a second guess.

**Audio effects.** AOSP attaches none -- no AEC, no NS, no AGC, in either
direction -- and relies on the voice-communication preset routing through
the platform's own processing. joan attaches `AutomaticGainControl` and
`AcousticEchoCanceler` to the capture session. Unmeasured, and a
plausible contributor to quality complaints on a source that already has
platform AEC behind it.

**Capture and playout API.** AOSP uses AAudio with
`SHARING_MODE_EXCLUSIVE`, `PERFORMANCE_MODE_LOW_LATENCY`,
`INPUT_PRESET_VOICE_COMMUNICATION` and `setPrivacySensitive(true)`. joan
uses `AudioRecord`/`AudioTrack` at defaults with
`MediaRecorder.AudioSource.VOICE_COMMUNICATION`.

## Call recording: wireable, with one real risk to test

An earlier version of this section said built-in call recording could not
work here. That was half right and stated too broadly, and the half that
was wrong matters.

What is true: `useAndroidAudioHandler()` calls
`setCallAudioHandler(AUDIO_HANDLER_ANDROID)`, the Connection reports
`audioModeIsVoip=true`, and Telecom therefore uses
**`MODE_IN_COMMUNICATION`** rather than `MODE_IN_CALL`. There is no modem
voice path on a joan call, so `AudioSource.VOICE_CALL` -- the
bidirectional tap -- has nothing on it. `AUDIO_HANDLER_BASEBAND` is not
an alternative; it is for stacks whose media really is on the modem.

What is wrong: LineageOS does not default to `VOICE_CALL`. From its
Dialer's `callrecord/res/values/config.xml`:

    call_recording_enabled      = false
    call_recording_audio_source = 1      // MIC; 4 = VOICE_CALL is the
                                         // alternative for devices with
                                         // bidirectional capture

`CallRecorderService` reads both as resources -- `getAudioSource()`
returns `R.integer.call_recording_audio_source` -- and checks
`RECORD_AUDIO` for itself. **MIC captures perfectly well in
`MODE_IN_COMMUNICATION`**, so the Dialer's recorder is not structurally
incompatible with a joan call at all.

So the wiring is three resource overrides, all RRO-able, and joan already
ships RROs (`joan-ims-rro.apk`, `joan-fw-volte.apk`):

- `call_recording_enabled` -> true
- `call_record_states.xml` -- LineageOS gates on the **current country by
  MCC**, per-country entries, which is the gating Lance asked about and is
  overridable the same way
- `call_recording_audio_source` -- leave at MIC

**The risk, which is why this is not shipped yet.** joan holds an
`AudioRecord` on `VOICE_COMMUNICATION` for the whole call. The Dialer
would open a second capture on `MIC`. Android's concurrent-capture policy
decides which client gets real audio and which gets silence, and the
outcome is not obvious from here: if the Dialer wins, **joan's uplink
goes silent and the call breaks**, which is a far worse failure than not
having recording.

That has to be tested on a handset before it ships, and the test is
cheap: enable the two resources, place a call, record, and read
`media ul level` from the trace. If the uplink levels collapse when
recording starts, the answer is no.

A recording made this way is also near-end only on the earpiece; the far
end is captured only acoustically on speakerphone. joan holds both
directions in process and could mix a true two-way recording itself, but
that is a feature with consent and legal questions attached, not a
wiring change.

## What is absent

- **Redundancy.** `GetRedundantFrame` and `GetPartialRedundancyFrame`
  recover lost frames from redundant copies. joan cannot.
- **Quality measurement.** `MediaQualityAnalyzer`, `CollectRxRtpStatus`,
  `CollectJitterBufferStatus`, `GetMeanBufferSize` and `RtcpXrEncoder`
  feed RTCP-XR and `CallQuality`. joan has ad-hoc counters and no XR.
- **`Resync` as a named operation.** joan re-anchors inside its poll-path
  trim, which covers the same ground because `MAX_QUEUE >= depth + SLACK`
  means the poll trim always runs when the offer trim did -- checked with
  a probe, because the opposite was asserted first and was wrong.

## The three defects that were in one function

`shrinkOnSilence` diverged from AOSP's "decrease delay" branch in three
independent ways, each found in a separate pass:

1. it dropped `queue.firstKey()` unconditionally, where AOSP drops only
   when the head of the queue is itself SID -- so it paid for latency
   with speech the caller was about to hear;
2. it ignored `DECREASE_THRESHOLD_MS` and `lastGrowAtMs`, which already
   existed for exactly this;
3. it ignored whether the adaptation wanted less depth at all, which is
   AOSP's `mUpdatedDelay < 0` -- so on a link whose measurements
   justified the depth, every comfort-noise frame pulled it down anyway
   and it walked to the floor.

Any one of them alone still produced `depth=2` on a clean link.
