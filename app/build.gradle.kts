plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    namespace = "io.secure.keyboard"
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    compileSdk = 35
    defaultConfig {
        applicationId = "io.secure.keyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 105
        versionName = "1.0.98"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = true } }
    packaging { jniLibs { useLegacyPackaging = true } }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("top.yukonga.miuix.kmp:miuix-android:0.5.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.8.2")
    implementation("androidx.compose.foundation:foundation:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation(files("libs/sherpa-onnx-1.13.8.aar")); testImplementation("junit:junit:4.13.2") }


// Reproducible default source catalog and immutable indexes; no generated databases in Git.
val buildDictionaryCatalog by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/build_dictionary_catalog.py")
    inputs.file(rootProject.file("scripts/build_dictionary_catalog.py"))
    inputs.dir(rootProject.file("third_party/open_dictionaries"))
    inputs.files(file("src/main/assets/dictionaries/skey_wubi86_high.tsv"), file("src/main/assets/dictionaries/skey_pinyin_high.tsv"), file("src/main/assets/dictionaries/skey_english_high.tsv"))
    outputs.dir(layout.buildDirectory.dir("generated/dictionaryCatalog"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/dictionaryCatalog"))
tasks.named("preBuild").configure { dependsOn(buildDictionaryCatalog) }
