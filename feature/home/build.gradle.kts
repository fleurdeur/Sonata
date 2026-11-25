plugins { id("sonata.android.feature") }

android { namespace = "com.sonata.feature.home" }

dependencies {
    implementation(project(":core:media"))
    implementation(project(":core:brain"))
    implementation(project(":core:data"))
    implementation(libs.compose.material.icons)
}
