plugins {
    alias(libs.plugins.gomob.android.feature)
    alias(libs.plugins.gomob.android.library.compose)
}

android {
    namespace = "io.gomob.feature.scan"
}

dependencies {
    implementation(project(":core:native-bridge"))
    implementation(project(":core:data"))
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.filament.android)
    implementation(libs.filament.utils.android)
}
