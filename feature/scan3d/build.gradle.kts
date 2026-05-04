plugins {
    alias(libs.plugins.gomob.android.feature)
    alias(libs.plugins.gomob.android.library.compose)
}

android {
    namespace = "io.gomob.feature.scan3d"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:native-bridge"))
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Filament 在 M3 重建预览时再加（点云 / mesh 实时渲染）
}
