plugins {
    alias(libs.plugins.gomob.android.feature)
    alias(libs.plugins.gomob.android.library.compose)
}

android {
    namespace = "io.gomob.feature.calibration"
}

dependencies {
    implementation(project(":core:native-bridge"))
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.lifecycle)
}
