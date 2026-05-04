plugins {
    alias(libs.plugins.gomob.android.feature)
    alias(libs.plugins.gomob.android.library.compose)
}

android {
    namespace = "io.gomob.feature.profile"
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.datastore.preferences)
}
