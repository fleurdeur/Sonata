plugins {
    `kotlin-dsl`
}

group = "com.sonata.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "sonata.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "sonata.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "sonata.android.hilt"
            implementationClass = "HiltConventionPlugin"
        }
        register("androidFeature") {
            id = "sonata.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidApplication") {
            id = "sonata.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
    }
}
