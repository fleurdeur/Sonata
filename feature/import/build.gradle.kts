plugins { id("sonata.android.feature") }

android { namespace = "com.sonata.feature.ingest" }

dependencies {
    implementation(project(":core:work"))
    implementation(project(":core:extractor"))
    implementation(project(":core:data"))
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.activity.compose)
}
