# The engine's classes are reached from native code (JNI registration and callbacks).
-keep class com.vanskis.liquidpocket.engine.** { *; }

# WorkManager instantiates workers by class name.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# :core reads the Hugging Face listing through kotlinx-serialization's JsonElement API only (no
# @Serializable classes), which the library's bundled consumer rules already cover.
