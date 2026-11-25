plugins { id("sonata.android.feature") }

android { namespace = "com.sonata.core.audio" }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.android)
}
