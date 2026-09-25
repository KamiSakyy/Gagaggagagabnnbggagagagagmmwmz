# The native Live2D Cubism Core looks up its JNI methods by name, so those classes must survive
# obfuscation untouched (a renamed class or method makes the native lookup fail at runtime).
-keep class com.live2d.sdk.cubism.core.** { *; }

# ML Kit uses reflection internally.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

-dontwarn com.live2d.**
