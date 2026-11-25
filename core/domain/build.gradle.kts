plugins {
    id("sonata.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.sonata.core.domain" }

dependencies {
    api(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
