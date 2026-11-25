plugins { id("sonata.android.feature") }

android { namespace = "com.sonata.feature.settings" }

dependencies {
    implementation(project(":core:audio"))
    implementation(project(":core:data"))
    implementation(libs.compose.material.icons)
}
