package com.peterwolf.replayaudio.sync;

import com.peterwolf.replayaudio.audio.AudioConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Monotonic capture clock with periodic anchors for drift detection/correction.
 * Replay time is authoritative during playback; anchors convert audio sample index ↔ replay µs.
 */
public final class SyncClock {
	private final long startMonotonicNanos;
	private long pausedNanosAccumulated;
	private long pauseStartedNanos = -1L;
	private long firstSampleMonotonicNanos = -1L;
	private long totalSamples;
	private long gameTick;
	private final List<SyncAnchor> anchors = new ArrayList<>();

	public SyncClock() {
		this.startMonotonicNanos = System.nanoTime();
		addAnchor("start");
	}

	public synchronized void pause() {
		if (pauseStartedNanos < 0) {
			pauseStartedNanos = System.nanoTime();
			addAnchor("pause");
		}
	}

	public synchronized void resume() {
		if (pauseStartedNanos >= 0) {
			pausedNanosAccumulated += System.nanoTime() - pauseStartedNanos;
			pauseStartedNanos = -1L;
			addAnchor("resume");
		}
	}

	public synchronized boolean isPaused() {
		return pauseStartedNanos >= 0;
	}

	public synchronized void noteFirstSample() {
		if (firstSampleMonotonicNanos < 0) {
			firstSampleMonotonicNanos = System.nanoTime();
			addAnchor("first_sample");
		}
	}

	public synchronized void addSamples(long samples) {
		if (samples > 0) {
			totalSamples += samples;
		}
	}

	public synchronized void setGameTick(long tick) {
		this.gameTick = tick;
	}

	public synchronized long totalSamples() {
		return totalSamples;
	}

	public synchronized long replayTimeMicrosecondsNow() {
		long now = System.nanoTime();
		long paused = pausedNanosAccumulated;
		if (pauseStartedNanos >= 0) {
			paused += now - pauseStartedNanos;
		}
		long elapsedNanos = now - startMonotonicNanos - paused;
		if (elapsedNanos < 0) {
			elapsedNanos = 0;
		}
		return elapsedNanos / 1_000L;
	}

	public synchronized long sampleIndexForReplayTime(long replayTimeMicros) {
		if (anchors.isEmpty()) {
			return AudioConstants.microsToSamples(Math.max(0, replayTimeMicros));
		}
		// Linear interpolation between surrounding anchors; extrapolate with last drift estimate.
		SyncAnchor before = anchors.getFirst();
		SyncAnchor after = anchors.getLast();
		for (int i = 0; i < anchors.size(); i++) {
			SyncAnchor a = anchors.get(i);
			if (a.replayTimeMicroseconds() <= replayTimeMicros) {
				before = a;
			}
			if (a.replayTimeMicroseconds() >= replayTimeMicros) {
				after = a;
				break;
			}
		}
		if (before.replayTimeMicroseconds() == after.replayTimeMicroseconds()) {
			return before.audioSampleIndex();
		}
		double t = (double) (replayTimeMicros - before.replayTimeMicroseconds())
			/ (double) (after.replayTimeMicroseconds() - before.replayTimeMicroseconds());
		return Math.round(before.audioSampleIndex() + t * (after.audioSampleIndex() - before.audioSampleIndex()));
	}

	public synchronized long replayTimeForSampleIndex(long sampleIndex) {
		if (anchors.isEmpty()) {
			return AudioConstants.samplesToMicros(Math.max(0, sampleIndex));
		}
		SyncAnchor before = anchors.getFirst();
		SyncAnchor after = anchors.getLast();
		for (int i = 0; i < anchors.size(); i++) {
			SyncAnchor a = anchors.get(i);
			if (a.audioSampleIndex() <= sampleIndex) {
				before = a;
			}
			if (a.audioSampleIndex() >= sampleIndex) {
				after = a;
				break;
			}
		}
		if (before.audioSampleIndex() == after.audioSampleIndex()) {
			return before.replayTimeMicroseconds();
		}
		double t = (double) (sampleIndex - before.audioSampleIndex())
			/ (double) (after.audioSampleIndex() - before.audioSampleIndex());
		return Math.round(before.replayTimeMicroseconds()
			+ t * (after.replayTimeMicroseconds() - before.replayTimeMicroseconds()));
	}

	/** Estimated drift of wall-sample progression vs pure 48 kHz ideal, in microseconds. */
	public synchronized long estimatedDriftMicros() {
		if (anchors.size() < 2) {
			return 0L;
		}
		SyncAnchor first = anchors.getFirst();
		SyncAnchor last = anchors.getLast();
		long idealSamples = AudioConstants.microsToSamples(last.replayTimeMicroseconds() - first.replayTimeMicroseconds());
		long actualSamples = last.audioSampleIndex() - first.audioSampleIndex();
		return AudioConstants.samplesToMicros(actualSamples - idealSamples);
	}

	public synchronized SyncAnchor addAnchor(String reason) {
		SyncAnchor anchor = new SyncAnchor(
			replayTimeMicrosecondsNow(),
			totalSamples,
			gameTick,
			System.nanoTime(),
			reason
		);
		anchors.add(anchor);
		return anchor;
	}

	public synchronized List<SyncAnchor> anchors() {
		return Collections.unmodifiableList(new ArrayList<>(anchors));
	}

	public synchronized long startMonotonicNanos() {
		return startMonotonicNanos;
	}

	public synchronized long firstSampleMonotonicNanos() {
		return firstSampleMonotonicNanos;
	}
}
