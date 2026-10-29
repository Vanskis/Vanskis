// The Android Gradle Plugin and the Compose compiler plugin are put on the root classpath only
// when an Android SDK is present (see settings.gradle.kts), so :core builds without Google's Maven.
buildscript {
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\..*")
            }
        }
        gradlePluginPortal()
    }
    dependencies {
        if (gradle.extra["lp.hasAndroidSdk"] as Boolean) {
            classpath(libs.android.gradle.plugin)
            classpath(libs.compose.compiler.gradle.plugin)
        }
    }
}

plugins {
    // Loads the Kotlin Gradle plugin once for all modules (kotlin.jvm and kotlin.android).
    alias(libs.plugins.kotlin.jvm) apply false
}
