plugins { id("sonata.android.feature") }

android { namespace = "com.sonata.core.datastore" }

dependencies {
    api(project(":core:domain"))
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
}
