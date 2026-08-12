package com.peterwolf.replayaudio.audio;

import com.peterwolf.replayaudio.metadata.MetadataIo;
import com.peterwolf.replayaudio.metadata.RecordingMetadata;
import com.peterwolf.replayaudio.sync.SyncClock;
import com.peterwolf.replayaudio.util.Checksums;
import com.peterwolf.replayaudio.util.FileNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns capture→encode pipeline for one recording. Thread-safe for PCM offers from audio thread
 * and control from the Minecraft client thread.
 */
public final class RecordingSession implements AutoCloseable {
	public enum State { IDLE, RECORDING, PAUSED, STOPPING, STOPPED, FAILED }

	private final String audioId = UUID.randomUUID().toString();
	private final SyncClock clock = new SyncClock();
	private final BoundedPcmQueue queue;
	private final RecordingMetadata metadata = new RecordingMetadata();
	private final AtomicBoolean firstSample = new AtomicBoolean(true);
	private final AtomicLong encoderSamples = new AtomicLong();
	private final Object stateLock = new Object();

	private volatile State state = State.IDLE;
	private Path audioPath;
	private Path metadataPath;
	private Path temporaryMetadataPath;
	private OggOpusEncoder encoder;
	private Thread encoderThread;
	private volatile String failure;
	private long lastAnchorWallMs;
	private volatile boolean manual;

	public RecordingSession(int queueCapacityFrames) {
		this.queue = new BoundedPcmQueue(queueCapacityFrames);
	}

	public String audioId() {
		return audioId;
	}

	public SyncClock clock() {
		return clock;
	}

	public RecordingMetadata metadata() {
		return metadata;
	}

	public State state() {
		return state;
	}

	public boolean isActive() {
		State s = state;
		return s == State.RECORDING || s == State.PAUSED;
	}

	public boolean isManual() {
		return manual;
	}

	public Path audioPath() {
		return audioPath;
	}

	public Path metadataPath() {
		return metadataPath;
	}

	public long droppedSamples() {
		return queue.droppedSamples();
	}

	public String failure() {
		return failure;
	}

	public OggOpusEncoder.Backend encoderBackend() {
		return encoder == null ? null : encoder.backend();
	}

	public void start(
		Path outputDir,
		String integration,
		String replayId,
		String replayName,
		RecordingQuality quality,
		AudioFormatChoice format,
		Map<String, Boolean> categories,
		boolean manual,
		String minecraftVersion,
		String replayModVersion,
		String worldOrServerId
	) throws IOException {
		synchronized (stateLock) {
			if (isActive()) {
				throw new IllegalStateException("Recording already active");
			}
			this.manual = manual;
			Files.createDirectories(outputDir);
			String date = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
				.withZone(ZoneOffset.UTC)
				.format(Instant.now());
			String base = FileNames.recordingBaseName(
				replayName == null || replayName.isBlank() ? "recording" : replayName,
				date,
				safeUuid(audioId)
			);
			String ext = format == AudioFormatChoice.FLAC ? ".flac" : ".replayaudio.ogg";
			audioPath = outputDir.resolve(base + ext);
			metadataPath = outputDir.resolve(base + ".replayaudio.json");
			temporaryMetadataPath = outputDir.resolve(base + ".replayaudio.json.partial");

			metadata.setAudioId(audioId);
			metadata.setReplayId(Objects.requireNonNullElse(replayId, ""));
			metadata.setIntegration(Objects.requireNonNullElse(integration, "manual"));
			metadata.setAudioFile(audioPath.getFileName().toString());
			metadata.setCodec(format.codec());
			metadata.setContainer(format.container());
			metadata.setSampleRate(AudioConstants.SAMPLE_RATE);
			metadata.setChannels(AudioConstants.CHANNELS);
			metadata.setBitrate(quality.bitrate());
			metadata.setRecordingStart(System.currentTimeMillis());
			metadata.setComplete(false);
			metadata.setAudioCategories(categories);
			metadata.setMinecraftVersion(minecraftVersion);
			metadata.setReplayModVersion(replayModVersion);
			metadata.setWorldOrServerId(worldOrServerId);
			metadata.setReplayFileName(replayName);
			metadata.setCaptureStrategy("shadow_mixer");
			MetadataIo.writeAtomic(temporaryMetadataPath, metadata);

			// Opus Ogg is the production default. FLAC selection still records Opus for now
			// and notes the requested format in metadata; archival FLAC can be produced via FFmpeg offline.
			encoder = new OggOpusEncoder(audioPath, quality.bitrate());
			if (format == AudioFormatChoice.FLAC) {
				metadata.setCodec("opus");
				metadata.setContainer("ogg");
				metadata.setRecoveryNotes("FLAC requested; recorded as Opus Ogg (default production codec). Convert offline if needed.");
			}

			state = State.RECORDING;
			lastAnchorWallMs = System.currentTimeMillis();
			encoderThread = new Thread(this::encoderLoop, "replay-audio-encoder");
			encoderThread.setDaemon(true);
			encoderThread.start();
		}
	}

	private static UUID safeUuid(String id) {
		try {
			return UUID.fromString(id);
		} catch (Exception e) {
			return UUID.randomUUID();
		}
	}

	/** Called from capture thread with interleaved stereo s16 PCM. */
	public void offerPcm(short[] interleaved, int frames) {
		if (state != State.RECORDING || frames <= 0) {
			return;
		}
		if (firstSample.compareAndSet(true, false)) {
			clock.noteFirstSample();
			metadata.setFirstSampleReplayTime(clock.replayTimeMicrosecondsNow());
		}
		long idx = encoderSamples.get();
		queue.offer(new PcmFrame(copy(interleaved, frames), idx));
		maybeAnchor();
	}

	public void pause() {
		synchronized (stateLock) {
			if (state == State.RECORDING) {
				clock.pause();
				state = State.PAUSED;
				checkpointMetadata();
			}
		}
	}

	public void resume() {
		synchronized (stateLock) {
			if (state == State.PAUSED) {
				clock.resume();
				state = State.RECORDING;
				checkpointMetadata();
			}
		}
	}

	public void noteEvent(String reason) {
		clock.addAnchor(reason);
		checkpointMetadata();
	}

	public void setGameTick(long tick) {
		clock.setGameTick(tick);
	}

	public RecordingMetadata stop() throws IOException {
		synchronized (stateLock) {
			if (state == State.STOPPED) {
				return metadata;
			}
			state = State.STOPPING;
			clock.addAnchor("stop");
		}
		// Drain encoder
		try {
			if (encoderThread != null) {
				encoderThread.join(30_000);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		synchronized (stateLock) {
			if (encoder != null) {
				encoder.finish();
				encoder.close();
			}
			metadata.setRecordingEnd(System.currentTimeMillis());
			metadata.setTotalSamples(encoderSamples.get());
			metadata.setDurationMicroseconds(AudioConstants.samplesToMicros(encoderSamples.get()));
			metadata.setSyncAnchors(clock.anchors());
			metadata.setDroppedSamples(queue.droppedSamples());
			metadata.setComplete(true);
			if (audioPath != null && Files.isRegularFile(audioPath)) {
				metadata.setChecksum(Checksums.sha256(audioPath));
			}
			MetadataIo.writeAtomic(metadataPath, metadata);
			if (temporaryMetadataPath != null) {
				Files.deleteIfExists(temporaryMetadataPath);
			}
			state = State.STOPPED;
			return metadata;
		}
	}

	public void fail(String reason, Throwable t) {
		synchronized (stateLock) {
			failure = reason + (t == null ? "" : (": " + t.getMessage()));
			state = State.FAILED;
			metadata.setComplete(false);
			metadata.setRecoveryNotes(failure);
			try {
				if (temporaryMetadataPath != null) {
					MetadataIo.writeAtomic(temporaryMetadataPath, metadata);
				}
			} catch (IOException ignored) {
			}
		}
	}

	private void encoderLoop() {
		short[] pending = new short[AudioConstants.FRAME_SAMPLES * AudioConstants.CHANNELS];
		int pendingFrames = 0;
		try {
			while (true) {
				State s = state;
				if (s == State.STOPPING || s == State.STOPPED || s == State.FAILED) {
					// Drain remaining
					PcmFrame f;
					while ((f = queue.poll()) != null) {
						pendingFrames = consume(f, pending, pendingFrames);
					}
					if (pendingFrames > 0 && encoder != null) {
						// pad and encode
						short[] pad = new short[AudioConstants.FRAME_SAMPLES * AudioConstants.CHANNELS];
						System.arraycopy(pending, 0, pad, 0, pendingFrames * AudioConstants.CHANNELS);
						encoder.encodeInterleavedS16(pad, AudioConstants.FRAME_SAMPLES);
						encoderSamples.addAndGet(AudioConstants.FRAME_SAMPLES);
						clock.addSamples(AudioConstants.FRAME_SAMPLES);
					}
					break;
				}
				PcmFrame frame = queue.poll(50);
				if (frame == null) {
					// Under-run: feed silence to preserve timeline when recording.
					if (s == State.RECORDING) {
						// Do not auto-silence every 50ms forever — only fill small gaps when queue is empty but active
						// (handled by capture side generating continuous clock ticks).
					}
					continue;
				}
				pendingFrames = consume(frame, pending, pendingFrames);
			}
		} catch (Throwable t) {
			fail("Encoder loop failed", t);
		}
	}

	private int consume(PcmFrame frame, short[] pending, int pendingFrames) throws IOException {
		int srcFrames = frame.frames();
		int srcPos = 0;
		short[] samples = frame.samples();
		while (srcPos < srcFrames) {
			int need = AudioConstants.FRAME_SAMPLES - pendingFrames;
			int take = Math.min(need, srcFrames - srcPos);
			System.arraycopy(samples, srcPos * 2, pending, pendingFrames * 2, take * 2);
			pendingFrames += take;
			srcPos += take;
			if (pendingFrames == AudioConstants.FRAME_SAMPLES) {
				encoder.encodeInterleavedS16(pending, AudioConstants.FRAME_SAMPLES);
				encoderSamples.addAndGet(AudioConstants.FRAME_SAMPLES);
				clock.addSamples(AudioConstants.FRAME_SAMPLES);
				pendingFrames = 0;
			}
		}
		return pendingFrames;
	}

	private void maybeAnchor() {
		long now = System.currentTimeMillis();
		if (now - lastAnchorWallMs >= 5_000) {
			clock.addAnchor("periodic");
			lastAnchorWallMs = now;
			checkpointMetadata();
		}
	}

	private void checkpointMetadata() {
		try {
			metadata.setTotalSamples(encoderSamples.get());
			metadata.setDurationMicroseconds(AudioConstants.samplesToMicros(encoderSamples.get()));
			metadata.setSyncAnchors(clock.anchors());
			metadata.setDroppedSamples(queue.droppedSamples());
			metadata.setComplete(false);
			if (temporaryMetadataPath != null) {
				MetadataIo.writeAtomic(temporaryMetadataPath, metadata);
			}
		} catch (IOException ignored) {
		}
	}

	private static short[] copy(short[] src, int frames) {
		short[] out = new short[frames * AudioConstants.CHANNELS];
		System.arraycopy(src, 0, out, 0, Math.min(src.length, out.length));
		return out;
	}

	@Override
	public void close() {
		try {
			if (isActive() || state == State.STOPPING) {
				stop();
			}
		} catch (IOException ignored) {
		}
	}
}
