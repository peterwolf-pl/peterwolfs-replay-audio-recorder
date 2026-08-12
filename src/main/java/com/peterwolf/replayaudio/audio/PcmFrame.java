package com.peterwolf.replayaudio.audio;

import java.util.Arrays;

/** Interleaved stereo s16le PCM frame batch. */
public final class PcmFrame {
	private final short[] samples; // LRLR...
	private final long sampleIndex;

	public PcmFrame(short[] samples, long sampleIndex) {
		this.samples = samples;
		this.sampleIndex = sampleIndex;
	}

	public short[] samples() {
		return samples;
	}

	public long sampleIndex() {
		return sampleIndex;
	}

	public int frames() {
		return samples.length / AudioConstants.CHANNELS;
	}

	public PcmFrame copy() {
		return new PcmFrame(Arrays.copyOf(samples, samples.length), sampleIndex);
	}

	public static PcmFrame silence(int frames, long sampleIndex) {
		return new PcmFrame(new short[frames * AudioConstants.CHANNELS], sampleIndex);
	}
}
