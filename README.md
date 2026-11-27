# AirPlay Bridge for Android TV

AirPlay Bridge lets iPhones, iPads and Macs play audio to your Chromecast speakers.

- **What it does:** it runs [philippe44's AirConnect](https://github.com/philippe44/AirConnect) `aircast` on an Android TV or Google TV box.
- **What you see:** every Chromecast Audio, Cast-enabled speaker, Nest speaker and Cast speaker group on your network shows up as an AirPlay speaker with the same name.
- **No root and no server:** it is an ordinary app. It starts with the box and keeps running while the TV is in standby.

## Requirements

- An Android TV or Google TV device with Android 9 or newer, connected to the same network as the Cast speakers.
- A device type the app has a build for:
  - 32-bit ARM, for example the onn 4K Google TV box, where it is tested
  - 64-bit ARM, which is built but not yet tested on a device
  - x86_64, for example the Android emulator
- iPhones and Macs on the same network, with the same multicast/mDNS reachability as for normal AirPlay.

## Install

No PC is needed.

1. **Get the APK.** Download `airplay-bridge-<version>.apk` from the Releases page, or build it yourself (see Build).
2. **Install it on the TV.** Any sideloading method works, for example the Downloader app, "Send Files to TV", or a USB stick and a file manager. Android asks once to allow installs from that app.
3. **Open it once.** Open *AirPlay Bridge* from the TV's app list. After that it starts by itself after every reboot.
4. **Turn off battery optimisation.** Press *Battery optimisation* in the app until it says "off (good)". This keeps the bridge running while the TV is in standby. Most Google TV builds have no settings screen for it, so the app does it through Shizuku (see below) or with adb from a PC.

### Battery optimisation with Shizuku

[Shizuku](https://shizuku.rikka.app/) lets apps run the few commands that normally need adb from a PC. On Android TV and Google TV, use the **[Shizuku fork by thedjchi](https://github.com/thedjchi/Shizuku/releases)**: it works with a D-pad remote, can pair on Android 14+ TVs, and can start itself at boot. The original Shizuku app is hard to use with a remote.

1. Install the fork's APK on the TV. The app shows a QR code with the download link.
2. Open Shizuku and start it with *Wireless debugging*, following its instructions. This needs Developer options on the TV, but no PC.
3. In AirPlay Bridge, press *Battery optimisation* and allow AirPlay Bridge in the Shizuku prompt.

The app then runs `dumpsys deviceidle whitelist +io.github.picazsoo.airplaybridge` and allows it to run in the background. This survives reboots, so Shizuku is only needed once. It doesn't have to keep running afterwards. If AirPlay Bridge is already allowed in Shizuku, it fixes this by itself when it starts.

### With adb from a PC

```bash
adb connect <tv-ip>:5555
adb install -r airplay-bridge-<version>.apk
adb shell am start -n io.github.picazsoo.airplaybridge/.MainActivity        # first start, then boot starts it
adb shell dumpsys deviceidle whitelist +io.github.picazsoo.airplaybridge   # battery optimisation off
```

## Using it

- **From an iPhone or iPad:** open Control Center, tap the AirPlay icon (or the AirPlay button in a music app) and pick a speaker.
- **From a Mac:** in the menu bar, open Sound or Control Center and choose the speaker as the output.
- **Several people at once:** they can play to different speakers at the same time. Picking a speaker someone else is using takes it over. A group and a speaker inside it can't play at once, which is a Chromecast limit.
- **Volume:** the phone's volume controls the Chromecast's volume.
- **Delay:** expect about 1 to 2 seconds, as with normal AirPlay to speakers.

Nothing needs to stay open on the TV.

## The app screen

| Left column | |
|---|---|
| Start / Stop bridge | Stops or starts the bridge. Stopped stays stopped across reboots. |
| Restart bridge | Restarts aircast, which interrupts anything playing. |
| Names | Plain names (the default) or aircast's style with a "+" suffix. |
| Codec to Chromecast | FLAC (lossless, the default), WAV, MP3 or AAC. Try MP3 or AAC if Wi-Fi is weak. |
| Buffer / delay | Default, 0.5 s, 1 s, 2 s, or 1 s with silence filling gaps. A longer buffer helps with dropouts. |
| Volume scale | How aircast maps AirPlay volume to the Chromecast media volume (default 50 %). Raise it if AirPlay is too quiet. |
| Timing | Corrected (the default) or "let drift", which avoids occasional clicks. |
| Song info to Chromecast | Sends title, artist and artwork (on by default). |
| On stop | Leave the Cast app open (the default) or close it when AirPlay stops. |
| Web settings | Switches the web settings page on or off, and shows its address and a QR code. |
| Web PIN | Shows the web page's PIN. Pressing it offers *New PIN* and *Turn PIN off* (or *on*). |
| Battery optimisation | Shows whether Android may put the app to sleep. It should say "off (good)". If not, pressing it fixes it through Shizuku, or explains how. |

Each settings button cycles to the next value when pressed.

The right side of the screen has:

- **Status:** whether the bridge is running or restarting, the uptime, the box's IP, how many times aircast was restarted, and how many AirPlay sessions are open.
- **Speakers shown on iPhone/Mac:** one checkbox for each Cast device found on the network.
  - Unchecked speakers are hidden from AirPlay.
  - "✓ offered" means the speaker is currently available. "(not online)" means it is off or unreachable.
  - The TV box's own Cast receiver is hidden by default: casting to it opens a full-screen Cast player on the TV and stops whatever is playing. Turn it on if the box feeds your sound system, for example a soundbar or AV receiver over HDMI. The choice sticks.
  - **Rename** sets the name shown on iPhones and Macs. Leave it empty or press Reset to use the Cast name.
- **Log:** the latest lines from the bridge and aircast, newest first.

**Applying changes:** a change takes effect a few seconds after the last one, with a bridge restart. While an AirPlay session is open, the restart waits until it ends, so playback is not cut off. The status line says when changes are waiting. The Start/Stop and Restart buttons act immediately.

## Web settings page

The same settings can be changed from any browser on your network, at `http://<tv-ip>:8099`. The page is off by default: the app asks once on first start whether to turn it on, and the *Web settings* button switches it later. While it is on, the TV screen shows the address and a QR code to scan with a phone.

The page has:

- **Settings and Speakers:** the same options as on the TV, as drop-downs, checkboxes and name fields, saved with one button.
- **config.xml:** the config aircast runs with.
  - Edit it in place, or load a file from your computer.
  - *Use this config.xml* runs aircast with it exactly as written. That covers options the app does not expose, such as per-speaker codecs; see [AirConnect's README](https://github.com/philippe44/AirConnect) for them.
  - While a custom config is in use, the app's own settings are not applied. *Go back to app-managed config* undoes it.
  - *Download* saves the current file.
- **Status and log**, refreshed every 3 seconds, and a *Restart now* button.

Saved changes apply the same way as on the TV: a few seconds later, and only after playback ends.

### PIN

The page needs a PIN by default.

- A random 6-digit PIN is created the first time the app starts and is shown on the TV screen.
- The browser asks for a user name, which can be anything, and a password, which is the PIN.
- The PIN stays the same across restarts, reboots and updates. It changes only when you press *Web PIN* on the TV and confirm.
- After five wrong PINs the page refuses logins for 30 seconds.
- *Web PIN* → *Turn PIN off* opens the page to anyone on your network without a password. Turning it back on uses the same PIN as before.

The page uses plain HTTP and is meant for your home network only. Switch it off with *Web settings* on the TV if you don't need it. Never forward port 8099 to the internet.

## How it works

- **Binaries for each architecture.** The APK carries the static aircast build from AirConnect 1.12.4 for three ABIs, as `lib/<abi>/libaircast.so`, each with its own `libairshim.so`. Android installs the pair that fits the device.

  | ABI | aircast binary | Tested on |
  |---|---|---|
  | `armeabi-v7a` (32-bit ARM) | `aircast-linux-arm-static` | onn 4K Google TV box (Android 14, 32-bit userspace) |
  | `arm64-v8a` (64-bit ARM) | `aircast-linux-aarch64-static` | not yet |
  | `x86_64` | `aircast-linux-x86_64-static` | Android 16 emulator |

  They ship as native libraries so that Android extracts them to `nativeLibraryDir`, the only app location a binary may be run from (`extractNativeLibs="true"`).
- **BridgeService.** It is a foreground service (`connectedDevice`) holding a partial wake lock, a low-latency Wi-Fi lock and a multicast lock.
  - It writes `config.xml` and runs aircast as a child process.
  - It restarts aircast with backoff if it exits, and when the IP address changes.
  - START_STICKY brings the service back if the app process dies. `BootReceiver` starts it at boot and after an update.
- **Own receiver hidden.** Google TV boxes are Cast receivers too. The service finds the box's own Cast id over mDNS (the `_googlecast._tcp` entry with the box's own IP) and disables it in aircast's config.
- **Visible or hidden per speaker.** The app remembers every Cast device it has seen on the network. Unchecked ones are written to `config.xml` as `<device><udn>…</udn><enabled>0</enabled></device>`.
- **airshim** (`jni/airshim.c`, built with the NDK into `libairshim.so`).
  - **The problem:** Android's app seccomp filter only allows the syscalls that bionic uses. It kills glibc programs with SIGSYS when they make legacy calls (`set_robust_list`, `pause`, `accept`, `send`, `recv`, ...).
  - **The fix:** the shim runs aircast under ptrace. On a seccomp SIGSYS it re-issues the call as its modern equivalent (`accept4`, `sendto`, `recvfrom`, `ppoll`, ...) and restores the caller's registers when the syscall exits. A call with no equivalent gets ENOSYS.
  - **Cost:** only trapped calls stop the tracer, so it uses about 0 % CPU.
  - **Architectures:** the shim has a small register layer for arm, aarch64 and x86_64, and a rewrite table for each. arm32 and x86_64 have many legacy calls; on x86_64 the path calls such as `open`, `stat` and `access` are also rewritten to their `*at` forms. aarch64 only has the generic syscall table, so only `accept` is rewritten there.
- **Names** are shown without aircast's usual "+" suffix. This can be switched in the app.

## Build

The build uses plain aapt, javac and d8, with no Gradle. It runs on Linux, macOS and Windows,
and needs:

- the Android SDK, with build-tools (34 or newer) and platform 35
- the NDK, only to rebuild the shim; prebuilt shims are in `lib/`
- a JDK 11 or newer, with `javac` and `keytool` on PATH
- bash; on Windows, Git Bash works

`sdk-env.sh` finds the SDK in `ANDROID_HOME` or `ANDROID_SDK_ROOT`, or else in Android Studio's
default place (`~/Android/Sdk` on Linux, `~/Library/Android/sdk` on macOS,
`%LOCALAPPDATA%\Android\Sdk` on Windows). It uses the newest build-tools and NDK found there.
`BUILD_TOOLS`, `ANDROID_JAR` and `NDK` override those choices.

```bash
bash build-native.sh        # airshim for all ABIs (NDK=... to override the NDK path)
# when updating aircast: AIRCONNECT_DIR=<unzipped AirConnect release> bash build-native.sh
bash build.sh               # VERSION_NAME=x VERSION_CODE=n to override
```

The APK is `build/airplay-bridge.apk`.

**Signing:**

- **Debug builds** are signed with `debug.keystore`. It is created on the first build and is not in git.
- **Release builds** are signed with your own key:

  ```bash
  KEYSTORE=release.jks KEY_ALIAS=airplaybridge KS_PASS=... bash build.sh
  ```

  The same lines (`KEYSTORE=...`, `KEY_ALIAS=...`, `KS_PASS=...`) can go in `keystore.properties`, which `build.sh` reads when present. Keystores and `keystore.properties` are gitignored.

- **Updates** must be signed with the same key as the installed app. Otherwise uninstall the app first, which also clears its settings.

## Troubleshooting

- **Logs:** `adb logcat -s AirBridge AirBridgeOut` shows the service and aircast output.
- **No speakers on the iPhone:** check that the TV and the phone are on the same network, and that the router doesn't block multicast between Wi-Fi and Ethernet. Also check that the app screen lists the speakers as "✓ offered".
- **Playback stops when the TV sleeps:** set *Battery optimisation* to off, through Shizuku or with the adb command from Install.
- **Forgot the web PIN:** it is shown on the TV screen in the app.
- **A new aircast release breaks something:** a new `airshim: syscall N blocked by seccomp` line in the log means a syscall the shim does not rewrite yet, so it gets ENOSYS. Look up N in the syscall table for your architecture and add a rewrite in `jni/airshim.c` if it breaks something.
- **Testing from a PC without an iPhone:** pyatv's `atvremote ... stream_file=` works if its `/info` and `/feedback` requests are stubbed out, because aircast answers them with 501 without CSeq.

## Contributing

Feel free to fork the project and send pull requests. Bug reports, device reports ("works on my X") and ideas are welcome as [issues](https://github.com/Picazsoo/airplay-cast-bridge/issues). For a bug, please include the device model, Android TV version and the output of `adb logcat -s AirBridge AirBridgeOut` if you can.

## License

- **This project:** MIT, see [LICENSE](LICENSE).
- **The bundled aircast binaries:** AirConnect's MIT license and its third-party components, see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **Not affiliated** with AirConnect's author, Apple or Google. AirPlay is a trademark of Apple Inc. Chromecast and Google Cast are trademarks of Google LLC.
