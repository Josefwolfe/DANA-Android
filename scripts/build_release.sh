#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for n in DANA_KEYSTORE_PATH DANA_KEYSTORE_PASSWORD DANA_KEY_ALIAS DANA_KEY_PASSWORD; do
  if [[ -z "${!n:-}" ]]; then echo "Missing required environment variable: $n" >&2; exit 2; fi
done
[[ -f "$DANA_KEYSTORE_PATH" ]] || { echo "Keystore not found: $DANA_KEYSTORE_PATH" >&2; exit 2; }
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
"${GRADLE[@]}" --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
APK="$(pwd)/app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || { echo "Release APK was not produced" >&2; exit 3; }
sha256sum "$APK" > "$APK.sha256"
printf '\nSIGNED RELEASE APK: %s\n' "$APK"
