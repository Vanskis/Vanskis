# JNI resolves native methods by class and method name.
-keepclasseswithmembernames class com.vanskis.liquidpocket.engine.** {
    native <methods>;
}
