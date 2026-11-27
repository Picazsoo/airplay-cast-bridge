# Changelog

## 1.1.1

First public release. App ID `io.github.picazsoo.airplaybridge`; the versions below were private
test builds.

- The web settings page is off by default. On first start the app asks once whether to turn it
  on; the Web settings button switches it later. Installs that already had it keep it on.

## 1.1.0

- Battery optimisation can be turned off without a PC, through Shizuku. The app points to the
  Android TV fork of Shizuku (thedjchi) with a QR code, asks for Shizuku permission, and then sets
  the Doze whitelist itself. If it is already allowed, it does this automatically at start.
- README: installing without adb comes first.

## 1.0.0

- Web settings page is protected by a PIN by default. A random 6-digit PIN is created on first
  start, shown on the TV screen, and only changes when you pick "Web PIN" → "New PIN" on the TV.
  The PIN can be turned off there. Five wrong PINs lock the page for 30 seconds.
- Release signing via `KEYSTORE`, `KEY_ALIAS`, `KS_PASS` in `build.sh`.
- License, third-party notices and a README for a public release.

## 0.6

- One APK for 32-bit ARM, 64-bit ARM and x86_64 devices (airshim ported to aarch64 and x86_64).

## 0.5

- Web settings page on port 8099 with a config.xml editor, and a QR code with its address on the TV.

## 0.3

- Configurable aircast options (codec, buffer, volume scale, timing, metadata, on-stop) and
  per-speaker AirPlay names. Settings changes wait until nobody is playing.

## 0.2

- Show or hide each Cast device as an AirPlay target. The box's own Cast receiver is hidden by default.

## 0.1

- aircast running as a foreground service on Android TV, started at boot, restarted if it exits,
  with a ptrace shim for Android's seccomp filter.
