plugins {
    alias(libs.plugins.gomob.android.feature)
    alias(libs.plugins.gomob.android.library.compose)
}

android {
    namespace = "io.gomob.feature.settings"
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
}
