plugins {
    alias(libs.plugins.gomob.android.feature)
    alias(libs.plugins.gomob.android.library.compose)
}

android {
    namespace = "io.gomob.feature.gallery"
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.filament.android)
    implementation(libs.gltfio.android)
}
