plugins { id("sonata.android.feature") }

android { namespace = "com.sonata.core.work" }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:data"))
    implementation(project(":core:extractor"))
    implementation(project(":core:audio"))
    implementation(libs.work.runtime)
    implementation(libs.media3.transformer)
    implementation(libs.media3.exoplayer)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.hilt.work.compiler)
    implementation(libs.hilt.work)
    implementation(libs.hilt.android)
}
