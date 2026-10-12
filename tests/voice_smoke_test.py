"""Host smoke test for the exact imported model (pip install sherpa-onnx==1.13.8 soundfile).

Usage: python tests/voice_smoke_test.py MODEL_DIR OFFICIAL_TEST_WAV
This checks shared runtime/model behavior, not Android microphone/UI behavior.
"""
import hashlib
import pathlib
import sys
import numpy as np
import sherpa_onnx
import soundfile as sf

folder = pathlib.Path(sys.argv[1])
for filename, size, sha in (
    ("model.int8.onnx", 26342340, "68c9c943840f7d9cf3e8a4970ba50f404feb5277f611fa82b7e72267786fa84a"),
    ("tokens.txt", 13366, "6fed8c6c248516f38e7faa19404b57413e8ce259f1cbc1fa4aebc86eac32fdfd"),
):
    content = (folder / filename).read_bytes()
    assert len(content) == size and hashlib.sha256(content).hexdigest() == sha, filename

recognizer = sherpa_onnx.OnlineRecognizer.from_zipformer2_ctc(
    tokens=str(folder / "tokens.txt"), model=str(folder / "model.int8.onnx"),
    num_threads=2, sample_rate=16000, feature_dim=80,
    enable_endpoint_detection=False, decoding_method="greedy_search", provider="cpu", debug=False,
)
audio, rate = sf.read(sys.argv[2], dtype="float32")
assert rate == 16000 and audio.ndim == 1
stream = recognizer.create_stream()
for start in range(0, len(audio), 1600):
    stream.accept_waveform(rate, audio[start:start + 1600])
    while recognizer.is_ready(stream):
        recognizer.decode_stream(stream)
stream.accept_waveform(rate, np.zeros(8000, dtype="float32"))
stream.input_finished()
while recognizer.is_ready(stream):
    recognizer.decode_stream(stream)
text = recognizer.get_result(stream)
assert text.strip(), "Expected nonempty speech transcription"
print(text)
# Fresh, all-silence session cannot reuse previous speech/result state.
silent = recognizer.create_stream()
silent.accept_waveform(16000, np.zeros(32000, dtype="float32"))
silent.input_finished()
while recognizer.is_ready(silent):
    recognizer.decode_stream(silent)
assert not recognizer.get_result(silent).strip(), "Silence emitted stale text"
print("PASS: verified files, Mandarin transcription, fresh silence session")
