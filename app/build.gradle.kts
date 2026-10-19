plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    namespace = "io.secure.keyboard"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.secure.keyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 82
        versionName = "1.0.75"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = true } }
    packaging { jniLibs { useLegacyPackaging = true } }
    // Read-only file mapping avoids decoding the entire Wubi lexicon into the Java heap.
    androidResources { noCompress += "skwb" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation("top.yukonga.miuix.kmp:miuix-android:0.5.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.8.2")
    implementation("androidx.compose.foundation:foundation:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation(files("libs/sherpa-onnx-1.13.8.aar")); testImplementation("junit:junit:4.13.2") }
