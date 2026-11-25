pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("""com\.android.*""")
                includeGroupByRegex("""com\.google.*""")
                includeGroupByRegex("""androidx.*""")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Sonata"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
enableFeaturePreview("STABLE_CONFIGURATION_CACHE")

include(":app")
include(":core:common")
include(":core:domain")
include(":core:designsystem")
include(":core:database")
include(":core:datastore")
include(":core:data")
include(":core:audio")
include(":core:media")
include(":core:extractor")
include(":core:work")
include(":core:brain")
include(":feature:home")
include(":feature:library")
include(":feature:mix")
include(":feature:import")
include(":feature:editor")
include(":feature:settings")
include(":feature:stems")
