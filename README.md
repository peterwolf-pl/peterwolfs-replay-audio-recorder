# PeterWolf's Replay Audio Recorder

Client-side Fabric mod for **Minecraft 26.2** that records the game's audio mix during ReplayMod or Flashback gameplay recording, plays it back in sync with the replay timeline, processes cuts/speed changes, and muxes the result into exported MP4 files.

## Important limitations (read first)

- Audio is recorded **only** when this mod is installed and active during the original gameplay session.
- **Existing replays do not contain recoverable original audio.**
- The track is the **original player/game-mix perspective**. Free-camera positions during replay do **not** change spatial mix, volume falloff, or left/right balance.
- This is **not** camera-relative spatial audio reconstruction (use a separate event-reconstruction mod for that).
- Does **not** capture microphone, OS loopback, browsers, or other applications.
- Voice chat recording is **disabled by default** (privacy).
- Final OpenAL mix may differ (no HRTF/reverb/occlusion from OpenAL Soft effects).
- Video is **copied** without re-encoding during mux; audio is encoded to **AAC** for MP4 compatibility.

## Requirements

| Component | Version |
|-----------|---------|
| Minecraft | 26.2 |
| Fabric Loader | ≥ 0.19.3 |
| Fabric API | 0.153.0+26.2 (or compatible) |
| Java | 25+ |
| ReplayMod (optional) | 26.2-2.6.27 verified |
| Flashback (optional) | 0.41.1 for MC26.2 verified |
| FFmpeg (recommended) | any recent build with libopus + aac |

The mod starts with **only ReplayMod**, **only Flashback**, **both**, or **neither**.

## Installation

1. Install Fabric Loader for Minecraft 26.2.
2. Install Fabric API.
3. Drop `peterwolfs-replay-audio-recorder-*.jar` into `.minecraft/mods/`.
4. Optionally install ReplayMod and/or Flashback.
5. Install FFmpeg on your PATH (or set a custom path in the config screen) for Opus encoding fallback and MP4 muxing.

## Usage

### Automatic recording

**Audio capture starts only when ReplayMod or Flashback starts a gameplay recording.**  
It does not start on world join, during replay playback, or during video export alone.

1. Start a ReplayMod or Flashback gameplay recording as usual.
2. This mod starts Opus audio capture at that moment (if auto-record is enabled in config).
3. Play the game; block/player/mob/UI/music sounds through the Minecraft sound engine are captured via the **shadow mixer**.
4. Stop the replay recording — audio finalizes to:

```text
.minecraft/replay_audio_recordings/
  replaymod/   or   flashback/
    <name>_<date>_<id>.replayaudio.ogg
    <name>_<date>_<id>.replayaudio.json
```

### Manual commands

```text
/replayaudiorecorder start
/replayaudiorecorder stop
/replayaudiorecorder pause
/replayaudiorecorder resume
/replayaudiorecorder status
/replayaudiorecorder openfolder
/replayaudiorecorder config
```

`start` is a diagnostic fallback and only works while ReplayMod/Flashback is already recording.  
Normal use is fully automatic when those mods begin a recording.

### Replay playback

When you open a matching replay, the mod:

1. Finds metadata by replay id / filename / integration (not only filename match).
2. Validates checksum when present.
3. Decodes Opus and follows the **replay timeline** (pause, seek, speed).
4. Shows status on the HUD.

**Original perspective warning** is shown in the config UI.

### MP4 export

After ReplayMod or Flashback finishes a video export:

1. Build an edit decision list from the export range / segments.
2. Trim/speed-transform the recorded Opus audio.
3. Mux with FFmpeg: **`-c:v copy -c:a aac`**.
4. Write `*_with_audio.mp4` under `replay_audio_recordings/exports/`.
5. Keep the original silent MP4 unless you disable that option.

If FFmpeg is missing, the silent MP4 and processed audio are kept, and a ready-to-copy FFmpeg command is logged.

## Configuration (F7 or `/replayaudiorecorder config`)

- Auto-record ReplayMod / Flashback
- Quality: Low 64 / Medium 96 / High 128 / **Very High 160 (default)** / Maximum 256 kbps
- Format: Ogg Opus (default); FLAC optional archival path
- Sound categories include/exclude
- Voice chat (off by default + privacy warning)
- Auto-mux after export, keep silent MP4, FFmpeg path, AAC bitrate (default 192 kbps)

## Audio format

- Container: Ogg  
- Codec: Opus  
- 48 kHz stereo VBR  
- Default ~160 kbps  

Encoder backends (in order):

1. Concentus pure-Java Opus (if on classpath)
2. FFmpeg `libopus`
3. Raw PCM fallback for crash recovery (not production default)

## Capture strategy

**Software shadow mixer** — duplicates decoded PCM associated with Minecraft sound playback and mixes a stereo stream with category gates and simple distance/pan.

Verified: LWJGL 3.4.1 provides `org.lwjgl.openal.SOFTLoopback` (used by Flashback during **export-time** device reopen). Continuous gameplay recording does **not** switch the live output device to loopback.

## Crash recovery

Incomplete `.replayaudio.json.partial` files are detected on startup. A recovery screen can repair metadata or move unrecoverable files to `orphaned/`. Recoverable audio is **never** auto-deleted.

## Development build

```bash
cd peterwolfs-replay-audio-recorder
./gradlew build test
# Dev client with optional replay mods:
./gradlew runClient -PwithReplayMods=true
```

Place JARs in `libs/`:

- `replaymod-26.2-2.6.27.jar`
- `Flashback-0.41.1-for-MC26.2.jar`

## Verified integration points

### ReplayMod 26.2-2.6.27

- `com.replaymod.recording.ReplayModRecording`
- `ConnectionEventHandler#getPacketListener`
- `PacketListener#getCurrentDuration`
- `com.replaymod.replay.ReplayModReplay#getReplayHandler`
- `ReplayHandler` / `ReplaySender#currentTimeStamp` (ms), `paused`, `getReplaySpeed`
- `com.replaymod.render.rendering.VideoRenderer`
- `com.replaymod.render.gui.GuiRenderingDone#videoFile`

### Flashback 0.41.1

- `com.moulberry.flashback.Flashback#RECORDER`, `#EXPORT_JOB`, `#isInReplay`, `#getReplayServer`
- `Recorder#isPaused`, `#finish`
- `FlashbackMeta#replayIdentifier`, `#totalTicks`
- `ReplayServer#getPartialReplayTick`, `#replayPaused`, `#getDesiredTickRate`
- `ExportJob` / `ExportSettings#startTick`, `#endTick`, `#output`

## License

MIT — see `LICENSE`.
