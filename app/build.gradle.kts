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
        versionCode = 3
        versionName = "1.0.1"
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
            // Test builds install next to the real app (different name and id).
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Jonaki AI Dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // Real phones are 64-bit ARM; the x86_64 engine is only for the computer's emulator.
            ndk { abiFilters += "arm64-v8a" }
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

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (Android's copy is only a stub there).
    testImplementation("org.json:json:20240303")
}
