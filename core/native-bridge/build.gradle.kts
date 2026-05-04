plugins {
    alias(libs.plugins.gomob.android.native)
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
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.annotation)
    implementation(libs.timber)
}
