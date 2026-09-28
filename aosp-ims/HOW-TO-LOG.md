# How to log a problem (AOSP IMS on the LG V30)

When VoLTE doesn't register, a call fails, or Wi-Fi calling won't come
up, these logs are what lets us find out why. It takes about five
minutes, needs a computer with adb, and **no root**.

## Before you start (once)

1. **USB debugging.** Settings > About phone: tap *Build number* seven
   times. Then Settings > System > Developer options: turn on *USB
   debugging*. Connect the phone and accept the prompt on its screen.
2. **adb on the computer.** Google's
   [platform-tools](https://developer.android.com/tools/releases/platform-tools)
   for Windows, macOS or Linux. `adb devices` must list the phone as
   `device` (not `unauthorized`).
3. **A bigger log buffer.** Developer options > *Logger buffer sizes* >
   **16M**. With the default, the phone overwrites the start of a failed
   registration within minutes, and the start is the part we need.

On **Windows**, run the commands below in *Command Prompt* (cmd), not
PowerShell, so the files come out as plain text.

## 1. Make it happen again

- **VoLTE never registers:** turn airplane mode on, wait 10 seconds, turn
  it off, then wait **one minute**.
- **A call fails, drops or has no audio:** make the call (or have
  someone call you). Note the time and which way the call went.
- **Wi-Fi calling:** connect to Wi-Fi, turn Wi-Fi calling on
  (Settings > Network & internet > SIMs), and wait **one minute**.

Collect the logs **right after**, before rebooting.

## 2. Collect the logs

Copy and paste these, one line at a time:

```
adb logcat -b all -d > logcat.txt
adb shell dumpsys activity service com.android.imsstack/.imsservice.ImsService > ims.txt
adb shell dumpsys telephony.registry > registry.txt
adb shell dumpsys connectivity > conn.txt
adb shell dumpsys carrier_config > carrier_config.txt
adb shell dumpsys package com.android.imsstack > package.txt
adb shell "getprop ro.lineage.version; getprop ro.build.version.incremental; getprop ro.telephony.block_binder_thread_on_incoming_calls; getprop gsm.sim.operator.numeric; getprop gsm.operator.numeric" > props.txt
```

For a **Wi-Fi calling** problem, also:

```
adb shell dumpsys activity service com.google.android.iwlan > iwlan.txt
adb shell dumpsys activity service com.android.telephony.qns > qns.txt
```

`logcat.txt` should be several MB. If it is tiny, the buffer was just
cleared (a reboot does that): make it happen again and collect again.

## 3. Tell us

With the files, say:

- which zip or ROM you flashed (the file name), and over what (official
  LineageOS nightly, another ROM, a joan IMS zip before it);
- your phone model (US998, H930, H932, ...) and your carrier and country;
- what you did and what happened, and roughly **what time** (the phone's
  clock), so it can be found in the log;
- whether VoLTE worked on this SIM in another phone.

## 4. Send them privately

**These logs contain your phone number, your SIM's IMSI, the phone's
IMEI and your cell location.** Don't try to blank them out by hand;
they are in too many places. Zip the files (Windows: select them,
right-click > *Send to* > *Compressed folder*; macOS/Linux:
`zip aosp-ims-logs.zip *.txt`) and send the zip to the maintainer
**in a direct message**, not in a public GitHub issue. In a public
issue, describe the problem and say that you have logs.

## If the zip won't install

In LineageOS recovery, after the error: *Advanced* > *Enable ADB*, then
on the computer:

```
adb pull /tmp/recovery.log recovery.log
```

The installer's own messages are at the end of it.

## Reading the logs yourself (optional)

Search `logcat.txt` for these (Windows: `findstr /C:"SIPMSG" logcat.txt`;
macOS/Linux: `grep SIPMSG logcat.txt`):

| Look for | What it tells you |
|---|---|
| `SIPMSG` | One line per SIP message sent (`OUT`) or received (`IN`): `REGISTER`, `401`, `200`, `INVITE`, `180`, `BYE`. No `IN` at all means the network never answered |
| `SocketError` | The phone couldn't reach the carrier's IMS server; the error names why |
| `Reg{FAILURE` (in `ims.txt`) | Each registration attempt that failed, with the stack's reason |
| `OnMediaFailed`, `- Terminate :` | Why the stack ended a call |
| `call-control`, `invokeStartFailed` | A call refused before it left the phone |
| `IWLAN`, `EpdgSelector`, `EpdgTunnelManager` | Wi-Fi calling: finding the carrier's ePDG and building the tunnel to it |
| `avc: denied` | Android's security policy blocked something |
