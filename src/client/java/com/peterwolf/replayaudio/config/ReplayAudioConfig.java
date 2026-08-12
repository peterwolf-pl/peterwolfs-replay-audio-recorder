package com.peterwolf.replayaudio.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.peterwolf.replayaudio.audio.AudioFormatChoice;
import com.peterwolf.replayaudio.audio.RecordingQuality;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Client configuration for recording, playback, and export. */
public final class ReplayAudioConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("peterwolfs_replay_audio_recorder.json");

	public boolean autoRecordReplayMod = true;
	public boolean autoRecordFlashback = true;
	public String recordingQuality = RecordingQuality.VERY_HIGH.name();
	public String audioFormat = AudioFormatChoice.OPUS_OGG.name();
	public boolean recordVoiceChat = false;
	public boolean showRecordingIndicator = true;
	public boolean crashRecoveryEnabled = true;
	public int maxQueueFrames = 500;
	public int editFadeMilliseconds = 5;
	public double minPlaybackStretch = 0.25;
	public double maxPlaybackStretch = 4.0;
	public String speedFallback = "mute"; // mute | resample
	public boolean reverseAudioEnabled = false;
	public boolean muteDuringFastPlayback = true;
	public float playbackVolume = 1.0f;
	public boolean enableReplayAudio = true;
	public boolean autoMuxAfterExport = true;
	public boolean keepOriginalSilentMp4 = true;
	public boolean keepProcessedAudio = false;
	public String ffmpegPath = "ffmpeg";
	public boolean autoDetectFfmpeg = true;
	public int aacBitrate = 192_000;
	public Map<String, Boolean> audioCategories = defaultCategories();

	public static Map<String, Boolean> defaultCategories() {
		Map<String, Boolean> m = new LinkedHashMap<>();
		m.put("master", true);
		m.put("music", true);
		m.put("records", true);
		m.put("weather", true);
		m.put("blocks", true);
		m.put("hostile", true);
		m.put("neutral", true);
		m.put("players", true);
		m.put("ambient", true);
		m.put("voice", true);
		m.put("ui", true);
		return m;
	}

	public RecordingQuality quality() {
		return RecordingQuality.fromName(recordingQuality);
	}

	public AudioFormatChoice format() {
		return AudioFormatChoice.fromName(audioFormat);
	}

	public static ReplayAudioConfig load() {
		if (Files.isRegularFile(PATH)) {
			try {
				ReplayAudioConfig cfg = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), ReplayAudioConfig.class);
				if (cfg.audioCategories == null || cfg.audioCategories.isEmpty()) {
					cfg.audioCategories = defaultCategories();
				}
				return cfg;
			} catch (Exception ignored) {
			}
		}
		ReplayAudioConfig cfg = new ReplayAudioConfig();
		cfg.save();
		return cfg;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
		}
	}
}
