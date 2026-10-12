#!/usr/bin/env bash
# Use only a disposable developer emulator/test device. No fixture goes in the APK.
set -euo pipefail
MODEL_DIR=${1:?Usage: push_voice_fixtures.sh MODEL_DIR WAV_FILE}
WAV=${2:?Usage: push_voice_fixtures.sh MODEL_DIR WAV_FILE}
ADB=${ADB:-adb}
PACKAGE=io.secure.keyboard
"$ADB" shell run-as "$PACKAGE" mkdir -p files/voice-fixtures
for name in model.int8.onnx tokens.txt; do
  "$ADB" shell -T "run-as $PACKAGE sh -c 'cat > files/voice-fixtures/$name'" < "$MODEL_DIR/$name"
done
"$ADB" shell -T "run-as $PACKAGE sh -c 'cat > files/voice-fixtures/0.wav'" < "$WAV"
"$ADB" shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO
printf '%s\n' 'Fixtures ready. Run:'
printf '%s\n' 'adb shell am instrument -w -r -e class io.secure.keyboard.voice.OfflineVoiceInstrumentationTest io.secure.keyboard.test/android.test.InstrumentationTestRunner'
