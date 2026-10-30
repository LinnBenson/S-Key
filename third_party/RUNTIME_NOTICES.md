# Runtime notices

Original S-Key code is licensed under Apache-2.0; see the root LICENSE and NOTICE.
Third-party materials retain their own licenses.

The release resolves Kotlin stdlib 2.2.10 and JetBrains annotations 23.0.0.
The build generates `assets/runtime-licenses/DEPENDENCIES.txt` from the resolved
release runtime artifacts, including AndroidX, Compose, Miuix 0.5.1, OkHttp 4.12.0,
Okio 3.6.0, coroutines and JSpecify. This avoids confusing requested versions with
resolved versions. The local sherpa-onnx AAR is 1.13.8. Its JNI library also includes unused
TTS, Piper phonemize (MIT) and eSpeak NG/ucd-tools (GPL-3.0-or-later) code;
excluding the standalone C/C++ API libraries does not remove those components.
kaldi-native-fbank also includes KissFFT (BSD-3-Clause). See
[the native component notice](../app/src/main/assets/voice-licenses/NATIVE_COMPONENTS_NOTICE.txt)
for pinned source versions and the accompanying complete license texts. The
GPL corresponding-source and redistribution requirements still apply; merely
adding attribution does not satisfy all binary distribution requirements.

Full license and copyright texts are under `app/src/main/assets/`; the application
license page recursively displays all packaged LICENSE, NOTICE and COPYRIGHT files.
Kotlin license files were fetched from the official v2.2.10 tag. Annotations headers
were extracted from the 23.0.0 sources JAR, retaining their original wording (with
normalized line endings). Source URLs and hashes are in runtime-licenses/PROVENANCE.txt.

The existing Guava, GWT and Boost attributions are retained. Font, dictionary,
OpenCC, emoji, CC-CEDICT, sherpa-onnx and ONNX Runtime notices remain separate;
the root Apache license does not override these data licenses.

## Gradle 8.9 wrapper: source distribution only

The source archive includes `gradlew`, `gradlew.bat`, and
`gradle/wrapper/gradle-wrapper.jar`. These are build tools and are not APK runtime
components. The wrapper JAR contains Gradle wrapper, CLI, and supporting Gradle
classes plus an embedded `META-INF/LICENSE`; the unchanged embedded license is
also supplied as [GRADLE_WRAPPER_LICENSE.txt](GRADLE_WRAPPER_LICENSE.txt).
The original script copyright and Apache-2.0 headers remain in place.

- Wrapper main attribution: Copyright 2007 the original author or authors.
- Shell wrapper attribution: Copyright © 2015-2021 the original authors.
- [Gradle 8.9 wrapper main source and original header](https://github.com/gradle/gradle/blob/v8.9.0/platforms/core-runtime/wrapper-main/src/main/java/org/gradle/wrapper/GradleWrapperMain.java)
- [Gradle 8.9 source](https://github.com/gradle/gradle/tree/v8.9.0)
- Wrapper JAR SHA-256: `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`

The entire Gradle distribution and its Groovy, JUnit, SLF4J, and other bundled
libraries are not redistributed in the source archive or APK. Therefore their
unrelated distribution-wide notices are not copied into the app. The downloaded
Gradle distribution retains its own LICENSE and NOTICE files in the build
environment. Font, dictionary, emoji, and voice/native-library notices are
maintained separately; this file supplements rather than replaces them.
