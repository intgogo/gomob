import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("gomob.android.library")
            pluginManager.apply("gomob.android.hilt")

            dependencies {
                add("implementation", project(":core:ui"))
                add("implementation", project(":core:designsystem"))
                add("implementation", project(":core:domain"))
                add("implementation", project(":core:model"))
                add("implementation", project(":core:common"))

                add("implementation", libs().findLibrary("androidx-lifecycle-runtime-ktx").get())
                add("implementation", libs().findLibrary("androidx-lifecycle-viewmodel-ktx").get())
                add("implementation", libs().findLibrary("androidx-lifecycle-runtime-compose").get())
                add("implementation", libs().findLibrary("androidx-lifecycle-viewmodel-compose").get())
                add("implementation", libs().findLibrary("androidx-navigation-compose").get())
                add("implementation", libs().findLibrary("hilt-navigation-compose").get())
                add("implementation", libs().findLibrary("kotlinx-coroutines-android").get())

                add("testImplementation", libs().findLibrary("junit").get())
                add("testImplementation", libs().findLibrary("kotlinx-coroutines-test").get())
                add("testImplementation", libs().findLibrary("truth").get())
            }
        }
    }
}
