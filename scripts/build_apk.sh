#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/verify_static.py
python3 -m unittest discover -s gateway/tests -v
python3 scripts/preflight.py
if [[ -f gradle/wrapper/gradle-wrapper.jar ]]; then
  chmod +x gradlew
  GRADLE=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  python3 scripts/fetch_wrapper.py
  chmod +x gradlew
  GRADLE=(./gradlew)
fi
"${GRADLE[@]}" --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
printf '\nAPK: %s\n' "$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
