// Adapted from llama.cpp examples/llama.android/lib/build.gradle.kts (MIT, Copyright (c) 2023-2026
// The ggml authors; Android example by Arm).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Versions come from the root buildscript classpath (AGP 8.13.2, Kotlin 2.3.0).
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vanskis.liquidpocket.engine"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The CPU backend variants (libggml-cpu-*.so) must exist as files in nativeLibraryDir so that
    // ggml_backend_load_all_from_path() finds them. The app module needs the same flag.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
