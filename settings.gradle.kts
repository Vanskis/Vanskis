import java.util.Properties

pluginManagement {
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
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\..*")
            }
        }
    }
}

rootProject.name = "LiquidPocket"

// The Android modules need an Android SDK (and the Android Gradle Plugin from Google's Maven).
// Without an SDK only the pure-JVM :core module is part of the build, so `./gradlew :core:test`
// works on any machine with a JDK. CI always has an SDK (ANDROID_HOME is set by setup-android).
fun hasAndroidSdk(): Boolean {
    val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!env.isNullOrBlank() && file(env).isDirectory) return true
    val localProps = file("local.properties")
    if (!localProps.isFile) return false
    val props = Properties().apply { localProps.inputStream().use { load(it) } }
    val sdkDir = props.getProperty("sdk.dir")
    return !sdkDir.isNullOrBlank() && file(sdkDir).isDirectory
}

val androidSdkPresent = hasAndroidSdk()
gradle.extra["lp.hasAndroidSdk"] = androidSdkPresent

include(":core")
if (androidSdkPresent) {
    include(":engine")
    include(":app")
} else {
    logger.warn("Liquid Pocket: no Android SDK found (ANDROID_HOME / local.properties); building :core only.")
}
