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
}
