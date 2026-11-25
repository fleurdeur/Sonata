plugins { id("sonata.android.feature") }

android { namespace = "com.sonata.feature.editor" }

dependencies {
    implementation(project(":core:work"))
    implementation(libs.compose.material.icons)
}
