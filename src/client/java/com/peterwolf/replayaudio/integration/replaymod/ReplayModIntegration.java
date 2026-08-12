package com.peterwolf.replayaudio.integration.replaymod;

import com.peterwolf.replayaudio.export.ExportPipeline;
import com.peterwolf.replayaudio.playback.ReplayAudioPlayer;
import com.peterwolf.replayaudio.recorder.RecordingController;
import com.peterwolf.replayaudio.timeline.AudioTimelineSegment;
import com.peterwolf.replayaudio.timeline.EditDecisionList;
import com.peterwolf.replayaudio.timeline.ReplayAudioTimeline;
import com.replaymod.recording.ReplayModRecording;
import com.replaymod.recording.gui.GuiRecordingControls;
import com.replaymod.recording.handler.ConnectionEventHandler;
import com.replaymod.recording.packet.PacketListener;
import com.replaymod.render.gui.GuiRenderingDone;
import com.replaymod.render.rendering.VideoRenderer;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.ReplaySender;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ReplayMetaData;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Isolated ReplayMod adapter. Loaded only when mod id "replaymod" is present.
 *
 * Audio capture starts only when ReplayMod begins a gameplay recording
 * (PacketListener becomes active) and stops when that recording ends.
 * Opening a world, replaying, or exporting does not start audio by itself.
 */
public final class ReplayModIntegration {
	private static final Logger LOGGER = LoggerFactory.getLogger("replay_audio_replaymod");
	private static final AtomicReference<PacketListener> lastListener = new AtomicReference<>();
	/** True only while ReplayMod is actively recording gameplay AND we started audio for it. */
	private static boolean audioLinkedToReplayModRecording;
	private static boolean lastPaused;
	private static ReplayHandler lastHandler;
	private static Path lastExportVideo;
	private static VideoRenderer lastRenderer;

	private ReplayModIntegration() {
	}

	public static boolean isLoaded() {
		return FabricLoader.getInstance().isModLoaded("replaymod");
	}

	/** True when ReplayMod has an active (non-stopped) gameplay recording session. */
	public static boolean isGameplayRecordingActive() {
		if (!isLoaded()) return false;
		try {
			PacketListener listener = currentPacketListener();
			if (listener == null) return false;
			GuiRecordingControls controls = currentGuiControls();
			if (controls != null && controls.isStopped()) return false;
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	public static void init() {
		if (!isLoaded()) return;
		LOGGER.info("ReplayMod integration enabled — audio starts only when ReplayMod starts recording");
	}

	public static void clientTick() {
		if (!isLoaded()) return;
		try {
			pollRecordingState();
			pollReplayPlayback();
			pollExportDone();
		} catch (Throwable t) {
			LOGGER.debug("ReplayMod poll error: {}", t.toString());
		}
	}

	private static PacketListener currentPacketListener() {
		ReplayModRecording rec = ReplayModRecording.instance;
		if (rec == null) return null;
		ConnectionEventHandler conn = rec.getConnectionEventHandler();
		if (conn == null) return null;
		return conn.getPacketListener();
	}

	private static GuiRecordingControls currentGuiControls() {
		try {
			ReplayModRecording rec = ReplayModRecording.instance;
			if (rec == null) return null;
			ConnectionEventHandler conn = rec.getConnectionEventHandler();
			if (conn == null) return null;
			Field f = ConnectionEventHandler.class.getDeclaredField("guiControls");
			f.setAccessible(true);
			Object v = f.get(conn);
			return v instanceof GuiRecordingControls c ? c : null;
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Edge-triggered: start audio only on transition into an active ReplayMod recording;
	 * stop on transition out. Never starts on tick merely because ReplayMod is installed.
	 */
	private static void pollRecordingState() {
		PacketListener listener = currentPacketListener();
		PacketListener prev = lastListener.get();
		GuiRecordingControls controls = currentGuiControls();
		boolean rmRecordingActive = listener != null && (controls == null || !controls.isStopped());

		if (rmRecordingActive && !audioLinkedToReplayModRecording) {
			// Rising edge: ReplayMod just started (or resumed after full stop) a recording.
			lastListener.set(listener);
			audioLinkedToReplayModRecording = true;
			lastPaused = controls != null && controls.isPaused();

			String replayId = "rm-" + System.identityHashCode(listener) + "-" + System.currentTimeMillis();
			String name = "replaymod_recording";
			try {
				Optional<Path> out = tryGetOutputPath(listener);
				if (out.isPresent()) {
					String fileName = out.get().getFileName().toString();
					int dot = fileName.lastIndexOf('.');
					name = dot > 0 ? fileName.substring(0, dot) : fileName;
				}
			} catch (Exception ignored) {
			}
			String ver = FabricLoader.getInstance().getModContainer("replaymod")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
			LOGGER.info("ReplayMod recording detected — starting linked audio capture");
			RecordingController.INSTANCE.startAutomatic("replaymod", replayId, name, "", ver);
			if (lastPaused) {
				RecordingController.INSTANCE.pause();
			}
			return;
		}

		if (!rmRecordingActive && audioLinkedToReplayModRecording) {
			// Falling edge: ReplayMod stopped / disconnected recording.
			LOGGER.info("ReplayMod recording ended — stopping linked audio capture");
			audioLinkedToReplayModRecording = false;
			lastListener.set(null);
			lastPaused = false;
			RecordingController.INSTANCE.stop();
			return;
		}

		if (rmRecordingActive && audioLinkedToReplayModRecording) {
			lastListener.set(listener);
			// Follow ReplayMod pause/resume without starting a new session.
			boolean paused = controls != null && controls.isPaused();
			if (paused != lastPaused) {
				lastPaused = paused;
				if (paused) {
					RecordingController.INSTANCE.pause();
				} else {
					RecordingController.INSTANCE.resume();
				}
			}
		}
	}

	private static void pollReplayPlayback() {
		// Playback must never start audio recording.
		ReplayModReplay mod = ReplayModReplay.instance;
		if (mod == null) return;
		ReplayHandler handler = mod.getReplayHandler();
		if (handler != lastHandler) {
			lastHandler = handler;
			if (handler != null) {
				String id = "unknown";
				String name = "replay";
				try {
					ReplayFile rf = handler.getReplayFile();
					ReplayMetaData meta = rf.getMetaData();
					id = "rm-" + meta.getDate() + "-" + meta.getDuration();
					name = "replay_" + meta.getDate();
				} catch (Exception e) {
					LOGGER.debug("Could not read replay metadata", e);
				}
				ReplayAudioPlayer.INSTANCE.bindTimeline(new ReplayModTimeline(handler));
				ReplayAudioPlayer.INSTANCE.tryLoadForReplay(
					RecordingController.INSTANCE.paths(), "replaymod", id, name);
			} else {
				ReplayAudioPlayer.INSTANCE.unload();
			}
		} else if (handler != null) {
			ReplayAudioPlayer.INSTANCE.bindTimeline(new ReplayModTimeline(handler));
		}
	}

	private static void pollExportDone() {
		Minecraft mc = Minecraft.getInstance();
		Screen vanilla = mc.gui.screen();
		if (vanilla == null) return;
		try {
			var wrapped = com.replaymod.lib.de.johni0702.minecraft.gui.container.GuiScreen.from(vanilla);
			if (wrapped instanceof GuiRenderingDone done) {
				File video = done.videoFile;
				if (video != null) {
					Path path = video.toPath();
					if (!path.equals(lastExportVideo)) {
						lastExportVideo = path;
						onExportCompleted(path, done.videoFrames);
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	public static void onVideoRendererFinished(VideoRenderer renderer) {
		lastRenderer = renderer;
		try {
			if (renderer.getRenderSettings() != null && renderer.getRenderSettings().getOutputFile() != null) {
				onExportCompleted(renderer.getRenderSettings().getOutputFile().toPath(), renderer.getTotalFrames());
			}
		} catch (Throwable t) {
			LOGGER.warn("VideoRenderer finish hook failed", t);
		}
	}

	private static void onExportCompleted(Path video, int frames) {
		if (RecordingController.INSTANCE.config() == null || !RecordingController.INSTANCE.config().autoMuxAfterExport) {
			return;
		}
		var player = ReplayAudioPlayer.INSTANCE;
		if (!player.isLoaded() || player.audioPath() == null) {
			LOGGER.info("Export completed but no associated audio to mux: {}", video);
			return;
		}
		try {
			long durationMicros = frames > 0
				? (frames * 1_000_000L / Math.max(1, guessFps()))
				: player.metadata().durationMicroseconds();
			ReplayAudioTimeline tl = new ReplayModTimeline(lastHandler);
			List<AudioTimelineSegment> segs = tl.getExportSegments();
			EditDecisionList edl;
			if (segs.isEmpty()) {
				edl = EditDecisionList.singleRange(0, durationMicros, RecordingController.INSTANCE.config().editFadeMilliseconds);
			} else {
				edl = EditDecisionList.normalize(segs, RecordingController.INSTANCE.config().reverseAudioEnabled,
					RecordingController.INSTANCE.config().editFadeMilliseconds);
			}
			Path exports = RecordingController.INSTANCE.paths().exports();
			ExportPipeline.ExportResult result = ExportPipeline.run(new ExportPipeline.ExportRequest(
				player.audioPath(),
				player.metadata(),
				edl,
				video,
				exports,
				RecordingController.INSTANCE.config().ffmpegPath,
				RecordingController.INSTANCE.config().aacBitrate,
				RecordingController.INSTANCE.config().keepOriginalSilentMp4,
				RecordingController.INSTANCE.config().keepProcessedAudio
			));
			LOGGER.info("ReplayMod export audio: success={} msg={}", result.success(), result.message());
		} catch (Exception e) {
			LOGGER.error("Failed muxing ReplayMod export", e);
		}
	}

	private static int guessFps() {
		return 60;
	}

	public static final class ReplayModTimeline implements ReplayAudioTimeline {
		private final ReplayHandler handler;

		public ReplayModTimeline(ReplayHandler handler) {
			this.handler = handler;
		}

		@Override
		public long getReplayTimeMicroseconds() {
			if (handler == null) return 0;
			ReplaySender sender = handler.getReplaySender();
			return sender.currentTimeStamp() * 1000L;
		}

		@Override
		public boolean isPaused() {
			return handler == null || handler.getReplaySender().paused();
		}

		@Override
		public double getPlaybackSpeed() {
			return handler == null ? 1.0 : handler.getReplaySender().getReplaySpeed();
		}

		@Override
		public List<AudioTimelineSegment> getExportSegments() {
			List<AudioTimelineSegment> list = new ArrayList<>();
			if (handler == null) return list;
			long dur = handler.getReplayDuration() * 1000L;
			list.add(new AudioTimelineSegment(0, dur, 0, 1.0));
			return list;
		}

		@Override
		public Optional<String> getReplayIdentifier() {
			try {
				if (handler == null) return Optional.empty();
				ReplayMetaData meta = handler.getReplayFile().getMetaData();
				return Optional.of("rm-" + meta.getDate() + "-" + meta.getDuration());
			} catch (Exception e) {
				return Optional.empty();
			}
		}

		@Override
		public long getReplayDurationMicroseconds() {
			return handler == null ? -1 : handler.getReplayDuration() * 1000L;
		}
	}

	public static Optional<Path> tryGetOutputPath(PacketListener listener) {
		try {
			Field f = PacketListener.class.getDeclaredField("outputPath");
			f.setAccessible(true);
			Object v = f.get(listener);
			if (v instanceof Path p) return Optional.of(p);
		} catch (Exception ignored) {
		}
		return Optional.empty();
	}
}
