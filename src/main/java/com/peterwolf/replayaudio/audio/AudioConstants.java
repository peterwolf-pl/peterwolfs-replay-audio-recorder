package com.peterwolf.replayaudio.audio;

public final class AudioConstants {
	public static final int SAMPLE_RATE = 48_000;
	public static final int CHANNELS = 2;
	public static final int BYTES_PER_SAMPLE = 2; // s16le
	public static final int FRAME_SAMPLES = 960; // 20 ms @ 48 kHz
	public static final int DEFAULT_BITRATE = 160_000;
	public static final long SEEK_ERROR_LOG_MICROS = 20_000L;
	public static final long DURATION_TOLERANCE_MICROS = 50_000L;
	public static final double MIN_STRETCH_SPEED = 0.25;
	public static final double MAX_STRETCH_SPEED = 4.0;
	public static final int DEFAULT_EDIT_FADE_MS = 5;
	public static final int MIN_EDIT_FADE_MS = 3;
	public static final int MAX_EDIT_FADE_MS = 10;

	private AudioConstants() {
	}

	public static long samplesToMicros(long samples) {
		return samples * 1_000_000L / SAMPLE_RATE;
	}

	public static long microsToSamples(long micros) {
		return micros * SAMPLE_RATE / 1_000_000L;
	}

	public static int frameBytes() {
		return FRAME_SAMPLES * CHANNELS * BYTES_PER_SAMPLE;
	}
}
