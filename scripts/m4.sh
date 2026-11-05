#!/usr/bin/env bash
# Run a command in a copy of this repo on the M4 (heavy builds: Zig/Ghostty, cargo-ndk, Gradle).
#   scripts/m4.sh cargo test -p rexc-term
#   PULL=android/app/build/outputs scripts/m4.sh ./gradlew :app:assembleDebug
set -euo pipefail
cd "$(dirname "$0")/.."
HOST=${M4_HOST:-m4}
REMOTE=${M4_DIR:-'~/work/rexc'}
rsync -az --delete \
  --exclude target --exclude .git --exclude '*/build/' --exclude .gradle --exclude '.idea' \
  --exclude 'android/app/src/main/jniLibs' \
  ./ "$HOST:$REMOTE/"
# shellcheck disable=SC2029
ssh "$HOST" "export PATH=\$HOME/.cargo/bin:/opt/homebrew/bin:\$PATH ANDROID_HOME=\$HOME/Library/Android/sdk ANDROID_NDK_HOME=\$HOME/Library/Android/sdk/ndk/28.2.13676358 JAVA_HOME=\$(/usr/libexec/java_home -v 21 2>/dev/null || true); cd $REMOTE && $*"
if [ -n "${PULL:-}" ]; then
  mkdir -p "$PULL"
  rsync -az "$HOST:$REMOTE/$PULL/" "$PULL/"
fi
