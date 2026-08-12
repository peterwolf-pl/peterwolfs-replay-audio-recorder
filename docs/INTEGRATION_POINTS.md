# Verified Integration Points

Inspected JARs (project `libs/`):

- `replaymod-26.2-2.6.27.jar` (id `replaymod`, version `26.2-2.6.27`)
- `Flashback-0.41.1-for-MC26.2.jar` (id `flashback`, version `0.41.1`)

Target stack (from sibling Fabric projects and gradle.properties):

- Minecraft **26.2**
- Fabric Loader **0.19.3**
- Fabric API **0.153.0+26.2**
- Java **25**
- Loom **1.16**
- LWJGL OpenAL **3.4.1** (`org.lwjgl.openal.SOFTLoopback` present)

## ReplayMod

| Area | Class / member | Role |
|------|----------------|------|
| Recording module | `com.replaymod.recording.ReplayModRecording.instance` | Module singleton |
| Connection | `ConnectionEventHandler#getPacketListener()` | Active recording listener |
| Packet stream | `PacketListener#getCurrentDuration()` | Duration while recording |
| Stop | `PacketListener#channelInactive` lifecycle | Recording end (listener null) |
| Playback | `com.replaymod.replay.ReplayModReplay.instance` | Playback module |
| Handler | `ReplayHandler#getReplaySender()`, `#getReplayFile()`, `#getReplayDuration()` | Timeline |
| Sender | `ReplaySender#currentTimeStamp()` **milliseconds**, `#paused()`, `#getReplaySpeed()`, `#jumpToTime` | Clock |
| Metadata | `ReplayMetaData#getDate()`, `#getDuration()`, `#getServerName()` | Association |
| Export | `com.replaymod.render.rendering.VideoRenderer` | Render lifecycle |
| Export settings | `RenderSettings#getOutputFile()`, `#getFramesPerSecond()` | Output path |
| Export done UI | `com.replaymod.render.gui.GuiRenderingDone#videoFile` | Completion |

Adapter: `com.peterwolf.replayaudio.integration.replaymod.ReplayModIntegration`  
Loaded only when `FabricLoader.isModLoaded("replaymod")` via `Class.forName`.

## Flashback

| Area | Class / member | Role |
|------|----------------|------|
| Recorder | `Flashback.RECORDER` | Non-null while recording |
| Control | `Flashback.startRecordingReplay/finish/pause/cancel` | Lifecycle |
| Meta | `FlashbackMeta.replayIdentifier`, `.name`, `.totalTicks` | Association |
| Playback | `Flashback.isInReplay()`, `#getReplayServer()` | Replay open |
| Server | `ReplayServer#getPartialReplayTick()`, `#replayPaused`, `#getDesiredTickRate`, `#getTotalReplayTicks` | Timeline (ticks × 50 000 µs) |
| Export | `Flashback.EXPORT_JOB`, `ExportJob#isRunning()`, `#getSettings()` | Export lifecycle |
| Settings | `ExportSettings#startTick()`, `#endTick()`, `#output()`, `#recordAudio()` | Range + path |

Adapter: `com.peterwolf.replayaudio.integration.flashback.FlashbackIntegration`  
Loaded only when `FabricLoader.isModLoaded("flashback")`.

## Audio capture

**Strategy implemented:** software **shadow mixer**

1. Mixin `Channel#attachStaticBuffer(SoundBuffer)` duplicates decoded PCM.
2. `SoundEngine#tick` / client tick advances the mix at 48 kHz stereo.
3. Category gates via `SoundSource` map in config.

**Not used for continuous play:** OpenAL Soft loopback device reopen (would replace real output).  
Flashback uses `SOFTLoopback` during **export** when `recordAudio()` is true — orthogonal to this mod’s gameplay recording.

**Differences from final OpenAL mix:** no HRTF, reverb, occlusion, native filters; voice chat only if routed through Minecraft categories and enabled; direct OpenAL from other mods may be missing.
