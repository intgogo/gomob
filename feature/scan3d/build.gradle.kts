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
    // Filament — 扫描中 TSDF 点云实时 3D 预览（手势旋转/缩放），未来 Gallery mesh 回看复用
    implementation(libs.filament.android)
    implementation(libs.filament.utils.android)
}
