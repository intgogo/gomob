import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/**
 * Native (NDK) 库 convention：
 * - 启用 externalNativeBuild + CMake
 * - 默认目标 ABI：arm64-v8a / armeabi-v7a（覆盖现役所有 Android 手机）
 * - C++17，开启 -fPIC、-Wall
 *
 * Why: depth/fusion/reconstruction 等模块统一靠这个插件接 native，避免每个 *.gradle.kts 重复样板。
 */
class AndroidNativeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("gomob.android.library")

            extensions.configure<LibraryExtension> {
                defaultConfig {
                    ndk {
                        abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                    }
                    externalNativeBuild {
                        cmake {
                            cppFlags += listOf("-std=c++17", "-fPIC", "-Wall", "-Wextra")
                            arguments += listOf(
                                "-DANDROID_STL=c++_shared",
                                "-DANDROID_ARM_NEON=TRUE",
                            )
                        }
                    }
                }
                ndkVersion = libs().findVersion("ndk").get().requiredVersion
                externalNativeBuild {
                    cmake {
                        version = libs().findVersion("cmake").get().requiredVersion
                    }
                }
                packaging {
                    jniLibs {
                        useLegacyPackaging = false
                    }
                }
            }
        }
    }
}
