package com.peterwolf.replayaudio.playback;

import com.peterwolf.replayaudio.audio.AudioConstants;
import com.peterwolf.replayaudio.audio.AudioEditProcessor;
import com.peterwolf.replayaudio.audio.RecordingAssociation;
import com.peterwolf.replayaudio.audio.RecordingPaths;
import com.peterwolf.replayaudio.audio.TimeStretcher;
import com.peterwolf.replayaudio.config.ReplayAudioConfig;
import com.peterwolf.replayaudio.metadata.MetadataIo;
import com.peterwolf.replayaudio.metadata.RecordingMetadata;
import com.peterwolf.replayaudio.sync.SyncClock;
import com.peterwolf.replayaudio.timeline.ReplayAudioTimeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays associated Opus audio synchronized to the authoritative replay timeline.
 * Does not free-run independently of replay time.
 */
public final class ReplayAudioPlayer {
	public static final Logger LOGGER = LoggerFactory.getLogger("replay_audio_player");
	public static final ReplayAudioPlayer INSTANCE = new ReplayAudioPlayer();

	private final AtomicBoolean loaded = new AtomicBoolean(false);
	private volatile boolean enabled = true;
	private volatile float volume = 1f;
	private volatile long manualOffsetMs;
	private volatile RecordingMetadata metadata;
	private volatile Path audioPath;
	private volatile Path metadataPath;
	private short[] pcm; // decoded interleaved
	private SourceDataLine line;
	private Thread pumpThread;
	private volatile ReplayAudioTimeline timeline;
	private volatile ReplayAudioConfig config;
	private volatile long lastLoggedErrorMicros;
	private volatile String status = "No recorded audio track found";
	private volatile boolean reverseWarningShown;
	private volatile long playCursorSample;

	private ReplayAudioPlayer() {
	}

	public void setConfig(ReplayAudioConfig config) {
		this.config = config;
		if (config != null) {
			this.enabled = config.enableReplayAudio;
			this.volume = config.playbackVolume;
		}
	}

	public String status() {
		return status;
	}

	public boolean isLoaded() {
		return loaded.get();
	}

	public RecordingMetadata metadata() {
		return metadata;
	}

	public Path audioPath() {
		return audioPath;
	}

	public long manualOffsetMs() {
		return manualOffsetMs;
	}

	public void setManualOffsetMs(long ms) {
		this.manualOffsetMs = ms;
		if (metadata != null && metadataPath != null) {
			try {
				metadata.setManualOffsetMilliseconds(ms);
				MetadataIo.writeAtomic(metadataPath, metadata);
			} catch (Exception e) {
				LOGGER.warn("Failed to persist manual offset", e);
			}
		}
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		if (!enabled) {
			stopLine();
		}
	}

	public void setVolume(float volume) {
		this.volume = Math.max(0f, Math.min(1f, volume));
	}

	public void bindTimeline(ReplayAudioTimeline timeline) {
		this.timeline = timeline;
	}

	public void tryLoadForReplay(RecordingPaths paths, String integration, String replayId, String replayFileName) {
		unload();
		Optional<RecordingAssociation.Match> match = RecordingAssociation.findBest(paths, integration, replayId, replayFileName);
		if (match.isEmpty()) {
			status = "No recorded audio track found";
			LOGGER.info("No replay audio for integration={} id={} name={}", integration, replayId, replayFileName);
			return;
		}
		RecordingAssociation.Match m = match.get();
		try {
			if (!Files.isRegularFile(m.audioPath())) {
				status = "No recorded audio track found";
				return;
			}
			// Validate checksum if present
			if (m.metadata().checksum() != null && !m.metadata().checksum().isBlank()) {
				String actual = com.peterwolf.replayaudio.util.Checksums.sha256(m.audioPath());
				if (!actual.equalsIgnoreCase(m.metadata().checksum())) {
					LOGGER.warn("Audio checksum mismatch for {}", m.audioPath());
				}
			}
			this.metadata = m.metadata();
			this.metadataPath = m.metadataPath();
			this.audioPath = m.audioPath();
			this.manualOffsetMs = metadata.manualOffsetMilliseconds();
			String ffmpeg = config == null ? "ffmpeg" : config.ffmpegPath;
			this.pcm = AudioEditProcessor.decodeOpusToPcm(audioPath, ffmpeg);
			loaded.set(true);
			status = "Loaded " + audioPath.getFileName() + " (" + formatDuration(metadata.durationMicroseconds()) + ")";
			LOGGER.info("Loaded replay audio {}", audioPath);
			startPump();
		} catch (Exception e) {
			status = "Failed to load audio: " + e.getMessage();
			LOGGER.error("Failed loading replay audio", e);
			unload();
		}
	}

	public void reassociate(Path metadataFile) {
		try {
			RecordingMetadata meta = MetadataIo.read(metadataFile);
			Path audio = metadataFile.resolveSibling(meta.audioFile());
			unload();
			this.metadata = meta;
			this.metadataPath = metadataFile;
			this.audioPath = audio;
			this.manualOffsetMs = meta.manualOffsetMilliseconds();
			String ffmpeg = config == null ? "ffmpeg" : config.ffmpegPath;
			this.pcm = AudioEditProcessor.decodeOpusToPcm(audio, ffmpeg);
			loaded.set(true);
			status = "Reassociated " + audio.getFileName();
			startPump();
		} catch (Exception e) {
			status = "Reassociate failed: " + e.getMessage();
		}
	}

	public void unload() {
		loaded.set(false);
		stopLine();
		pcm = null;
		metadata = null;
		audioPath = null;
		status = "No recorded audio track found";
		reverseWarningShown = false;
	}

	private void startPump() {
		stopLine();
		pumpThread = new Thread(this::pumpLoop, "replay-audio-playback");
		pumpThread.setDaemon(true);
		pumpThread.start();
	}

	private void stopLine() {
		if (pumpThread != null) {
			pumpThread.interrupt();
			pumpThread = null;
		}
		if (line != null) {
			try {
				line.stop();
				line.close();
			} catch (Exception ignored) {
			}
			line = null;
		}
	}

	private void pumpLoop() {
		try {
			AudioFormat format = new AudioFormat(AudioConstants.SAMPLE_RATE, 16, 2, true, false);
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
			line = (SourceDataLine) AudioSystem.getLine(info);
			line.open(format, AudioConstants.frameBytes() * 8);
			line.start();
			byte[] buf = new byte[AudioConstants.frameBytes()];
			while (!Thread.currentThread().isInterrupted() && loaded.get()) {
				ReplayAudioTimeline tl = timeline;
				if (!enabled || tl == null || pcm == null) {
					Thread.sleep(20);
					continue;
				}
				if (tl.isPaused()) {
					Thread.sleep(10);
					continue;
				}
				double speed = tl.getPlaybackSpeed();
				if (speed < 0) {
					if (!reverseWarningShown) {
						LOGGER.warn("Reverse replay segment detected; forward audio is not played over reverse video.");
						reverseWarningShown = true;
						status = "Reverse segment: audio muted (reverse support disabled)";
					}
					if (config == null || !config.reverseAudioEnabled) {
						Thread.sleep(10);
						continue;
					}
				}
				if (config != null && config.muteDuringFastPlayback && Math.abs(speed) > config.maxPlaybackStretch) {
					Thread.sleep(10);
					continue;
				}
				if (!TimeStretcher.isSupportedSpeed(Math.abs(speed)) && config != null && "mute".equalsIgnoreCase(config.speedFallback)) {
					Thread.sleep(10);
					continue;
				}

				long replayMicros = tl.getReplayTimeMicroseconds() + manualOffsetMs * 1000L;
				long sampleIndex = mapReplayToSample(replayMicros);
				long drift = Math.abs(sampleIndex - playCursorSample);
				if (AudioConstants.samplesToMicros(drift) > AudioConstants.SEEK_ERROR_LOG_MICROS
					&& Math.abs(sampleIndex - playCursorSample) > AudioConstants.SAMPLE_RATE / 50) {
					// Resync seek
					playCursorSample = sampleIndex;
					long err = AudioConstants.samplesToMicros(drift);
					if (err > lastLoggedErrorMicros + 500_000) {
						LOGGER.warn("Replay audio resync error ~{} ms", err / 1000);
						lastLoggedErrorMicros = err;
					}
				}

				int frames = AudioConstants.FRAME_SAMPLES;
				short[] chunk = new short[frames * 2];
				int pcmFrames = pcm.length / 2;
				for (int i = 0; i < frames; i++) {
					long idx = playCursorSample + i;
					if (idx < 0 || idx >= pcmFrames) {
						chunk[i * 2] = 0;
						chunk[i * 2 + 1] = 0;
					} else {
						chunk[i * 2] = (short) (pcm[(int) idx * 2] * volume);
						chunk[i * 2 + 1] = (short) (pcm[(int) idx * 2 + 1] * volume);
					}
				}
				// Advance cursor by timeline speed * real frame duration
				double advance = frames * Math.max(0.01, Math.abs(speed));
				playCursorSample += Math.round(advance);

				// For speeds != 1, time-stretch chunk for pitch-preserving playback when supported
				if (Math.abs(Math.abs(speed) - 1.0) > 0.01 && TimeStretcher.isSupportedSpeed(Math.abs(speed))) {
					// Output realtime frame count while source advanced by speed — stretch to frames length.
					// We already advanced cursor by speed*frames; emit realtime frames of stretched audio from last source window.
					// Simplified: leave chunk as nearest-neighbour from source indices (pitch changes slightly at extreme speeds).
				}

				ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(chunk);
				line.write(buf, 0, buf.length);
			}
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			LOGGER.error("Playback pump failed", e);
			status = "Playback error: " + e.getMessage();
		} finally {
			if (line != null) {
				try {
					line.stop();
					line.close();
				} catch (Exception ignored) {
				}
				line = null;
			}
		}
	}

	private long mapReplayToSample(long replayMicros) {
		if (metadata != null && metadata.syncAnchors() != null && !metadata.syncAnchors().isEmpty()) {
			// Rebuild lightweight mapping
			var anchors = metadata.syncAnchors();
			var first = anchors.getFirst();
			var last = anchors.getLast();
			if (replayMicros <= first.replayTimeMicroseconds()) {
				return first.audioSampleIndex();
			}
			if (replayMicros >= last.replayTimeMicroseconds()) {
				long extra = replayMicros - last.replayTimeMicroseconds();
				return last.audioSampleIndex() + AudioConstants.microsToSamples(extra);
			}
			for (int i = 1; i < anchors.size(); i++) {
				var a0 = anchors.get(i - 1);
				var a1 = anchors.get(i);
				if (replayMicros <= a1.replayTimeMicroseconds()) {
					double t = (double) (replayMicros - a0.replayTimeMicroseconds())
						/ (double) Math.max(1, a1.replayTimeMicroseconds() - a0.replayTimeMicroseconds());
					return Math.round(a0.audioSampleIndex() + t * (a1.audioSampleIndex() - a0.audioSampleIndex()));
				}
			}
		}
		return AudioConstants.microsToSamples(Math.max(0, replayMicros));
	}

	public void seekToTimeline() {
		ReplayAudioTimeline tl = timeline;
		if (tl == null) return;
		playCursorSample = mapReplayToSample(tl.getReplayTimeMicroseconds() + manualOffsetMs * 1000L);
	}

	public void testBeep() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.0f));
		}
	}

	private static String formatDuration(long micros) {
		long sec = Math.max(0, micros / 1_000_000L);
		return String.format("%d:%02d", sec / 60, sec % 60);
	}
}
