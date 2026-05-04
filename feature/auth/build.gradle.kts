plugins {
    alias(libs.plugins.gomob.android.feature)
    alias(libs.plugins.gomob.android.library.compose)
}

android {
    namespace = "io.gomob.feature.auth"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:network"))
}
