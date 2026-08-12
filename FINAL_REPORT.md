# Final Report — PeterWolf's Replay Audio Recorder 1.0.0+26.2

## 1–7. Target stack

| # | Item | Value |
|---|------|-------|
| 1 | Minecraft | **26.2** |
| 2 | Fabric Loader | **0.19.3** |
| 3 | Fabric API | **0.153.0+26.2** |
| 4 | Java | **25** |
| 5 | ReplayMod | **26.2-2.6.27** (verified JAR) |
| 6 | Flashback | **0.41.1** for MC26.2 (verified JAR) |
| 7 | LWJGL / OpenAL | **LWJGL 3.4.1**, OpenAL Soft (`org.lwjgl.openal.SOFTLoopback` present) |

## 8. Verified ReplayMod integration

- `com.replaymod.recording.ReplayModRecording` / `ConnectionEventHandler#getPacketListener`
- `com.replaymod.recording.packet.PacketListener#getCurrentDuration`
- `com.replaymod.replay.ReplayModReplay#getReplayHandler`
- `ReplayHandler` / `ReplaySender#currentTimeStamp` (ms), `#paused`, `#getReplaySpeed`
- `ReplayMetaData#getDate`, `#getDuration`
- `com.replaymod.render.gui.GuiRenderingDone#videoFile`
- `com.replaymod.render.rendering.VideoRenderer` / `RenderSettings#getOutputFile`

Adapter: `integration/replaymod/ReplayModIntegration.java` (reflective load only if mod present).

## 9. Verified Flashback integration

- `com.moulberry.flashback.Flashback#RECORDER`, `#EXPORT_JOB`, `#isInReplay`, `#getReplayServer`
- `Recorder#isPaused`, `#finish`
- `FlashbackMeta#replayIdentifier`, `#name`, `#totalTicks`
- `ReplayServer#getPartialReplayTick`, `#replayPaused`, `#getDesiredTickRate`, `#getTotalReplayTicks`
- `ExportJob#isRunning`, `#getSettings`
- `ExportSettings#startTick`, `#endTick`, `#output`, `#recordAudio`

Adapter: `integration/flashback/FlashbackIntegration.java`.

## 10–11. Capture strategy & mix differences

**Strategy:** software shadow mixer — PCM duplicated from `Channel.attachStaticBuffer(SoundBuffer)` + continuous mix on sound/client tick.

**Not used for continuous gameplay:** OpenAL Soft loopback device reopen (disrupts real speakers). Flashback’s export-time loopback is separate.

**Differences from final OpenAL mix:** no HRTF, reverb, occlusion, native Soft filters; other mods writing OpenAL directly may be missing; voice chat only if Minecraft-routed and user-enabled.

## 12. Codec

Ogg **Opus**, 48 kHz stereo, default **160 kbps** (Very High). Qualities: 64/96/128/160/256 kbps.  
Encoder backends: Concentus (if present) → FFmpeg `libopus` → raw PCM recovery fallback.

## 13–15. Association, timeline, drift

- Sidecars: `.replayaudio.ogg` + `.replayaudio.json` under `replay_audio_recordings/{replaymod,flashback,manual,orphaned,exports,temporary}/`
- Association scores: replay UUID/id, integration, filename, completeness (not filename-only)
- Monotonic `SyncClock` + periodic/event anchors (start, pause, resume, periodic, stop, dimension, etc.)
- Playback maps replay µs → sample index via linear interpolation between anchors
- Drift estimate from anchor sample vs ideal 48 kHz progression

## 16–18. Trimming, speed, mux

- `EditDecisionList` + `AudioEditProcessor` for ranges, cuts, speed, reverse detection, 3–10 ms fades
- WSOLA-style pitch-preserving stretch 0.25×–4.0×; outside range: mute or resample (config)
- `FfmpegMuxer` / `ExportPipeline`: video **copy**, audio **AAC 192k**, atomic tmp → `*_with_audio.mp4`

## 19. Created files

Project root: `peterwolfs-replay-audio-recorder/` — 46 Java sources + Gradle + README + docs.  
See `src/` tree (main pure engine, client integrations/mixins/GUI/commands, tests).

## 20. Automated tests

**25 tests, 0 failures, 0 errors** (JUnit 5):

- OggOpusEncoder, RecordingSession, FileSafety/CrashRecovery/Association
- SyncClock, EditDecisionList, MetadataIo
- FfmpegMuxer (including real MP4 mux when FFmpeg available)

## 21. Development client

`./gradlew runClient -PwithReplayMods=true` — **SUCCESS**

Log confirms:

```
PeterWolf's Replay Audio Recorder initializing (client-only)
ReplayMod integration enabled (replaymod present)
Flashback integration enabled (flashback present)
Replay Audio Recorder ready. ReplayMod=true Flashback=true
```

Mods loaded together: `peterwolfs_replay_audio_recorder`, `replaymod 26.2-2.6.27`, `flashback 0.41.1`. Clean exit.

## 22–23. Manual ReplayMod / Flashback gameplay validation

**Not fully automated in this run.** Full end-to-end (record → sounds → stop → open replay → seek/speed → export MP4) requires interactive UI session. Integration **load + polling hooks compile and initialize** with both mods. Manual checklist remains in the task description.

## 24. Known limitations

- Original game-mix perspective only (not free-camera spatial reconstruction)
- Shadow mixer ≠ bit-perfect OpenAL Soft output
- Reverse audio: muted by default (warning); optional reverse not fully production-tested
- FLAC option records Opus with a metadata note (Opus remains production default)
- Concentus not bundled by default (FFmpeg path used when available)
- ReplayMod multi-cut path editor EDL is simplified to full-duration / export range unless more timeline API is exposed
- Full interactive ReplayMod/Flashback export mux not exercised beyond unit FFmpeg mux tests

## 25–28. Locations

| Artifact | Path |
|----------|------|
| Mod JAR | `build/libs/peterwolfs-replay-audio-recorder-1.0.0+26.2.jar` |
| Sources JAR | `build/libs/peterwolfs-replay-audio-recorder-1.0.0+26.2-sources.jar` |
| Recordings | `.minecraft/replay_audio_recordings/` (dev: `run/replay_audio_recordings/`) |
| Metadata | alongside `.replayaudio.ogg` as `.replayaudio.json` |
| Muxed MP4 | `replay_audio_recordings/exports/*_with_audio.mp4` |

## 29. Install

1. Fabric Loader 26.2 + Fabric API  
2. Drop the mod JAR into `mods/`  
3. Optional: ReplayMod and/or Flashback  
4. Install FFmpeg on PATH for best encoding/muxing  

## 30. Usage

1. Enable auto-record in F7 config (or `/replayaudiorecorder config`)  
2. Start ReplayMod/Flashback recording → audio starts  
3. Stop recording → `.replayaudio.ogg` + `.json` written  
4. Open replay → audio loads and follows timeline  
5. Export video → auto-mux to `*_with_audio.mp4` when FFmpeg available  

Commands: `start|stop|pause|resume|status|openfolder|config`
