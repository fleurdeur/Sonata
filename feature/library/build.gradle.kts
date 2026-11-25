plugins { id("sonata.android.feature") }

android { namespace = "com.sonata.feature.library" }

dependencies {
    implementation(project(":core:media"))
    implementation(project(":core:brain"))
    implementation(libs.compose.material.icons)
}
