package com.peterwolf.replayaudio.recorder;

import com.peterwolf.replayaudio.audio.RecordingPaths;
import com.peterwolf.replayaudio.audio.RecordingSession;
import com.peterwolf.replayaudio.config.ReplayAudioConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

/** Client-thread controller for automatic and manual recordings. */
public final class RecordingController {
	public static final Logger LOGGER = LoggerFactory.getLogger("replay_audio_recorder");
	public static final RecordingController INSTANCE = new RecordingController();

	private volatile RecordingSession session;
	private ReplayAudioConfig config;
	private RecordingPaths paths;

	private RecordingController() {
	}

	public void init(ReplayAudioConfig config) {
		this.config = config;
		Minecraft mc = Minecraft.getInstance();
		this.paths = new RecordingPaths(mc.gameDirectory.toPath());
		try {
			paths.ensureLayout();
		} catch (Exception e) {
			LOGGER.error("Failed to create recording directories", e);
		}
		ShadowAudioMixer.INSTANCE.setController(this);
		ShadowAudioMixer.INSTANCE.setConfig(config);
	}

	public RecordingPaths paths() {
		return paths;
	}

	public ReplayAudioConfig config() {
		return config;
	}

	public RecordingSession session() {
		return session;
	}

	public boolean isRecordingPcm() {
		RecordingSession s = session;
		return s != null && s.state() == RecordingSession.State.RECORDING;
	}

	public boolean isActive() {
		RecordingSession s = session;
		return s != null && s.isActive();
	}

	public synchronized boolean startAutomatic(String integration, String replayId, String replayName, String worldId, String replayModVersion) {
		if (session != null && session.isActive()) {
			if (session.isManual()) {
				LOGGER.warn("Automatic recording not started: manual recording is active");
				return false;
			}
			LOGGER.warn("Automatic recording not started: already recording");
			return false;
		}
		if ("replaymod".equals(integration) && !config.autoRecordReplayMod) return false;
		if ("flashback".equals(integration) && !config.autoRecordFlashback) return false;
		return startInternal(integration, replayId, replayName, worldId, replayModVersion, false);
	}

	/**
	 * Manual start is a diagnostic fallback only. Audio recording is intended to start
	 * exclusively when ReplayMod or Flashback begins a gameplay recording.
	 * Freestanding manual capture (without an active replay-system recording) is refused.
	 */
	public synchronized boolean startManual() {
		if (session != null && session.isActive()) {
			toast("Already recording.");
			return false;
		}
		boolean rmActive = isReplayModRecordingActive();
		boolean fbActive = isFlashbackRecordingActive();
		if (!rmActive && !fbActive) {
			toast("Audio starts only when ReplayMod or Flashback starts recording.");
			LOGGER.info("Manual start refused: no active ReplayMod/Flashback gameplay recording");
			return false;
		}
		if (rmActive) {
			return startInternal("replaymod", "rm-manual-" + System.currentTimeMillis(), "replaymod_recording", "", "", false);
		}
		return startInternal("flashback", "fb-manual-" + System.currentTimeMillis(), "flashback_recording", "", "", false);
	}

	private static boolean isReplayModRecordingActive() {
		try {
			if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("replaymod")) return false;
			Class<?> c = Class.forName("com.peterwolf.replayaudio.integration.replaymod.ReplayModIntegration");
			return (Boolean) c.getMethod("isGameplayRecordingActive").invoke(null);
		} catch (Throwable t) {
			return false;
		}
	}

	private static boolean isFlashbackRecordingActive() {
		try {
			if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("flashback")) return false;
			Class<?> c = Class.forName("com.peterwolf.replayaudio.integration.flashback.FlashbackIntegration");
			return (Boolean) c.getMethod("isGameplayRecordingActive").invoke(null);
		} catch (Throwable t) {
			return false;
		}
	}

	private boolean startInternal(String integration, String replayId, String replayName, String worldId, String replayModVersion, boolean manual) {
		try {
			paths.ensureLayout();
			RecordingSession s = new RecordingSession(Math.max(64, config.maxQueueFrames));
			Path dir = paths.forIntegration(integration);
			Map<String, Boolean> cats = config.audioCategories;
			String mcVer = Minecraft.getInstance().getLaunchedVersion();
			s.start(dir, integration, replayId, replayName, config.quality(), config.format(), cats, manual, mcVer, replayModVersion, worldId);
			session = s;
			LOGGER.info("Started {} audio recording ({}) -> {}", manual ? "manual" : "automatic", integration, s.audioPath());
			toast("Replay audio recording started (" + integration + ")");
			return true;
		} catch (Exception e) {
			LOGGER.error("Failed to start audio recording", e);
			toast("Failed to start audio recording: " + e.getMessage());
			return false;
		}
	}

	public synchronized void stop() {
		RecordingSession s = session;
		if (s == null || (!s.isActive() && s.state() != RecordingSession.State.STOPPING)) {
			return;
		}
		try {
			s.stop();
			LOGGER.info("Stopped audio recording: {}", s.metadataPath());
			toast("Replay audio recording saved");
		} catch (Exception e) {
			LOGGER.error("Failed to stop audio recording", e);
			s.fail("stop failed", e);
			toast("Failed to stop audio recording: " + e.getMessage());
		}
	}

	public synchronized void pause() {
		if (session != null) session.pause();
	}

	public synchronized void resume() {
		if (session != null) session.resume();
	}

	public void offerCapturedPcm(short[] interleaved, int frames) {
		RecordingSession s = session;
		if (s != null) {
			s.offerPcm(interleaved, frames);
		}
	}

	public void noteEvent(String reason) {
		RecordingSession s = session;
		if (s != null && s.isActive()) {
			s.noteEvent(reason);
		}
	}

	public String statusText() {
		RecordingSession s = session;
		if (s == null) return "Idle";
		return s.state() + " samples=" + s.metadata().totalSamples()
			+ " dropped=" + s.droppedSamples()
			+ (s.audioPath() == null ? "" : (" file=" + s.audioPath().getFileName()));
	}

	private static void toast(String msg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("[ReplayAudio] " + msg));
		}
	}
}
