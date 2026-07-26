plugins {
    alias(libs.plugins.gomob.android.library)
    alias(libs.plugins.gomob.android.library.compose)
}

android {
    namespace = "io.gomob.designsystem"
}

dependencies {
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    // 毛玻璃: GlassSurface / hazeSource 由 designsystem 统一封装, feature 层透传使用
    api(libs.haze)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
