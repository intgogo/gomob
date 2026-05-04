plugins {
    alias(libs.plugins.gomob.android.feature)
    alias(libs.plugins.gomob.android.library.compose)
}

android {
    namespace = "io.gomob.feature.gallery"
}

dependencies {
    implementation(project(":core:data"))
    // Filament 在 M3 历史回看（glTF 渲染）时再加

}
