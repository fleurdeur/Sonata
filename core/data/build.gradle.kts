plugins {
    id("sonata.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.sonata.core.data" }

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
}
