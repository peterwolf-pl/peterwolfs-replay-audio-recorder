package com.peterwolf.replayaudio.integration.flashback;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.Recorder;
import com.peterwolf.replayaudio.export.ExportPipeline;
import com.peterwolf.replayaudio.playback.ReplayAudioPlayer;
import com.peterwolf.replayaudio.recorder.RecordingController;
import com.peterwolf.replayaudio.timeline.AudioTimelineSegment;
import com.peterwolf.replayaudio.timeline.EditDecisionList;
import com.peterwolf.replayaudio.timeline.ReplayAudioTimeline;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Isolated Flashback adapter. Loaded only when mod id "flashback" is present.
 *
 * Audio capture starts only when Flashback begins a gameplay recording
 * (Flashback.RECORDER becomes non-null and readyToWrite) and stops when RECORDER is cleared.
 * Replay playback and video export do not start audio recording.
 */
public final class FlashbackIntegration {
	private static final Logger LOGGER = LoggerFactory.getLogger("replay_audio_flashback");
	private static Recorder lastRecorder;
	/** True only while we have an audio session linked to the current Flashback RECORDER. */
	private static boolean audioLinkedToFlashbackRecording;
	private static boolean waitingForReady;
	private static boolean wasInReplay;
	private static ExportJob lastExportJob;
	private static boolean exportWasRunning;
	private static boolean lastPaused;

	private FlashbackIntegration() {
	}

	public static boolean isLoaded() {
		return FabricLoader.getInstance().isModLoaded("flashback");
	}

	/** True when Flashback has an active gameplay recorder. */
	public static boolean isGameplayRecordingActive() {
		if (!isLoaded()) return false;
		try {
			Recorder rec = Flashback.RECORDER;
			return rec != null;
		} catch (Throwable t) {
			return false;
		}
	}

	public static void init() {
		if (!isLoaded()) return;
		LOGGER.info("Flashback integration enabled — audio starts only when Flashback starts recording");
	}

	public static void clientTick() {
		if (!isLoaded()) return;
		try {
			pollRecording();
			pollReplay();
			pollExport();
		} catch (Throwable t) {
			LOGGER.debug("Flashback poll error: {}", t.toString());
		}
	}

	/**
	 * Edge-triggered against Flashback.RECORDER.
	 * Does not start audio when merely in a world or replaying a Flashback file.
	 */
	private static void pollRecording() {
		Recorder rec = Flashback.RECORDER;

		if (rec != null && !audioLinkedToFlashbackRecording) {
			// Flashback started a recorder — wait until it can write before starting audio,
			// so we do not open a file before the recording is real.
			if (!rec.readyToWrite()) {
				if (!waitingForReady) {
					waitingForReady = true;
					LOGGER.debug("Flashback RECORDER present; waiting for readyToWrite before audio start");
				}
				lastRecorder = rec;
				return;
			}
			waitingForReady = false;
			lastRecorder = rec;
			audioLinkedToFlashbackRecording = true;
			lastPaused = rec.isPaused();

			String id = "fb-rec-" + System.identityHashCode(rec) + "-" + System.currentTimeMillis();
			String name = "flashback_recording";
			String ver = FabricLoader.getInstance().getModContainer("flashback")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
			LOGGER.info("Flashback recording detected — starting linked audio capture");
			RecordingController.INSTANCE.startAutomatic("flashback", id, name, "", ver);
			if (lastPaused) {
				RecordingController.INSTANCE.pause();
			}
			return;
		}

		if (rec == null && audioLinkedToFlashbackRecording) {
			LOGGER.info("Flashback recording ended — stopping linked audio capture");
			audioLinkedToFlashbackRecording = false;
			waitingForReady = false;
			lastRecorder = null;
			lastPaused = false;
			RecordingController.INSTANCE.stop();
			return;
		}

		if (rec != null && audioLinkedToFlashbackRecording) {
			lastRecorder = rec;
			boolean paused = rec.isPaused();
			if (paused != lastPaused) {
				lastPaused = paused;
				if (paused) {
					RecordingController.INSTANCE.pause();
				} else {
					RecordingController.INSTANCE.resume();
				}
			}
		}

		if (rec == null) {
			waitingForReady = false;
			lastRecorder = null;
		}
	}

	private static void pollReplay() {
		// Playback must never start audio recording.
		boolean inReplay = Flashback.isInReplay();
		if (inReplay && !wasInReplay) {
			wasInReplay = true;
			ReplayServer server = Flashback.getReplayServer();
			String id = "fb-unknown";
			String name = "flashback_replay";
			if (server != null) {
				FlashbackMeta meta = server.getMetadata();
				if (meta != null) {
					if (meta.replayIdentifier != null) {
						id = meta.replayIdentifier.toString();
					}
					if (meta.name != null && !meta.name.isBlank()) {
						name = meta.name;
					}
				}
			}
			ReplayAudioPlayer.INSTANCE.bindTimeline(new FlashbackTimeline());
			ReplayAudioPlayer.INSTANCE.tryLoadForReplay(
				RecordingController.INSTANCE.paths(), "flashback", id, name);
		} else if (!inReplay && wasInReplay) {
			wasInReplay = false;
			ReplayAudioPlayer.INSTANCE.unload();
		} else if (inReplay) {
			ReplayAudioPlayer.INSTANCE.bindTimeline(new FlashbackTimeline());
		}
	}

	private static void pollExport() {
		ExportJob job = Flashback.EXPORT_JOB;
		boolean running = job != null && job.isRunning();
		if (exportWasRunning && !running && lastExportJob != null) {
			try {
				ExportSettings settings = lastExportJob.getSettings();
				if (settings != null && settings.output() != null) {
					onExportCompleted(settings);
				}
			} catch (Exception e) {
				LOGGER.error("Flashback export completion handling failed", e);
			}
		}
		if (job != null) {
			lastExportJob = job;
		}
		exportWasRunning = running;
	}

	private static void onExportCompleted(ExportSettings settings) {
		if (RecordingController.INSTANCE.config() == null || !RecordingController.INSTANCE.config().autoMuxAfterExport) {
			return;
		}
		var player = ReplayAudioPlayer.INSTANCE;
		if (!player.isLoaded() || player.audioPath() == null) {
			LOGGER.info("Flashback export done without associated recorded audio: {}", settings.output());
			return;
		}
		int start = settings.startTick();
		int end = settings.endTick();
		long startMicros = start * 50_000L;
		long endMicros = end * 50_000L;
		EditDecisionList edl = EditDecisionList.singleRange(startMicros, endMicros,
			RecordingController.INSTANCE.config().editFadeMilliseconds);

		Path exports = RecordingController.INSTANCE.paths().exports();
		ExportPipeline.ExportResult result = ExportPipeline.run(new ExportPipeline.ExportRequest(
			player.audioPath(),
			player.metadata(),
			edl,
			settings.output(),
			exports,
			RecordingController.INSTANCE.config().ffmpegPath,
			RecordingController.INSTANCE.config().aacBitrate,
			RecordingController.INSTANCE.config().keepOriginalSilentMp4,
			RecordingController.INSTANCE.config().keepProcessedAudio
		));
		LOGGER.info("Flashback export audio: success={} msg={}", result.success(), result.message());
	}

	public static final class FlashbackTimeline implements ReplayAudioTimeline {
		@Override
		public long getReplayTimeMicroseconds() {
			ReplayServer server = Flashback.getReplayServer();
			if (server == null) return 0;
			return Math.round(server.getPartialReplayTick() * 50_000L);
		}

		@Override
		public boolean isPaused() {
			ReplayServer server = Flashback.getReplayServer();
			return server == null || server.replayPaused;
		}

		@Override
		public double getPlaybackSpeed() {
			ReplayServer server = Flashback.getReplayServer();
			if (server == null) return 1.0;
			float rate = server.getDesiredTickRate(true);
			return rate / 20.0;
		}

		@Override
		public List<AudioTimelineSegment> getExportSegments() {
			List<AudioTimelineSegment> list = new ArrayList<>();
			ReplayServer server = Flashback.getReplayServer();
			if (server == null) return list;
			int total = server.getTotalReplayTicks();
			list.add(new AudioTimelineSegment(0, total * 50_000L, 0, 1.0));
			return list;
		}

		@Override
		public Optional<String> getReplayIdentifier() {
			ReplayServer server = Flashback.getReplayServer();
			if (server == null || server.getMetadata() == null || server.getMetadata().replayIdentifier == null) {
				return Optional.empty();
			}
			return Optional.of(server.getMetadata().replayIdentifier.toString());
		}

		@Override
		public long getReplayDurationMicroseconds() {
			ReplayServer server = Flashback.getReplayServer();
			return server == null ? -1 : server.getTotalReplayTicks() * 50_000L;
		}
	}
}
