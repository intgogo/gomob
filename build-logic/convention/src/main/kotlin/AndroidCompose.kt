import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

internal fun Project.configureAndroidCompose(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    val libs = libs()
    commonExtension.apply {
        buildFeatures {
            compose = true
        }
    }

    dependencies {
        val bom = libs.findLibrary("compose-bom").get()
        add("implementation", platform(bom))
        add("androidTestImplementation", platform(bom))
        add("implementation", libs.findLibrary("compose-ui").get())
        add("implementation", libs.findLibrary("compose-ui-graphics").get())
        add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
        add("implementation", libs.findLibrary("compose-material3").get())
        add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
        add("debugImplementation", libs.findLibrary("compose-ui-test-manifest").get())
        add("androidTestImplementation", libs.findLibrary("compose-ui-test-junit4").get())
    }
}
