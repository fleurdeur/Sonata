# Sonata

An offline-first Android player for short-form sound. Share a TikTok video to
Sonata and the app turns it into a track: the audio is separated on-device, the
full song is fetched when TikTok exposes it, and the clip is rebuilt into a
complete, loopable song with the original edit's character.

No account, no ads, no analytics, no uploads. Everything lives on the phone.

## What it actually does

### TikTok → full track

1. In TikTok, tap **Share → More → Sonata**.
2. Sonata reads the post through TikTok's public mobile endpoint (no login, no
   signatures), saves the video to its cache and extracts the audio track with
   MediaCodec.
3. It measures tempo, key and reverb tail on the clip, then looks for the full
   sound behind the post. When the sound is longer than the clip, that file is
   downloaded and rendered as the track; otherwise the clip is looped to a
   complete 2-minute song with equal-power crossfades at every join.
4. Tempo, pitch character, reverb and bass are matched from the clip, the result
   is peak-normalised, written as AAC and added to the library with cover,
   waveform, BPM, key and effect tags ("nightcore", "slowed", "reverb", …).

The import runs in WorkManager (it survives leaving the app), shows its real
stage and progress in the import sheet, can be cancelled, and retries once with
backoff before it reports a failure.

### Import from elsewhere

- Any audio or video file through the system document picker (video becomes
  audio).
- A pasted link: TikTok, direct audio, and the BitChord YouTube/YouTube Music
  extractor when the network allows it.
- The microphone, recorded straight to AAC. Icons and text on every row name the
  real action; nothing is a placeholder.

### Library

Recent imports, most played, and sounds that came from TikTok. Multi-select for
play, favourite, delete or add to playlist; sort by recency, title, plays or BPM;
playlist creation and editing. Search understands plain text (title, artist, tag)
and phrases such as "slow 120 bpm under 40 seconds".

### Player

Full player with artwork, waveform seek, play/pause, next/previous, repeat,
playback speed, A-B loop and a sleep timer. A mini player sits above the
navigation bar and opens the full player with a tap or an upward swipe.

### Mix

The library is ordered into a harmonic path: each next track is chosen for a
small tempo step and a compatible Camelot key. "Play the mix" plays that order.

### Equalizer

DynamicsProcessing on the player session with a draggable response curve, five
bands (frequency, gain, Q), compressor, limiter, effects and output gain. The
active engine is shown, and the app falls back to the framework equalizer when
DynamicsProcessing is unavailable.

### Settings

Theme (light, dark, pure black, system), Material You, six accent colours, glass
blur strength, storage usage and cache clearing, and the equalizer entry point.
Only settings that change the app are shown.

## Build from source

Requirements: JDK 17, Android SDK 36, Gradle 8.13+ (the repository has no wrapper
checked in).

```bash
export ANDROID_HOME=$HOME/Android/Sdk
gradle :app:assembleDebug        # debug APK
gradle testDebugUnitTest         # JVM tests (PCM/DSP, parsing, ordering)
gradle :app:assembleRelease      # signed release, needs keystore.properties
```

The release build reads `keystore.properties` from the repository root:

```properties
storeFile=sonata-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Both the keystore and the properties file are git-ignored; never commit them.

## Architecture

Kotlin + Jetpack Compose + Material 3, Hilt, Room, DataStore, WorkManager and
Media3. Multi-module: `app`, `core/*` (common, domain, designsystem, database,
datastore, data, audio, media, extractor, work, brain) and `feature/*` (home,
library, mix, import, editor, settings, stems). Audio transforms live in
`core/audio` as pure Kotlin so they are unit-tested on the JVM.

## Privacy

- No network calls except the link you explicitly import.
- No account, no telemetry, no crash reporting.
- Imported audio, covers and the library database stay in app storage.
- The Android share sheet sends Sonata the link or file you picked; nothing else.

## Disclaimer

Sonata is not affiliated with TikTok, ByteDance, YouTube or Google. Only import
content you have the right to use. The YouTube extractor is derived from
[BitChord](https://github.com/kushagrasinghx/BitChord) and is provided as-is;
platforms change and some networks block it.

## License

MIT — see [LICENSE](LICENSE).
