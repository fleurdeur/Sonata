plugins { id("sonata.android.feature") }

android { namespace = "com.sonata.feature.mix" }

dependencies {
    implementation(project(":core:media"))
    implementation(project(":core:data"))
    implementation(libs.compose.material.icons)
}
