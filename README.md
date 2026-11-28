# Sonata

An offline-first Android player for short-form sound. Share a TikTok video to
Sonata and the app turns it into a track: the audio is extracted on-device, the
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
   edits, and tries the top candidates in order. Original sounds use explicit
   caption hints, not arbitrary text. A candidate is accepted only after an
   on-device acoustic proof: spectral-flux correlation plus chroma agreement at
   a speed that stands clear of every other speed. Metadata alone never promotes
   a file, and the matched recording is downloaded in full, never repeated.
4. The matcher searches 0.70×–1.60× in 2% steps and keeps the exact offset of
   the TikTok moment inside the rendered full song. Pitch follows only when the
   chroma rotation clearly beats the untransposed comparison; reverb and bass
   are added only when measured or explicitly tagged. If no candidate passes,
   the actual clip is kept and tagged "clip only" — it is never looped. When a
   full song is proven, both the clip and the full recording are stored.
5. The result is peak-normalised, written as AAC and added to the library with
   cover, waveform, BPM, key and effect tags.

The import runs in WorkManager (it survives leaving the app), shows its real
stage and progress in the import sheet, can be cancelled, and retries up to
three times with backoff. Completed network stages are cached so a retry resumes
instead of downloading everything again.

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
playlist creation and editing. Smart filters narrow the list to full songs,
clips, tracks with lyrics, tracks with BPM, or short/long tracks. The tag editor
updates title, artist and tags and re-matches lyrics when the identity changes.
Search understands plain text (title, artist, tag) and phrases such as
"slow 120 bpm under 40 seconds". BPM is measured by onset autocorrelation and
key by an FFT chroma profile, including minor keys and Camelot codes.

### Player

Full player with large artwork, a tinted backdrop, waveform seek, play/pause,
next/previous, repeat, playback speed, A-B loop and a sleep timer. TikTok tracks
with both files get a Clip / Full song switch that keeps the musical position,
plus a "jump to the TikTok moment" shortcut. Cues save any moment as a hot cue,
memory or eight-second loop and are listed above the dock. A floating mini player
sits above a rounded navigation dock and opens the player with a tap or an upward
swipe; swiping it down fades the sound out and offers Undo. The current file can
be exported through the system share sheet.

### Lyrics

Apple Music-style animated lyrics: the active line grows and brightens while
neighbours fade, and the page follows the song automatically. Tapping a line
seeks there. Timed lyrics are requested in parallel from LRCLIB, NetEase, QQ
Music and lyrics.ovh, cached in Room, and shifted into the finished track's
timeline when the edit changed its tempo. Word-level enhanced LRC animates
phrase by phrase. Plain lyrics are used when no timed version exists; the player
shows which provider supplied them and offers a ±2 s timing nudge for lyrics
that are consistently ahead or behind.

### Mix

The library is ordered into a harmonic path: each next track is chosen for a
small tempo step and a compatible Camelot key. "Play the mix" plays that order.
Every row also has up/down controls, so the queue can be rearranged by hand and
returned to harmonic order with one tap.

### Equalizer

DynamicsProcessing on the player session with a draggable response curve, eight
peaking bands, four-band compression, a lookahead limiter, output gain, A/B
bypass and presets stored in Room. The active engine is shown. On devices
without DynamicsProcessing the app falls back to the framework equalizer for EQ
and boost-only gain; compression, limiting, Q and filter type are hidden or
marked unavailable instead of pretending to work.

### Settings

Theme (light, dark, pure black, system), Material You, six accent colours, a
measured storage breakdown, cache clearing, the equalizer, and library backup.
Backup writes a JSON file with track metadata, tags, lyrics and cues (never audio
or local paths) through the system file picker; restore matches those records to
the current library by acoustic fingerprint. Only settings that change the app are
shown. The Stem separation placeholder is not shipped until a real separation
engine exists.

## Build from source

Requirements: JDK 17 and Android SDK 36. The Gradle wrapper is committed, so no
separate Gradle installation is needed.

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug        # debug APK
./gradlew testDebugUnitTest         # JVM tests (DSP, matching, lyrics, ordering)
./gradlew :app:assembleRelease      # signed release, needs keystore.properties
```

On Windows use `gradlew.bat` instead of `./gradlew`.

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
library, mix, import, editor, settings). The `feature/stems` module remains in the
repository but is deliberately not a dynamic feature of the app. Audio transforms live in
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