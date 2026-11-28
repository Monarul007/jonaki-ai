import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The release key lives outside the project and is never committed.
val keyProps = Properties().apply {
    val f = file(System.getenv("OFFLINEGEMMA_KEY_PROPS") ?: "${System.getProperty("user.home")}/.android-keys/offlinegemma.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.offlinegemma.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.offlinegemma.app"
        minSdk = 31
        targetSdk = 36
        manifestPlaceholders["appLabel"] = "Jonaki AI"
        versionCode = 20
        versionName = "1.0.18"
    }

    signingConfigs {
        if (keyProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // The sherpa-onnx AAR ships four ABIs (about 190 MB). Debug keeps arm64 for the phone and
            // x86_64 for the emulator; without a filter the APK was 337 MB. Set per build type, because
            // a second `defaultConfig { }` block is silently ignored by AGP.
            ndk {
                abiFilters.clear()
                abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
            }
            if (project.hasProperty("overRelease")) {
                // `-PoverRelease`: a test build that installs over the real app and keeps its data
                // (the downloaded model, chats). Same id and signing key as the release build.
                signingConfig = signingConfigs.findByName("release")
            } else {
                // Test builds install next to the real app (different name and id).
                applicationIdSuffix = ".dev"
                manifestPlaceholders["appLabel"] = "Jonaki AI Dev"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // Real phones are 64-bit ARM; the x86_64 engine is only for the computer's emulator.
            // ASSIGN, not `+=`: appending would keep whatever an earlier block added, which is how
            // 46 MB of emulator libraries got into a release APK (measured: 175.6 MB, not 129).
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging {
        jniLibs { pickFirsts.add("**/liblitertlm_jni.so") }
        // Post-quantum crypto tables pulled in by the PDF reader's encryption support; never used here.
        resources { excludes += "org/bouncycastle/pqc/**" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // litertlm-android 0.17+ ships Kotlin 2.4 metadata; Gallery uses this same flag.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    // On-device speech recognition for Bengali + English. A 2B chat model asked to "write down what
    // this says" Romanises Bengali (measured: 3 of 5 clips came back as "Bangladesh-er koto tomo"),
    // and that text is what the study files are searched with. sherpa-onnx is a real ASR runtime.
    // The AAR is vendored rather than fetched: k2-fsa publishes no Maven coordinate, and pinning the
    // file (SHA-256 4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50) means a
    // release build cannot silently pick up a different runtime.
    implementation(files("${rootProject.projectDir}/third_party/sherpa-onnx-2.0.3.aar"))
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // In-app camera: the phone's camera app is what makes MIUI's camera booster kill us.
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    // Reads the text inside PDFs, on the phone (Apache 2.0).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // On-device OCR (Bengali + English) for pictures when the model's vision is not loaded.
    // ML Kit and PaddleOCR do not support the Bengali script, so Tesseract is used here.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // Latin text (chemical formulas, equations) read by ML Kit's bundled, offline Latin model;
    // Tesseract misreads Latin formulas on a Bengali page ("CuCl2" -> "00002").
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (Android's copy is only a stub there).
    testImplementation("org.json:json:20240303")
}
