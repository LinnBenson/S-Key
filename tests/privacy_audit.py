#!/usr/bin/env python3
"""Static guardrail checks. These do not replace Android runtime/privacy review."""
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
android = '{http://schemas.android.com/apk/res/android}'
permissions = {p.get(android+'name') for p in manifest.findall('uses-permission')}
assert 'android.permission.INTERNET' not in permissions
assert permissions <= {'android.permission.RECORD_AUDIO', 'android.permission.VIBRATE'}
app = manifest.find('application')
assert app.get(android+'allowBackup') == 'false'
assert app.get(android+'fullBackupContent') == 'false'
source = '\n'.join(p.read_text() for p in (root/'app/src/main/java').rglob('*.kt'))
for prohibited in ['setComposingText(', 'setComposingRegion(', 'SpeechRecognizer.', 'MediaRecorder()', 'Log.d(', 'Log.i(', 'Log.e(', 'println(']:
    assert prohibited not in source, prohibited
assert 'debug = false' in source
service = (root/'app/src/main/java/io/secure/keyboard/OfflineKeyboardService.kt').read_text()
assert 'commit(code)' not in service
assert 'exactWubi(code' in service
voice = (root/'app/src/main/java/io/secure/keyboard/voice/OfflineVoiceSession.kt').read_text()
guard = (root/'app/src/main/java/io/secure/keyboard/voice/VoiceCommitGuard.kt').read_text()
assert 'enableEndpoint = true' in voice and 'engine.isEndpoint(input)' in voice
assert 'engine.reset(input)' in voice and 'listener.onSegment(finalizedId, result)' in voice
assert 'generation.get() == id' in voice and 'generation.incrementAndGet()' in voice
assert 'voiceTargetIsCurrent(request, editor, connection)' in service
assert 'voiceGuard !== guard' in service and 'guard.reserve(' in service
assert 'currentInputConnection === connection' in service and 'voiceSegments.clear()' in service
assert 'segmentId <= lastSegmentId' in guard and 'selectionStart != expectedCursor' in guard
preview = service.split('override fun onPreview(text: String, isFinal: Boolean)', 1)[1].split('override fun onSegment', 1)[0]
assert 'commit' not in preview and 'queueVoiceSegment' not in preview
assert '确认插入' not in service and 'button("开始")' not in service
print('PASS: manifest/privacy, keyboard-only preedit, no input logs/online recognizer, endpoint-only deduplicated voice with editor/session/cursor guards')
