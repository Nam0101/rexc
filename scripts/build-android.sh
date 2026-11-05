#!/usr/bin/env bash
# Build the Rust engine for Android and generate the Kotlin bindings.
#   scripts/build-android.sh                 # arm64-v8a, release
#   ABIS="arm64-v8a x86_64" PROFILE=debug scripts/build-android.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ABIS=${ABIS:-"arm64-v8a"}
PROFILE=${PROFILE:-release}
APP=android/app/src/main
T=${CARGO_TARGET_DIR:-target}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)
  export ANDROID_NDK_HOME
fi
export PATH="$HOME/.cargo/bin:$PATH"
flags=()
for abi in $ABIS; do flags+=(-t "$abi"); done
release=()
[ "$PROFILE" = release ] && release=(--release)
cargo ndk "${flags[@]}" --platform 28 -o "$APP/jniLibs" build -p rexc-ffi "${release[@]}"
# Bindings come from the host build's metadata (identical across targets).
cargo build -p rexc-ffi --features bindgen --bin uniffi-bindgen
cargo build -p rexc-ffi
lib=$T/debug/librexc_ffi.dylib
[ -f "$lib" ] || lib=$T/debug/librexc_ffi.so
rm -rf "$APP/java/uniffi"
"$T/debug/uniffi-bindgen" generate --library "$lib" --language kotlin --no-format --out-dir "$APP/java"
echo "Android engine ready: $APP/jniLibs, $APP/java/uniffi"
