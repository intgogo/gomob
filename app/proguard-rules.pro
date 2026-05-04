# gomob 默认 proguard 规则
# native 方法名必须保留
-keepclasseswithmembernames class * {
    native <methods>;
}

# Berxel SDK（待 AAR 引入后按官方 README 补 -keep 规则）
# -keep class com.berxel.** { *; }

# Kotlinx Serialization
-keep,includedescriptorclasses class io.gomob.**$$serializer { *; }
-keepclassmembers class io.gomob.** {
    *** Companion;
}
-keepclasseswithmembers class io.gomob.** {
    kotlinx.serialization.KSerializer serializer(...);
}
