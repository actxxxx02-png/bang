<p align="center">
  <img src="store/icon-512.png" width="96" alt="PromptCut icon">
</p>

<h1 align="center">PromptCut</h1>

<p align="center"><b>AI-powered mobile video editor on Android with a strictly monochrome studio interface.</b><br>
On-device analysis, multi-track timeline editing, autonomous AI editing agent, and local hardware-accelerated Media3 export.</p>

<p align="center">
  <img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-111111?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-222222?logo=kotlin&logoColor=white">
  <img alt="Media3" src="https://img.shields.io/badge/Media3-Transformer%201.11-333333">
  <img alt="Version" src="https://img.shields.io/badge/version-1.0.0-000000">
</p>

## What PromptCut Does (v1.0.0 — V3 Studio Engine)

PromptCut brings natural-language video editing into a full multi-track monochrome studio:

1. **Strictly Monochrome Dark Studio UI (`Dark` default, `Light`, & `System`)**
   - Live **Preview Stage** (`1080p` default or optional `720p` proxy mode) with GL-matched effect previews, V2 picture-in-picture overlays, styled captions with safe-area guides, A/B compare, speed selector (`0.5x`–`2x`), and `-1f`/`+1f` frame steppers.
   - Zoomable **Multi-Track Timeline** (`V1` main video, `V2` overlay/PIP, `A1` music, `A2` voiceover, `A3` sound FX, `T1` captions) with pinch-to-zoom, beat marker diamonds, draggable trim handles, sticky track lock/mute/visibility headers, and **60-step Undo/Redo** with browsable history.
   - Expandable **Bottom Studio Dock** for **AI Agent**, **Style**, **Edit / Speed Ramp / Keyframes**, **Captions**, **Audio / SFX**, **Effects / PIP**, and **Export / QA**.
2. **Unified AI Brain & Multi-API Key Manager**
   - **Google Gemini** (free tier, vision + sampled frames), **Groq** (free tier, fast LLM + Whisper speech-to-text), and **Anthropic Claude** (strictly manual-only with explicit paid-API consent — never auto-selected).
   - **Unlimited Keys per Provider**: Add, rename, mask, enable/disable, prioritize preferred keys, test individually or batch-test, and view per-key health/cooldown status with smart same-provider key rotation.
   - **Conversation Memory & Token Budgeting**: Remembers previous turns, project instructions, editing decisions, selected clips/segments, and rolling context compaction.
3. **Autonomous AI Editing Agent**
   - Strictly typed, schema-validated editing tools across all V1/V2/A1/A3/T1 tracks (`inspect_clips`, `detect_highlights`, `trim_segment`, `split_segment`, `delete_segment`, `duplicate_segment`, `reorder_segments`, `set_crop_resize`, `generate_captions`, `edit_caption`, `style_captions`, `set_transition`, `set_look`, `set_speed`, `set_effects`, `add_keyframe`, `add_overlay_clip`, `add_sfx`, `trim_silence`, `sync_to_beats`, `configure_audio_music`, `inspect_timeline_qa`).
   - **Track Lock & Edit Protection**: Respects locked tracks, prompts before overwriting manual edits (`editedByUser`) or performing major destructive timeline replacements, and executes every agent edit atomically.
4. **Video Effects, Audio Studio & Hardware-Adaptive Export**
   - Per-clip OpenGL ES 2.0 shaders (`ShaderAdjustEffect`, `ShakeMatrixEffect`, `KeyframeTransformEffect`, `KeyframeOpacityEffect`), multi-point **Speed Ramps** (`SpeedRampProvider`), deterministic PCM **SFX Synthesizer** (`Whoosh`, `Impact`, `Pop`, `Riser`, `Chime`), beat detection, voice enhancement, and `-14 dBFS` loudness normalization.
   - Hardware capability probing (`DeviceCapabilities`) for **720p / 1080p / 1440p QHD / 4K UHD**, `24–60 fps`, optional **H.265 (HEVC)**, audio bitrate selection, MP4 container verification, and `ExportDiagnostics`.
5. **Security, Privacy & Room Persistence (v2)**
   - API keys are encrypted on-device with **Android Keystore AES-256-GCM** (`KeyVault`) with zero plaintext fallback and masked logs/diagnostics.
   - Room SQLite database (`v2` non-destructive migration) with project thumbnails, missing-media detection + relinking, soft delete/restore, JSON import/export, and crash/render recovery.

## Quick Build & Run

See [`BUILD.md`](BUILD.md) for SDK, Gradle, test, and signed release build commands, and [`CHANGELOG.md`](CHANGELOG.md) for full release notes.

## Developer Attribution

Developed by **Angkur**.

All open-source third-party libraries (including AndroidX, Jetpack Compose, Media3, Room, and Material Symbols) remain subject to their respective upstream licenses and copyrights.
