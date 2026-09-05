plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pagereader.android"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.pagereader.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Physical phones only. OpenCV ships a full native build per ABI, so the
        // emulator-only x86/x86_64 slices were ~105 MB of an APK no handset can
        // execute. Delete this block to build for the emulator again.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    androidResources {
        // Leave the language data uncompressed. AAPT would otherwise deflate
        // it, which costs an inflate on every install for no size win (it is
        // already a compact binary model) and, more to the point, makes
        // AssetManager.openFd fail on it -- "probably compressed".
        noCompress += "traineddata"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // LiteRT removed: nothing in the app imports it, and it cost ~22 MB of
    // TensorFlow Lite natives. Restore these two lines if ML is added later.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("org.opencv:opencv:4.9.0")
    // OCR. The -openmp variant is faster on multi-core with a single engine
    // instance, which is exactly our usage (TessBaseAPI is not thread-safe, so
    // there is one instance on one background thread). Its only transitive
    // dependency is androidx.annotation -- no GMS, which the target phone
    // does not have. The AAR is 12.8 MB across all ABIs, roughly halved by
    // the abiFilters above.
    implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}