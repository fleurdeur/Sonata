plugins {
    id("sonata.android.feature")
    alias(libs.plugins.room)
}

android { namespace = "com.sonata.core.database" }

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:domain"))
    api(project(":core:common"))
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)
}
