# Sourced by build.sh and build-native.sh: finds the Android SDK, build-tools and NDK on
# Windows (Git Bash), macOS and Linux. Override with ANDROID_HOME, BUILD_TOOLS, ANDROID_JAR, NDK.
# Works with bash 3.2 (macOS's default).

if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then ANDROID_HOME=$ANDROID_SDK_ROOT
  elif [ -n "${LOCALAPPDATA:-}" ] && [ -d "$LOCALAPPDATA/Android/Sdk" ]; then ANDROID_HOME=$LOCALAPPDATA/Android/Sdk
  elif [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_HOME=$HOME/Library/Android/sdk
  else ANDROID_HOME=$HOME/Android/Sdk
  fi
fi
SDK=$ANDROID_HOME

newest() { ls -1 "$1" 2>/dev/null | sort -V | tail -n 1; }

BT=${BUILD_TOOLS:-$SDK/build-tools/$(newest "$SDK/build-tools")}
ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}

# Runs an SDK/NDK tool, using its .bat/.cmd/.exe wrapper on Windows.
tool() {
  local base=$1; shift
  for ext in "" .exe .bat .cmd; do
    if [ -f "$base$ext" ]; then "$base$ext" "$@"; return; fi
  done
  echo "not found: $base" >&2; return 127
}

if [ -z "${NDK:-}" ]; then
  if [ -n "${ANDROID_NDK_HOME:-}" ]; then NDK=$ANDROID_NDK_HOME
  else NDK=$SDK/ndk/$(newest "$SDK/ndk")
  fi
fi
NDK_BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -n 1)
