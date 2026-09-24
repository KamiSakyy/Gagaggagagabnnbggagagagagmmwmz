# gobind / gomobile generated bindings - referenced from native code by name.
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
-dontwarn io.nekohasekai.libbox.**

# Java implementations of Go interfaces are called from native code.
-keep interface io.nekohasekai.libbox.** { *; }
-keep class * implements io.nekohasekai.libbox.** { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.** { *; }

# Keep engine bridge classes reachable from native callbacks.
-keep class com.vortex.vpn.core.** { *; }
-keep class com.vortex.vpn.model.** { *; }
-keep class com.vortex.vpn.cfg.** { *; }
-keep class com.vortex.vpn.sub.** { *; }

# SnakeYAML uses reflection over the JDK; keep it simple.
-dontwarn org.yaml.snakeyaml.**
-keep class org.yaml.snakeyaml.** { *; }
