#!/bin/bash
# Builds airshim for every ABI and copies in the matching static aircast binaries.
# AIRCONNECT_DIR: folder with the files unpacked from AirConnect-<ver>.zip (only needed when
# updating aircast; the binaries are kept in lib/ in git).
set -euo pipefail
cd "$(dirname "$0")"
. ./sdk-env.sh
[ -n "$NDK_BIN" ] || { echo "NDK not found; set NDK=/path/to/ndk" >&2; exit 1; }
for abi in armeabi-v7a arm64-v8a x86_64; do
  case $abi in
    armeabi-v7a) triple=armv7a-linux-androideabi28; aircast=aircast-linux-arm-static ;;
    arm64-v8a)   triple=aarch64-linux-android28;     aircast=aircast-linux-aarch64-static ;;
    x86_64)      triple=x86_64-linux-android28;      aircast=aircast-linux-x86_64-static ;;
  esac
  mkdir -p lib/$abi
  tool "$NDK_BIN/$triple-clang" -O2 -s -Wall -o lib/$abi/libairshim.so jni/airshim.c
  if [ -n "${AIRCONNECT_DIR:-}" ]; then cp "$AIRCONNECT_DIR/$aircast" lib/$abi/libaircast.so; fi
  echo "$abi: $(ls lib/$abi | tr '\n' ' ')"
done
