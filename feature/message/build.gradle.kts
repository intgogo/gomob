plugins {
    alias(libs.plugins.gomob.android.feature)
    alias(libs.plugins.gomob.android.library.compose)
}

android {
    namespace = "io.gomob.feature.message"
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.serialization.json)
}
