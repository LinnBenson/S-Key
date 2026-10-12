# Packaged JVM runtime and build-wrapper notices

## APK runtime scope

The application packages Kotlin Standard Library **2.0.21** and its
`org.jetbrains:annotations:13.0` dependency. Both pinned Maven POMs declare
Apache License 2.0. Their binary JARs contain no standalone license or copyright
files, so explicit readable copies are packaged under
[`app/src/main/assets/runtime-licenses`](../app/src/main/assets/runtime-licenses/).
That directory is also retained in the source archive.

- [Kotlin license](../app/src/main/assets/runtime-licenses/KOTLIN-LICENSE.txt)
  and [original copyright notice](../app/src/main/assets/runtime-licenses/KOTLIN-COPYRIGHT.txt)
- [Runtime attributions](../app/src/main/assets/runtime-licenses/NOTICE.txt), including
  Guava-derived unsigned arithmetic and map capacity, GWT-derived collection base
  classes, and Boost-derived inverse hyperbolic functions
- Unchanged upstream [Guava Apache license](../app/src/main/assets/runtime-licenses/KOTLIN-GUAVA-LICENSE.txt),
  [GWT Apache license](../app/src/main/assets/runtime-licenses/KOTLIN-GWT-LICENSE.txt),
  and [Boost license](../app/src/main/assets/runtime-licenses/KOTLIN-BOOST-LICENSE.txt)
- [Annotations original copyright headers](../app/src/main/assets/runtime-licenses/JETBRAINS-ANNOTATIONS-COPYRIGHT.txt),
  including Sascha Weinreuter's attribution in addition to JetBrains
- [Pinned provenance and notice hashes](../app/src/main/assets/runtime-licenses/PROVENANCE.txt)

The matching Kotlin 2.0.21 sources JAR was examined, not the compiler's dependency
list. `UnsignedKt`, `MathKt__MathJVMKt`, `MapsKt__MapsJVMKt`, `AbstractList`,
`AbstractMap`, and the `org.jetbrains.annotations` / `org.intellij.lang.annotations`
classes were confirmed in the existing ARM64 APK's DEX class definitions. This
build does not shrink the standard library.

ThreeTen is **not included as a runtime notice**: no ThreeTen-derived code or
attribution was found in the exact 2.0.21 JVM stdlib sources JAR, and the release's
license inventory and third-party-license directory contain no ThreeTen entry.
Do not infer newer Kotlin, Kotlin/Native, JavaScript, Wasm, compiler, test, or
Gradle-distribution dependency licenses apply to this JVM runtime. Re-audit this
scope when upgrading dependencies. No upstream runtime source files were modified;
Android tooling converts their JVM bytecode to DEX.

## Official provenance

- [Kotlin 2.0.21 license inventory](https://github.com/JetBrains/kotlin/blob/v2.0.21/license/README.md)
- [Kotlin 2.0.21 third-party license directory](https://github.com/JetBrains/kotlin/tree/v2.0.21/license/third_party)
- [Kotlin stdlib 2.0.21 POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom)
- [Kotlin stdlib 2.0.21 sources](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21-sources.jar)
- [Annotations 13.0 POM](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.pom)
- [Annotations 13.0 sources](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0-sources.jar)
- [Guava map-capacity source referenced by Kotlin](https://github.com/google/guava/blob/v28.2/guava/src/com/google/common/collect/Maps.java)

Audited cached binary SHA-256 values:

- `kotlin-stdlib-2.0.21.jar`: `f31cc53f105a7e48c093683bbd5437561d1233920513774b470805641bedbc09`
- `annotations-13.0.jar`: `ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478`

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
