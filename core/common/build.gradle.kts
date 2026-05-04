plugins {
    alias(libs.plugins.gomob.android.library)
}

android {
    namespace = "io.gomob.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
