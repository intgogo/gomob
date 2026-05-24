plugins {
    alias(libs.plugins.gomob.android.feature)
    alias(libs.plugins.gomob.android.library.compose)
}

android {
    namespace = "io.gomob.feature.collaboration"
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.livekit.android)
}
