plugins {
    id("sonata.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.sonata.core.brain" }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:audio"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
