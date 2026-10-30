import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// CI passes the workflow run number; local builds are version 1.
val lpVersionCode = System.getenv("LP_VERSION_CODE")?.toIntOrNull() ?: 1

// Release signing comes from the environment (CI decodes the keystore from repo secrets).
// Without it the release build is signed with the debug key so it still installs.
val releaseKeystorePath: String? = System.getenv("LP_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.vanskis.liquidpocket"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vanskis.liquidpocket"
        minSdk = 33
        targetSdk = 36
        versionCode = lpVersionCode
        versionName = "0.1.$lpVersionCode"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("LP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LP_KEY_ALIAS")
                keyPassword = System.getenv("LP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // The engine ships several CPU-variant backends as .so files and picks one at runtime from
    // nativeLibraryDir, so the libraries must be extracted on install, not mapped from the APK.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Room writes each database version's schema here; commit it, migrations are tested against it.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.markdown.renderer.m3)
    // The calculate tool's isolated JavaScript sandbox, for math.js (assets/mathjs).
    implementation(libs.androidx.javascriptengine)
    implementation(libs.jlatexmath.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Documents: PDF text layer, and on-device OCR for scanned pages (bundled Latin model, offline).
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
