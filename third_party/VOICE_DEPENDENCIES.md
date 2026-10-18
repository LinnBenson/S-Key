# Offline Mandarin voice dependencies

## Runtime
- sherpa-onnx 1.13.8 official Android AAR (Apache-2.0)
- Source: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8
- Binary: https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
- SHA-256: `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`
- AAR includes arm64-v8a, armeabi-v7a, x86, x86_64 JNI libraries and ONNX Runtime 1.28.2 (MIT; version inspected from shipped binary). Include accompanying license notices when redistributing.
- Kotlin API compiled inside the AAR; no copied/reimplemented JNI declarations.

## User-imported model
`csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01`

Official instructions:
https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-ctc/zipformer-ctc-models.html

Model repository pinned revision: `a5f60fe00dcfbaf68fcc1c6b5cf53061e144d6da`
https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/tree/a5f60fe00dcfbaf68fcc1c6b5cf53061e144d6da

- model.int8.onnx: 26,342,340 bytes, SHA-256 `68c9c943840f7d9cf3e8a4970ba50f404feb5277f611fa82b7e72267786fa84a`
- tokens.txt: 13,366 bytes, SHA-256 `6fed8c6c248516f38e7faa19404b57413e8ce259f1cbc1fa4aebc86eac32fdfd`
- Mandarin-only streaming CTC; 16 kHz mono, 80-dimensional features, CPU, greedy decoding.
- Training-model card declares Apache-2.0: https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01 . The converted ONNX card does not repeat an explicit license declaration. Model weights are not bundled in this application; users obtain them from the upstream distribution and import the exact audited version.
- Hashes are enforced before native loading, not just filenames. Imports use bounded copies, no archive extraction, no persistent external-storage grant, and app-private no-backup storage.

## Privacy/lifecycle
This section describes the offline voice path only. It does not use Android SpeechRecognizer, remote inference, analytics, audio files, history, or transcript logs. The application now declares INTERNET for optional translation, AI writing and AI voice; see README.md for the complete network scope. AudioRecord PCM goes to RAM buffers, then native streaming features. PCM buffers are zero-filled after consumption; native stream and recognizer are released after stop/cancel. Interim results remain in volatile UI memory. Endpoint-finalized segments insert directly once into the captured active editor after session and cursor validation; cancellation drops uncommitted segments but does not retract inserted text. The application cannot control storage by the destination editor after explicit commit. Activity setup opens the user's external browser only after a download-button tap; that browser has its own network/privacy behavior.

Sessions are limited to 60 seconds. Runtime permission is requested in setup. Owners must cancel the voice session on editor changes, keyboard hide, and service destruction; The toolbar starts listening directly once setup is complete; no confirmation button is required by the current user-selected workflow. Endpoint rules use 2.4 seconds of silence, 1.4 seconds of trailing silence after speech, or a 20-second segment limit; the total session remains capped at 60 seconds.
