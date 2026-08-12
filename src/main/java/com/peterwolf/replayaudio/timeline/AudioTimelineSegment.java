package com.peterwolf.replayaudio.timeline;

/** One continuous edit decision list segment from source audio to output timeline. */
public final class AudioTimelineSegment {
	private final long sourceStartMicros;
	private final long sourceEndMicros;
	private final long outputStartMicros;
	private final double speed;
	private final boolean reverse;

	public AudioTimelineSegment(long sourceStartMicros, long sourceEndMicros, long outputStartMicros, double speed) {
		this(sourceStartMicros, sourceEndMicros, outputStartMicros, speed, false);
	}

	public AudioTimelineSegment(long sourceStartMicros, long sourceEndMicros, long outputStartMicros, double speed, boolean reverse) {
		this.sourceStartMicros = sourceStartMicros;
		this.sourceEndMicros = sourceEndMicros;
		this.outputStartMicros = outputStartMicros;
		this.speed = speed <= 0 ? 1.0 : speed;
		this.reverse = reverse;
	}

	public long sourceStartMicros() {
		return sourceStartMicros;
	}

	public long sourceEndMicros() {
		return sourceEndMicros;
	}

	public long outputStartMicros() {
		return outputStartMicros;
	}

	public double speed() {
		return speed;
	}

	public boolean reverse() {
		return reverse;
	}

	public long sourceDurationMicros() {
		return Math.max(0, sourceEndMicros - sourceStartMicros);
	}

	public long outputDurationMicros() {
		if (speed <= 0) {
			return sourceDurationMicros();
		}
		return Math.round(sourceDurationMicros() / speed);
	}
}
