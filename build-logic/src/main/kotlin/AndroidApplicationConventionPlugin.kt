import com.android.build.api.dsl.ApplicationExtension
import com.sonata.buildlogic.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
                apply("org.jetbrains.kotlin.plugin.compose")
                apply("com.google.dagger.hilt.android")
                apply("com.google.devtools.ksp")
            }
            val extension = extensions.getByType<ApplicationExtension>()
            configureKotlinAndroid(extension)
            extension.apply {
                defaultConfig.targetSdk = 36
                testOptions.unitTests.isReturnDefaultValues = true
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro",
                        )
                    }
                    debug {
                        applicationIdSuffix = ".debug"
                    }
                }
                packaging {
                    resources.excludes += setOf(
                        "/META-INF/{AL2.0,LGPL2.1}",
                        "META-INF/DEPENDENCIES",
                        "META-INF/INDEX.LIST",
                    )
                }
                dependenciesInfo {
                    includeInApk = false
                    includeInBundle = false
                }
            }
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            val handler = configurations
            handler.getByName("implementation").dependencies.add(libs.findLibrary("hilt-android").get().get())
            handler.getByName("ksp").dependencies.add(libs.findLibrary("hilt-compiler").get().get())
            handler.getByName("implementation").dependencies.add(libs.findLibrary("profileinstaller").get().get())
            handler.getByName("debugImplementation").dependencies.add(libs.findLibrary("leakcanary").get().get())
        }
    }
}
