plugins {
    alias(libs.plugins.gomob.android.native)
    alias(libs.plugins.gomob.android.hilt)
}

android {
    namespace = "io.gomob.nativebridge"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                targets += listOf("gomob_native")
            }
        }
        // M1.6.6 feature flag — 选 Berxel stack 后端：
        //   "SDK"            = 用厂商 BerxelHawkContext（libBerxelHawk.so + libuvc-0.0.7 + 自家 libusb），默认值
        //   "NATIVE_REWRITE" = 用 BerxelNativeStack（libusb-1.0 + 自实现 Sonix XU 协议，M1.6.6 实验路径）
        // 翻这个值不用改代码 — gradle 重编一次就走另一路。运行时也可被 [BerxelStackBackendOverride] 临时覆盖。
        buildConfigField("String", "BERXEL_STACK_BACKEND", "\"NATIVE_REWRITE\"")
    }

    externalNativeBuild {
        cmake {
            path = file("../../native/CMakeLists.txt")
        }
    }

    // Berxel SDK 的预编译 .so（按 ABI 分目录）按 third_party/berxel-android/README.md 投放后
    // 自动打进 APK。SDK 缺失时这里是空目录，不会让构建失败 —— 但运行时调 Berxel
    // API 会 UnsatisfiedLinkError。
    sourceSets["main"].jniLibs.srcDir(file("../../third_party/berxel-android/jniLibs"))
    // libusb-1.0 prebuilt：gomob_native.so 链接它，APK 里也要带它的 .so 才能运行时 dlopen。
    sourceSets["main"].jniLibs.srcDir(file("../../third_party/libusb-android/lib"))
    // eYs3D/VIN 双相机原厂隔离栈：RS-D550=libuvc+libusb100；HLSD8=libuvc1+libusb1001。
    sourceSets["main"].jniLibs.srcDir(file("../../third_party/eys3d-vendor/lib"))
    // pupil-labs libuvc（含 MJPEG 解析 + uvc_wrap fd 注入）：eYs3D 真彩色 MJPEG 走它，
    // soname 已 patchelf 为 libuvc_pupil.so（避与 vendor libuvc.so 冲突），NEEDED 已对齐 libusb-1.0.so。
    sourceSets["main"].jniLibs.srcDir(file("../../third_party/libuvc-android/jniLibs"))
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.annotation)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)

    // Berxel SDK Java 入口（jar，~14MB）。fileTree 写法在 jar 缺失时返回空集合，
    // 编译不挂；运行时调用就 ClassNotFoundException —— 让"SDK 未投放"在测试里立即暴露，
    // 不写"假兜底"。
    implementation(
        fileTree("../../third_party/berxel-android/libs") {
            include("*.jar")
        }
    )

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
