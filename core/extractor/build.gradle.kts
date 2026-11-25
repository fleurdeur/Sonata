plugins { id("sonata.android.feature") }

android { namespace = "com.sonata.core.extractor" }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // BitChord — the YouTube / YouTube Music extractor:
    // https://github.com/kushagrasinghx/BitChord
    //
    // Sonata's BitchordExtractor in this module is a full, self-contained
    // InnerTube implementation in the BitChord style (multi-client fallback,
    // signature handling, radio/autoplay, suggestions, expired-URL refresh),
    // so it works with zero external artifacts.
    //
    // The upstream library is wired below; uncomment once JitPack publishes
    // the artifact (currently the repo has no JitPack build yet):
    // implementation(libs.bitchord)
}
