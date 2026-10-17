# Changelog

All notable changes to PeterWolf's Replay Audio Recorder are documented in this file.

## 1.0.2+26.3 — 2026-09-24

### Changed

- Ported to Minecraft 26.3 (Wilderness Bound).
- Updated Fabric Loader to `>=0.19.5`, Fabric API to `0.160.7+26.3`, Loom to `1.17-SNAPSHOT`, and Gradle wrapper to `9.5.1`.
- Updated key mapping type to `InputConstants.Type.KEYBOARD` for MC 26.3 Mojmap API changes.
- Updated mixin compatibility level to `JAVA_25`.

## 1.0.1+26.2 — 2026-08-13

### Fixed / behaviour

- **Audio capture now starts only when ReplayMod or Flashback starts a gameplay recording.**
  - Rising-edge detection on ReplayMod `PacketListener` / Flashback `RECORDER` (waits for `readyToWrite()`).
  - Opening a world, watching a replay, or exporting video does **not** start audio by itself.
- **Stop / pause follow the replay system recording lifecycle** (ReplayMod `GuiRecordingControls` pause/stop; Flashback `Recorder.isPaused()`).
- **Manual `/replayaudiorecorder start`** no longer creates freestanding recordings; it only works while ReplayMod or Flashback is already recording (diagnostic fallback).

### Notes

- Default quality remains Ogg Opus 48 kHz stereo @ 160 kbps.
- MP4 mux after export is unchanged (`-c:v copy -c:a aac`).

## 1.0.0+26.2 — 2026-08-06

### Added

- Initial client-only Fabric release for Minecraft 26.2.
- Optional ReplayMod and Flashback integrations (no hard dependency).
- Ogg Opus recording, JSON sync metadata, crash recovery, timeline playback, EDL export, FFmpeg mux.
