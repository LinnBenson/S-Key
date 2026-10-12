plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    namespace = "io.secure.keyboard"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.secure.keyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.1.12"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = false } }
    packaging { jniLibs { useLegacyPackaging = true } }
    // Read-only file mapping avoids decoding the entire Wubi lexicon into the Java heap.
    androidResources { noCompress += "skwb" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies { implementation(files("libs/sherpa-onnx-1.13.8.aar")); testImplementation("junit:junit:4.13.2") }
