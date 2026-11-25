import com.android.build.api.dsl.LibraryExtension
import com.sonata.buildlogic.configureCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.configure<LibraryExtension> { configureCompose() }
            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
            val implementation = configurations.getByName("implementation")
            listOf(
                "compose-ui",
                "compose-foundation",
                "compose-material3",
                "compose-ui-tooling-preview",
                "compose-material-icons",
            ).forEach { alias ->
                implementation.dependencies.add(libs.findLibrary(alias).get().get())
            }
            val bom = libs.findLibrary("compose-bom").get().get()
            implementation.dependencies.add(
                project.dependencies.enforcedPlatform(bom),
            )
        }
    }
}
