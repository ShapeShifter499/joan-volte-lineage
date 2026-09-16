# Can we adopt AOSP's ImsMedia? (spike, 2026-09-16)

Read against `packages/modules/ImsMedia` at `android-security-17.0.0_r1`.

## Why ask

Our media path is five files and about 2,200 lines, written from the
RFCs: `JoanAmr` (RFC 4867 framing), `JoanRtcp`, `JoanDtmf` (RFC 4733),
`JoanAmrCodec` and the RTP loop in `JoanMedia`. ImsMedia is 287 source
files covering the same ground, with unit tests per component.

**The one thing it has that we have nothing of is a jitter buffer.**
`AudioJitterBuffer`, `BaseJitterBuffer`, `JitterBufferControlNode` and
`JitterNetworkAnalyser`, adaptive, tested. Our playback is
`sock.receive()` -> decode -> `AudioTrack.write()`, straight through: no
reordering, no depth, no packet-loss concealment. Traces show
`jitter=0 loss=0%` because the bench network is clean. That is not a
property of our code.

It would also bring RTCP-XR, which we deliberately do not advertise
because nothing here emits the blocks.

## What the spike found

Four things that would have been reasonable blockers, and are not:

- **The API is in the module, not in `frameworks/base`.**
  `framework/src/android/telephony/imsmedia/` builds as an
  `android_library` named `ImsMediaFramework` -- a static library. An app
  can link it. No platform change is needed for the API surface.
- **`imsmedia.mk` is one inherit**, the same shape as our own
  `joan-ims.mk`: `ImsMediaService`, `libimsmedia`,
  `preinstalled-packages-imsmedia.xml`.
- **The AP-side path is the default.** `AudioSession` picks
  `AudioLocalSession` (JNI to `libimsmedia`) unless `isAudioOffload()`,
  and the only thing that sets that flag is a `@VisibleForTesting`
  setter. The `android.hardware.radio.ims.media` dependency is a
  build-time AIDL import for the offload path; joan's modem not having
  that HAL does not matter.
- **The caller supplies the sockets.** `ImsMediaManager.openSession()`
  takes a `DatagramSocket` for RTP and one for RTCP and passes them as
  `ParcelFileDescriptor`. We would bind them to the IPsec-protected
  network exactly as `JoanMedia.startRtp()` does now and hand them over.
  This was the risk I expected to end the spike, and it is the opposite:
  the design assumes somebody else owns the socket.

## What actually blocks it

**The flashable zip cannot deliver it.** `service/AndroidManifest.xml`
declares `android:sharedUserId="android.uid.phone"`, and
`service/Android.bp` has `certificate: "platform"`. Joining the phone
UID requires the same signing certificate as `com.android.phone`. The
device under test reports `ro.build.tags=release-keys` -- an official
LineageOS build signed with LineageOS's platform key, which we do not
have and should not have. PackageManager rejects a sharedUserId whose
signature does not match, so the service would not start.

This is not the `audio_effects.xml` problem again. That was a full
partition; this is a signing boundary, and no amount of space fixes it.

## The three paths

1. **ROM build / device tree.** Inherit `imsmedia.mk` alongside
   `joan-ims.mk`. Everything above then works, because the ROM signs
   `ImsMediaService` with its own platform key. This is the honest
   destination and it fits the upstreaming story already in
   `upstream/README.md`.
2. **Vendor the library into our own app.** Take `libimsmedia` and
   `imsmedia-core` and link them directly, skipping `ImsMediaService`,
   the AIDL boundary and the sharedUserId entirely. Apache-2.0 permits
   it. The cost is owning a fork of a large C++ codebase and adding an
   NDK build -- this repo builds a small C target with
   `aarch64-linux-gnu-gcc`, which is not the same thing as an NDK C++
   library with MediaCodec and AIDL dependencies.
3. **Port the jitter buffer only.** Read `JitterNetworkAnalyser` and
   `BaseJitterBuffer` and implement an adaptive buffer in `JoanMedia`,
   the way every other AOSP behaviour in this project has been adopted:
   as a reference, with our own tests.

## Recommendation

Path 1 for the whole module, as part of the device-tree work rather than
the zip. Path 3 for the jitter buffer in the meantime, because the zip
is what testers actually run and the buffer is the single largest gap
between our media path and a real one.

Path 2 is available and licensed, but it trades a bounded piece of work
for an unbounded maintenance commitment, and nothing observed so far
needs the rest of the module.

## Not a licensing question

Recorded because it came up: ImsMedia and ImsStack are Apache-2.0 and so
is this project, so copying with headers and a NOTICE entry is
straightforward. Nothing here has been avoided for licensing reasons.
The distinction that matters is different -- ImsStack's Java tree is a
configuration and agent layer over a native SIP stack that is not in the
repository, so for SIP there is repeatedly nothing to take; ImsMedia is
self-contained and the algorithms are present, which is why it is worth
this evaluation and ImsStack is worth reading.
