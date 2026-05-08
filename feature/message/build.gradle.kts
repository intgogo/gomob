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
    implementation(libs.kotlinx.serialization.json)
}
