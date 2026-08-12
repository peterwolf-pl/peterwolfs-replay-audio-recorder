package com.peterwolf.replayaudio.sync;

import java.util.Objects;

/** Maps replay timeline time to audio sample index using a monotonic clock. */
public final class SyncAnchor {
	private final long replayTimeMicroseconds;
	private final long audioSampleIndex;
	private final long gameTick;
	private final long monotonicTimeNanoseconds;
	private final String reason;

	public SyncAnchor(long replayTimeMicroseconds, long audioSampleIndex, long gameTick,
			long monotonicTimeNanoseconds, String reason) {
		this.replayTimeMicroseconds = replayTimeMicroseconds;
		this.audioSampleIndex = audioSampleIndex;
		this.gameTick = gameTick;
		this.monotonicTimeNanoseconds = monotonicTimeNanoseconds;
		this.reason = reason == null ? "periodic" : reason;
	}

	public long replayTimeMicroseconds() {
		return replayTimeMicroseconds;
	}

	public long audioSampleIndex() {
		return audioSampleIndex;
	}

	public long gameTick() {
		return gameTick;
	}

	public long monotonicTimeNanoseconds() {
		return monotonicTimeNanoseconds;
	}

	public String reason() {
		return reason;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof SyncAnchor that)) return false;
		return replayTimeMicroseconds == that.replayTimeMicroseconds
			&& audioSampleIndex == that.audioSampleIndex
			&& gameTick == that.gameTick
			&& monotonicTimeNanoseconds == that.monotonicTimeNanoseconds
			&& Objects.equals(reason, that.reason);
	}

	@Override
	public int hashCode() {
		return Objects.hash(replayTimeMicroseconds, audioSampleIndex, gameTick, monotonicTimeNanoseconds, reason);
	}
}
