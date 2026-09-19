# The audio quality mechanism, against AOSP ImsMedia and LG

Signed-off-by: Lance <Gero3977@gmail.com>
Assisted-by: Claude-Code:claude-opus-5
Date: 2026-09-19

Written because the same subsystem was debugged four times from four
symptoms, and each pass found one more divergence in one function that a
single structural read would have found at once. The point of this file
is that the next person compares the whole shape before touching a
constant.

## The reference is ImsMedia, not LG

`libims.lge.so` carries `GetJitterBufferSize`, `SetCumulativeJitter` and
`CheckJitterBufferUpdate` and no playout buffer at all: those are RTCP
reporting accessors. LG's media runs on the modem, so an AP-side stack
has no LG reference for any of this. AOSP's
`packages/modules/ImsMedia` is the only readable one.

Checked again specifically for effects and buffering rather than assumed.
A symbol sweep of the whole binary for agc, aec, echo, effect, noise
suppression, playout, depth and buffer size returns only
`GetJitterBufferSize`, the `IPSec_*_Buffersize` message helpers, libxml2's
`xml*Depth`/`xml*BufferSize`, and `Handle_VideoCallEffect`, which is a
video call visual effect. **No audio processing and no playout buffering
of any kind.** So there is nothing to learn from LG about either the
effects question or the SLACK question, and that absence is itself the
finding: the engine is signalling only.

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

## Built-in call recording cannot work, and no permission fixes it

Asked whether granting joan the microphone would make the Dialer's call
recording work. It will not, and the reason is structural rather than a
missing grant.

`useAndroidAudioHandler()` calls `setCallAudioHandler(AUDIO_HANDLER_ANDROID)`,
which makes the Telephony Connection report `audioModeIsVoip=true`, which
Telecom turns into **`MODE_IN_COMMUNICATION`** rather than
`MODE_IN_CALL`. That is correct for joan: the RTP runs in joan's own
process, so there is no modem voice path for the radio mixer to own.

Built-in call recording taps `AudioSource.VOICE_CALL` (or the
`VOICE_DOWNLINK`/`VOICE_UPLINK` variants), which the HAL wires to that
modem voice path and which only carries audio in `MODE_IN_CALL`. On a
joan call there is nothing on it to record. The Dialer holds its own
`RECORD_AUDIO`; the permission was never the obstacle, and granting joan
anything does not change what the Dialer can tap.

`AUDIO_HANDLER_BASEBAND` is not a knob to flip either. It is for stacks
whose media really does run on the modem; selecting it would leave the
mixer expecting audio joan is not putting there.

joan *could* record its own calls -- it holds both the decoded downlink
and the captured uplink in process and could mix them -- but that is a
feature with its own consent and legal questions, not a fix. Recorded
here so the next report of "call recording is broken" is not chased as a
permissions bug.

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
