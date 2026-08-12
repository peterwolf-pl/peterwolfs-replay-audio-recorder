# Changelog

All notable changes to PeterWolf's Replay Audio Recorder are documented in this file.

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
