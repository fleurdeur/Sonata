<div align="center">

<br/>
<br/>

# Sonata

### The definitive home for short-form sound

<br/>

[![Latest release](https://img.shields.io/github/v/release/fleurdeur/Sonata?style=for-the-badge&labelColor=0d1117)](https://github.com/fleurdeur/Sonata/releases)
[![License](https://img.shields.io/github/license/fleurdeur/Sonata?style=for-the-badge&labelColor=0d1117)](https://github.com/fleurdeur/Sonata/blob/main/LICENSE)
[![CI](https://img.shields.io/github/actions/workflow/status/fleurdeur/Sonata/ci.yml?style=for-the-badge&labelColor=0d1117)](https://github.com/fleurdeur/Sonata/actions)
[![Core size](https://img.shields.io/badge/core%20bundle-7%20MB-6C5CE7?style=for-the-badge&labelColor=0d1117)](https://github.com/fleurdeur/Sonata/releases)

<br/>

[**Download**](#download) · [**Features**](#features) · [**Build from source**](#build-from-source) · [**Contributing**](#contributing) · [**Disclaimer**](#disclaimer)

</div>

> [!IMPORTANT]
> Sonata is an offline-first, privacy-first local music player for short clips. It contains **no ads, no analytics, no accounts and no tracking**. The optional YouTube/YouTube Music source is not affiliated with, endorsed by, or connected to YouTube or Google in any way. Use it at your own discretion.

Sonata treats a 15-second TikTok clip with the same respect as a full album track: sped-up edits, slowed-and-reverb loops, hardstyle snippets, voice memos — everything becomes a beautifully organized library. Inspired by melo: Playlists for Sounds, but going far beyond it.

---

<div align="center">

<h1><a id="features"></a>Features</h1>

<table>
  <tr>
    <td width="50%" valign="top">

#### Ingestion
- **System Share Sheet target** — ACTION_SEND / SEND_MULTIPLE for videos, audio and links.
- **SAF picker, Photo Picker, split-screen drag and drop, clipboard watcher, quick-import bubble.**
- **In-app voice recorder** with microphone capture and **watched folders**.
- **Measured import pipeline** — visible queue, cancel, retry with exponential backoff through WorkManager.
- **Lossless remux first**, high-bitrate transcode only as a fallback; sample rate and channels preserved.
- **Dedupe by audio fingerprint**, auto-tagging by source, on-device BPM, musical key, loudness (LUFS), mood and waveform analysis.
- **Metadata + tag editor** that writes standard tags on export.

#### Editing (non-destructive)
- **Automatic dead-air trimming** with a visible "what was cut" region and full undo.
- **Zoomable waveform** with draggable handles, 10 ms nudging, haptic ticks at zero crossings and a minimap strip.
- **Fades, normalize, reverse, split, merge, pitch and speed tools, noise reduction, voice isolation.**
- **Loop-point tuning with zero-crossing snapping** — infinite single-clip loops that are gapless and click-free.
- Every edit is a **recipe over the original file** — reset anytime, with full undo/redo history.
- **On-demand stem separation module** — vocals, drums, bass, other; solo/mute, karaoke mode, acapella/instrumental export.

#### Playback
- **Media3 MediaSessionService** with a foreground notification, lock-screen controls and artwork.
- Bluetooth / LE Audio, headset buttons, Android Auto, Cast and DLNA output, audio focus and ducking.
- **Infinite gapless looping** of a single sound via ExoPlayer repeat-one with zero-gap clipping.
- Playlists with drag-and-drop ordering, bulk select, nested folders, custom covers, shuffle, repeat and a **queue editor** with Play Next, Save Queue as Playlist and undo.
- **Smart playlists** by rule — most played, recently added, shortest, BPM range, key, source, mood, tag.
- **A-B loop, crossfade, gentle-fade sleep timer, resume where you left off, pause-on-disconnect and a "Play it again" quick-loop button.**

    </td>
    <td width="50%" valign="top">

#### The Mix system
- **Mix button on any playlist or the live queue** — opens a transition editor.
- Auto-generated or preset transitions: Fade, Rise, Blend, Echo Out, Reverb Tail, Filter Sweep, Tape Stop, Vinyl Brake, Spin-Up, Beat-Match Crossfade, Cut, Drop Swap, Wash Out — each with length, curve and intensity.
- **Camelot-wheel harmonic matching**, beat-matching by safe time-stretching and auto-ordering for the smoothest flow.
- **Two overlapping waveforms** with a draggable transition region and a live preview of the exact join.
- Export a mix as one continuous file, save as a **Mixed playlist** and share as a **Sonata Pack** friends import with no account.
- **Dynamic Mix Queue** with refresh-without-stopping, Smart Shuffle markers, one-tap Reshuffle, the **Remix Lab** (Slowed + Reverb, Nightcore and more as non-destructive versions), Remix Radio and **Auto-Mix**.
- **Soundboard & Sampler** — a 4×4 pad grid with < 20 ms latency, per-pad loop/pitch/volume, tempo-synced looping and recordable performances.

#### The sound engine (Equalizer314, reimplemented)
- **DynamicsProcessing chain** attached to the ExoPlayer audio session: per-channel parametric pre-EQ, multiband compressor with adjustable crossovers, post-EQ, master limiter with ceiling and EBU R128 loudness normalization.
- **Draggable frequency-response curve**, live spectrum, gain-reduction meters, bypass, A/B comparison and a band sweep tool.
- **30+ factory presets** across genres (Vaporwave → Cinematic), situations (Gym, Car, 3am…) and effects (Underwater, Telephone, 8D Spatial, Vinyl Crackle…), each listing exactly which bands it changes.
- Preset manager with save/overwrite/rename/duplicate/import/export, **AutoEQ import** and device-aware profiles.
- Graceful **fallback to framework Equalizer / BassBoost / LoudnessEnhancer** — the active engine is always displayed.

#### Sonata Brain
- **On-device, explainable suggestions** — every card says why ("Suggested because this clip is 140 BPM with heavy bass") and is dismissible.
- Preset advisor, auto-fit EQ, clipping flags, playlist naming, near-duplicate spotting, better loop points, next-track picks, mood/time-of-day playlists, a **For You** feed, a daily **Fresh Mix** and **natural-language search** ("slow sad songs under 30 seconds").
- A **Taste Profile** screen with controls to edit, reset or turn off each learning type. Glass chips, snooze, "never suggest this" — all opt-out, all local.

    </td>
  </tr>
</table>

#### Design & sharing
- **Liquid glass meets editorial minimalism** — frosted translucent surfaces with real RenderEffect blur (tinted fallback below Android 12), 28–32 dp rounded cards, soft inner highlights, spring physics and haptics everywhere.
- Cover-driven **mesh-gradient** Now Playing, five player styles (full-bleed cover, vinyl, cassette, minimal waveform, glass card), an audio-reactive visualizer and lyric-style captions.
- Light, Dark, **Pure Black AMOLED**, Material You dynamic color, accent presets and a cover-adaptive theme; adaptive two-pane layouts for tablets and foldables.
- **Ringtone/notification/alarm setter** with a length-enforcing trimmer, audio file export, animated waveform video cards, Quick Share, Glance widgets (2×2, 4×2, 4×4), a Quick Settings tile, app shortcuts, Car Mode and a Wear OS companion.
- TalkBack labels, font scaling to 200%, reduced motion, high contrast, 48 dp touch targets and RTL layouts.

</div>

---

<div align="center">

<h1><a id="download"></a>Download</h1>

Grab the latest AAB / APK from the [Releases](https://github.com/fleurdeur/Sonata/releases) page. Sideloading requires enabling "Install unknown apps" for whichever app you download it with. The core app ships as an **~7 MB** Android App Bundle with R8, resource shrinking, vector-only assets and an on-demand stem-separation module.

</div>

---

<div align="center">

<h1><a id="build-from-source"></a>Build from source</h1>

</div>

```bash
git clone https://github.com/fleurdeur/Sonata.git
cd Sonata

# JDK 17+ and an Android SDK with platform 36 are required.
# Point local.properties at your SDK (or set ANDROID_HOME):
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Debug build
./gradlew :app:assembleDebug

# Release app bundle (R8 + resource shrinking)
./gradlew bundleRelease

# Unit tests (silence detection, BPM/key, fingerprints, DSP mapping, Brain ranking)
./gradlew testDebugUnitTest

# Lint-free static analysis
./gradlew lint detekt
```

The output lands at `app/build/outputs/bundle/release/app-release.aab` (and `app/build/outputs/apk/debug/app-debug.apk`).

**Module structure**

```
app/                  single-activity shell: navigation, bottom bar, mini-player, Now Playing, onboarding
build-logic/          convention plugins (sonata.android.library / compose / hilt / feature / application)
core/common           dispatchers, Result, formatters
core/domain           models + repository contracts (Sound, Playlist, Mix, Preset, Dsp, Brain)
core/designsystem     tokens, theme, glass surfaces, empty states, skeletons
core/database         Room + FTS4 search, DAOs, schema
core/datastore        DataStore-backed settings
core/data             repository implementations, factory preset library, Brain learning store
core/audio            DynamicsProcessing engine + framework fallback, parameter mapping
core/media            Media3 PlaybackService, PlayerConnection
core/extractor        BitChord-style YouTube / YouTube Music InnerTube extractor, providers, resolvers
core/work             import pipeline: decode → dedupe → analyze → store (WorkManager)
core/brain            suggestion ranking, natural-language search, Remix Lab profiles
feature/home          greeting, quick filters, Continue hero, For You, Fresh Mix, suggestions
feature/library       Sounds/Playlists/Folders/Mixes, grid/list, multi-select, search overlay
feature/mix           track strip, transition editor, sampler
feature/ingest        import sheet + queue
feature/editor        trim editor with zoomable waveform
feature/settings      searchable settings hierarchy, EQ screen, preset browser
feature/stems         on-demand dynamic feature (on-device stem separation)
```

---

<div align="center">

<h1><a id="contributing"></a>Contributing</h1>

We welcome contributions to Sonata! Fork the repo, create a feature branch, run `./gradlew testDebugUnitTest lint` before opening a Pull Request.

</div>

---

<div align="center">

<h1><a id="disclaimer"></a>Credits &amp; Disclaimer</h1>

- The YouTube / YouTube Music extractor is a full, self-contained InnerTube implementation in the style of [**kushagrasinghx/BitChord**](https://github.com/kushagrasinghx/BitChord).
- The sound engine is a native reimplementation of the chain popularized by [**bearinmindcat/Equalizer314**](https://github.com/bearinmindcat/Equalizer314).
- The streaming-provider layer and playback UX follow the architecture of [**rukamori/ArchiveTune**](https://github.com/rukamori/ArchiveTune).
- The short-form-sound concept was inspired by **melo: Playlists for Sounds**.

Sonata is provided as-is, without warranty of any kind. Streaming from third-party sources depends on those services; region blocks, age restrictions and unavailable tracks are handled gracefully but never bypassed beyond what the platform offers.

</div>
