plugins {
    alias(libs.plugins.gomob.android.library)
    alias(libs.plugins.gomob.android.hilt)
}

android {
    namespace = "io.gomob.domain"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.kotlinx.coroutines.android)
}
