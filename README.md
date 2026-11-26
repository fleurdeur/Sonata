# Sonata

An offline-first Android player for short-form sound. Share a TikTok video to
Sonata and the app turns it into a track: the audio is separated on-device, the
real full song is found through TikTok or the BitChord YouTube extractor, and
the clip's edit is measured and reapplied to that full recording. A clip is
never looped into a fake full song.

No account, no ads, no analytics, no uploads. Everything lives on the phone.

## What it actually does

### TikTok → full track

1. In TikTok, tap **Share → More → Sonata**.
2. Sonata reads the post through TikTok's public mobile endpoint (no login, no
   signatures), saves the video to its cache and extracts the audio track with
   MediaCodec.
3. It looks for the full sound behind the post. If TikTok exposes a genuinely
   longer music stream it is used. Otherwise Sonata searches YouTube through the
   BitChord InnerTube extractor, rejects covers, live cuts, loops and hour-long
   edits, and downloads the best full-length match.
4. On the full recording, the clip's onset envelope is matched at every
   plausible speed (0.75×–1.5×), so a sped-up edit stays sped up instead of
   being slowed down by a half-time tempo reading. Pitch follows only when the
   key shift agrees with the detected speed; reverb and bass are added only when
   the clip's tail and tags show them. If no full song is available, the actual
   clip is kept and tagged "clip only" — it is never looped.
5. The result is peak-normalised, written as AAC and added to the library with
   cover, waveform, BPM, key and effect tags.

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

Full player with large artwork, a tinted backdrop, waveform seek, play/pause,
next/previous, repeat, playback speed, A-B loop and a sleep timer. A floating
mini player sits above a rounded navigation dock and opens the player with a tap
or an upward swipe.

### Lyrics

Apple Music-style animated lyrics: the active line grows and brightens while
neighbours fade, and the page follows the song automatically. Tapping a line
seeks there. Timed lyrics are requested in parallel from LRCLIB, NetEase, QQ
Music and lyrics.ovh, cached in Room, and shifted into the finished track's
timeline when the edit changed its tempo. Plain lyrics are used when no timed
version exists; the player shows exactly which provider supplied them.

### Mix

The library is ordered into a harmonic path: each next track is chosen for a
small tempo step and a compatible Camelot key. "Play the mix" plays that order.

### Equalizer

DynamicsProcessing on the player session with a draggable response curve, five
bands (frequency, gain, Q), compressor, limiter, effects and output gain. The
active engine is shown, and the app falls back to the framework equalizer when
DynamicsProcessing is unavailable.

### Settings

Theme (light, dark, pure black, system), Material You, six accent colours,
storage usage and cache clearing, and the equalizer entry point. Only settings
that change the app are shown.

## Build from source

Requirements: JDK 17, Android SDK 36, Gradle 8.13+ (the repository has no wrapper
checked in).

```bash
export ANDROID_HOME=$HOME/Android/Sdk
gradle :app:assembleDebug        # debug APK
gradle testDebugUnitTest         # JVM tests (DSP, matching, lyrics, ordering)
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

- Network calls only for the link you explicitly import, the YouTube full-song
  lookup and the lyrics providers.
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