plugins {
    id("sonata.android.library")
    id("sonata.android.hilt")
}

android { namespace = "com.sonata.core.common" }

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
