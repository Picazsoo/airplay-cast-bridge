# Third-party software

The license in `LICENSE` covers this repository's own code (the Android app in `src/`, the shim
in `jni/airshim.c`, the build scripts and resources). The APK and the `lib/` and `libs/` folders also contain
software by others, redistributed unchanged:

## AirConnect (aircast)

`lib/<abi>/libaircast.so` are the static `aircast` binaries from
[AirConnect](https://github.com/philippe44/AirConnect) release 1.12.4 by Philippe
(philippe44), renamed so Android installs them as native libraries:

| File | Release asset |
|---|---|
| `lib/armeabi-v7a/libaircast.so` | `aircast-linux-arm-static` |
| `lib/arm64-v8a/libaircast.so` | `aircast-linux-aarch64-static` |
| `lib/x86_64/libaircast.so` | `aircast-linux-x86_64-static` |

AirConnect is under the MIT license (https://mit-license.org), (c) Philippe. As its README says,
it uses third-party software that is licensed by their authors under their own conditions
(among them OpenSSL, libFLAC, FAAC, ALAC and the GNU C Library, linked statically into these
binaries; FAAC and the GNU C Library are under the LGPL). The source of AirConnect and of those components
is available from the AirConnect repository and its submodules at
https://github.com/philippe44/AirConnect.

## Shizuku API

`libs/shizuku-{api,provider,aidl,shared}-13.1.5.jar` are the `classes.jar` files of the
`dev.rikka.shizuku` 13.1.5 libraries from Maven Central, the client API of
[Shizuku](https://github.com/RikkaApps/Shizuku-API), (c) RikkaApps, under the MIT license.
They are used only when the Shizuku app is installed and running.

This project is not affiliated with or endorsed by AirConnect's author, Apple or Google.
AirPlay is a trademark of Apple Inc. Chromecast and Google Cast are trademarks of Google LLC.
