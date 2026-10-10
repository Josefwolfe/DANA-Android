#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v kotlinc >/dev/null 2>&1; then
  echo 'برای این آزمون به Kotlin CLI نیاز دارید: kotlinc' >&2
  exit 1
fi
out_dir="$(mktemp -d)"
trap 'rm -rf "$out_dir"' EXIT
kotlinc app/src/main/java/com/dana/ai/CommandEngine.kt app/src/main/java/com/dana/ai/ReminderTimeResolver.kt \
  app/src/main/java/com/dana/ai/WakePhrase.kt \
  app/src/main/java/com/dana/ai/WakeCommandCollector.kt \
  app/src/main/java/com/dana/ai/WakeSpeechProbe.kt \
  app/src/main/java/com/dana/ai/AiDecisionGate.kt \
  app/src/main/java/com/dana/ai/AliasConsentPolicy.kt \
  app/src/main/java/com/dana/ai/SafetyPolicy.kt \
  app/src/main/java/com/dana/ai/WakeEnergyPolicy.kt \
  app/src/main/java/com/dana/ai/WakeModelInstaller.kt \
  tools/CoreSmokeTest.kt tools/FeatureRegression.kt -include-runtime -d "$out_dir/dana-core-tests.jar"
java -jar "$out_dir/dana-core-tests.jar"
