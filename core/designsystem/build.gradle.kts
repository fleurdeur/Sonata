plugins {
    id("sonata.android.library")
    id("sonata.android.compose")
}

android { namespace = "com.sonata.core.designsystem" }

dependencies {
    api(project(":core:domain"))
    implementation(libs.palette.ktx)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
