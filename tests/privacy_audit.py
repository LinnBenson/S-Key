#!/usr/bin/env python3
"""Static guardrail checks. These do not replace Android runtime/privacy review."""
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
android = '{http://schemas.android.com/apk/res/android}'
permissions = {p.get(android+'name') for p in manifest.findall('uses-permission')}
assert permissions <= {'android.permission.RECORD_AUDIO', 'android.permission.VIBRATE', 'android.permission.INTERNET'}
app = manifest.find('application')
assert app.get(android+'allowBackup') == 'false'
assert app.get(android+'fullBackupContent') == 'false'
source = '\n'.join(p.read_text() for p in (root/'app/src/main/java').rglob('*.kt'))
for prohibited in ['setComposingText(', 'setComposingRegion(', 'SpeechRecognizer.', 'MediaRecorder()', 'Log.d(', 'Log.i(', 'Log.e(', 'println(']:
    assert prohibited not in source, prohibited
assert 'debug = false' in source
service = (root/'app/src/main/java/io/secure/keyboard/OfflineKeyboardService.kt').read_text()
assert 'openConnection(' not in service
ai = (root/'app/src/main/java/io/secure/keyboard/ai/AiWriting.kt').read_text()
assert 'instanceFollowRedirects=false' in ai and 'canceled.get()' in ai
assert 'aiCall!==call' in service and 'revision!=translation.revision' in service
assert 'aiSelectionTarget.matches(selected,epoch)' in service
# Network transport stays in API helpers; generation is an explicit action, never a keystroke side effect.
for handler in ['private fun commitInputText', 'private fun applyBackspace']:
    section = service.split(handler, 1)[1].split('\n    private fun ', 1)[0]
    assert 'requestAi(' not in section and 'GrokModelCatalog' not in section
api = (root/'app/src/main/java/io/secure/keyboard/data/ApiConfiguration.kt').read_text()
assert 'AES/GCM/NoPadding' in api and 'AndroidKeyStore' in api
assert 'instanceFollowRedirects = false' in api
assert app.get(android+'usesCleartextTraffic') == 'false'
assert 'commit(code)' not in service
assert 'exactWubi(code' in service
ai_voice = (root/'app/src/main/java/io/secure/keyboard/voice/AiVoiceSession.kt').read_text()
assert 'pcm.fill(0)' in ai_voice and 'instanceFollowRedirects=false' in ai_voice
assert 'FileOutputStream' not in ai_voice and 'check(current(id))' in ai_voice
voice = (root/'app/src/main/java/io/secure/keyboard/voice/OfflineVoiceSession.kt').read_text()
guard = (root/'app/src/main/java/io/secure/keyboard/voice/VoiceCommitGuard.kt').read_text()
assert 'enableEndpoint = true' in voice and 'engine.isEndpoint(input)' in voice
assert 'engine.reset(input)' in voice and 'listener.onSegment(finalizedId, result)' in voice
assert 'generation.get() == id' in voice and 'generation.incrementAndGet()' in voice
assert 'voiceTargetIsCurrent(request, editor, connection)' in service
assert 'voiceGuard !== guard' in service and 'guard.reserve(' in service
assert 'currentInputConnection === connection' in service and 'voiceSegments.clear()' in service
assert 'segmentId <= lastSegmentId' in guard and 'selectionStart != expectedCursor' in guard
# Scope the dictation callback; AI command preview is a separate listener.
listener = service.split('private fun voiceListener()', 1)[1].split('private fun failVoice', 1)[0]
preview = listener.split('override fun onPreview(text: String, isFinal: Boolean)', 1)[1].split('override fun onSegment', 1)[0]
assert 'commit' not in preview and 'queueVoiceSegment' not in preview
assert '确认插入' not in service and 'button("开始")' not in service
print('PASS: manifest/privacy, keyboard-only preedit, no input logs/platform recognizer, endpoint-only deduplicated voice with editor/session/cursor guards')

translation = (root/'app/src/main/java/io/secure/keyboard/translation/GoogleTranslation.kt').read_text()
assert 'checkActive()' in translation and 'payload.fill(0)' in translation
assert 'translationWorker.remove(it)' in service
assert 'TranslationSession { stopTranslationRequest() }' in service
print('PASS: canceled translation and AI tasks leave queue; translation invalidation cancels transport')
