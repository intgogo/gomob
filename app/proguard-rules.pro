# gomob release 混淆规则

# 压缩包层级和访问边界，降低反编译后的模块可读性。
-allowaccessmodification
-overloadaggressively
-repackageclasses io.gomob.o
-adaptclassstrings
-adaptresourcefilenames **.properties,META-INF/services/**
-adaptresourcefilecontents **.properties,META-INF/services/**
-renamesourcefileattribute SourceFile

# 运行期反射仍需要这些结构属性；不要保留 LineNumberTable。
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# release 包剥掉低等级日志，避免反编译或抓 logcat 时泄露内部状态。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
}

# native 方法名必须保留；当前 JNI 采用 Java_... 静态导出。
-keepclasseswithmembernames class * {
    native <methods>;
}

# eYs3D vendor shim 类（com.esp.android.usb.camera.core.*）：libUVCCamera.so 的 JNI_OnLoad 经
# FindClass + RegisterNatives 把 native 方法绑到这些类（native 直驱路径 setVM 需要此绑定动作）。
# Kotlin 侧已无任何引用（Eys3dApcCamera.kt 退役），故必须显式 keep，否则 release R8 收缩掉 → FindClass 失败。
-keep class com.esp.android.usb.camera.core.** { *; }

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
