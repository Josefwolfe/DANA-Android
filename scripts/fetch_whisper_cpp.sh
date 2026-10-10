#!/usr/bin/env bash
set -euo pipefail
DEST="app/src/main/cpp/whisper.cpp"
if [[ -f "$DEST/CMakeLists.txt" ]]; then
  echo "Pinned whisper.cpp already present"
  exit 0
fi
mkdir -p "$(dirname "$DEST")"
git clone --depth 1 --branch v1.8.4 https://github.com/ggml-org/whisper.cpp.git "$DEST"
test -f "$DEST/include/whisper.h"
# The release tag is pinned; do not silently build from a moving master branch.
git -C "$DEST" describe --tags --exact-match | grep -Fx 'v1.8.4'
echo 'Official whisper.cpp v1.8.4 ready.'
