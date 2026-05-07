plugins {
    alias(libs.plugins.gomob.android.native)
    alias(libs.plugins.gomob.android.hilt)
}

android {
    namespace = "io.gomob.nativebridge"

    defaultConfig {
        externalNativeBuild {
            cmake {
                targets += listOf("gomob_native")
            }
        }
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
}
