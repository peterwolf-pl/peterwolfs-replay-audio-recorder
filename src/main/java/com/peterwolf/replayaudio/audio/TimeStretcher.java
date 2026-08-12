package com.peterwolf.replayaudio.audio;

/**
 * Lightweight WSOLA-style time stretcher that preserves pitch for forward speeds
 * in the supported range [0.25, 4.0]. Outside that range the caller should mute
 * or use simple resampling with pitch change.
 */
public final class TimeStretcher {
	private static final int WINDOW = 1024;
	private static final int HOP_ANALYSIS = 256;

	private TimeStretcher() {
	}

	public static boolean isSupportedSpeed(double speed) {
		return speed > 0
			&& speed >= AudioConstants.MIN_STRETCH_SPEED
			&& speed <= AudioConstants.MAX_STRETCH_SPEED;
	}

	/**
	 * Stretch interleaved stereo PCM by factor speed (2.0 = twice as fast → shorter output).
	 */
	public static short[] stretch(short[] inputInterleaved, double speed) {
		if (inputInterleaved == null || inputInterleaved.length < 2) {
			return inputInterleaved == null ? new short[0] : inputInterleaved.clone();
		}
		if (!isSupportedSpeed(speed) || Math.abs(speed - 1.0) < 1e-6) {
			if (Math.abs(speed - 1.0) < 1e-6) {
				return inputInterleaved.clone();
			}
			// Simple resample (pitch changes) as fallback.
			return resample(inputInterleaved, speed);
		}
		int inFrames = inputInterleaved.length / AudioConstants.CHANNELS;
		int hopSynthesis = Math.max(1, (int) Math.round(HOP_ANALYSIS / speed));
		int outFrames = Math.max(1, (int) Math.round(inFrames / speed));
		short[] output = new short[outFrames * AudioConstants.CHANNELS];
		float[] leftIn = new float[inFrames];
		float[] rightIn = new float[inFrames];
		for (int i = 0; i < inFrames; i++) {
			leftIn[i] = inputInterleaved[i * 2] / 32768f;
			rightIn[i] = inputInterleaved[i * 2 + 1] / 32768f;
		}
		float[] leftOut = new float[outFrames];
		float[] rightOut = new float[outFrames];
		float[] window = hann(WINDOW);
		int outPos = 0;
		double inPos = 0;
		while (outPos + WINDOW < outFrames && (int) inPos + WINDOW < inFrames) {
			int i0 = (int) inPos;
			for (int n = 0; n < WINDOW; n++) {
				float w = window[n];
				leftOut[outPos + n] += leftIn[i0 + n] * w;
				rightOut[outPos + n] += rightIn[i0 + n] * w;
			}
			outPos += hopSynthesis;
			inPos += HOP_ANALYSIS;
		}
		// Copy any remaining tail with linear resample.
		for (int i = outPos; i < outFrames; i++) {
			int src = Math.min(inFrames - 1, (int) Math.round(i * speed));
			leftOut[i] = leftIn[src];
			rightOut[i] = rightIn[src];
		}
		for (int i = 0; i < outFrames; i++) {
			output[i * 2] = clamp(leftOut[i]);
			output[i * 2 + 1] = clamp(rightOut[i]);
		}
		return output;
	}

	public static short[] reverse(short[] inputInterleaved) {
		if (inputInterleaved == null || inputInterleaved.length < 2) {
			return inputInterleaved == null ? new short[0] : inputInterleaved.clone();
		}
		int frames = inputInterleaved.length / 2;
		short[] out = new short[inputInterleaved.length];
		for (int i = 0; i < frames; i++) {
			int j = frames - 1 - i;
			out[i * 2] = inputInterleaved[j * 2];
			out[i * 2 + 1] = inputInterleaved[j * 2 + 1];
		}
		return out;
	}

	public static short[] applyFade(short[] interleaved, int fadeInSamples, int fadeOutSamples) {
		if (interleaved == null || interleaved.length == 0) {
			return interleaved == null ? new short[0] : interleaved;
		}
		short[] out = interleaved.clone();
		int frames = out.length / 2;
		fadeInSamples = Math.min(frames, Math.max(0, fadeInSamples));
		fadeOutSamples = Math.min(frames, Math.max(0, fadeOutSamples));
		for (int i = 0; i < fadeInSamples; i++) {
			float g = (i + 1) / (float) fadeInSamples;
			out[i * 2] = (short) (out[i * 2] * g);
			out[i * 2 + 1] = (short) (out[i * 2 + 1] * g);
		}
		for (int i = 0; i < fadeOutSamples; i++) {
			int idx = frames - 1 - i;
			float g = (i + 1) / (float) fadeOutSamples;
			// fade out: last sample gain ~0
			float gain = 1f - (i / (float) fadeOutSamples);
			out[idx * 2] = (short) (out[idx * 2] * gain);
			out[idx * 2 + 1] = (short) (out[idx * 2 + 1] * gain);
		}
		return out;
	}

	private static short[] resample(short[] input, double speed) {
		int inFrames = input.length / 2;
		int outFrames = Math.max(1, (int) Math.round(inFrames / speed));
		short[] out = new short[outFrames * 2];
		for (int i = 0; i < outFrames; i++) {
			double src = i * speed;
			int i0 = Math.min(inFrames - 1, (int) src);
			int i1 = Math.min(inFrames - 1, i0 + 1);
			float t = (float) (src - i0);
			out[i * 2] = lerp(input[i0 * 2], input[i1 * 2], t);
			out[i * 2 + 1] = lerp(input[i0 * 2 + 1], input[i1 * 2 + 1], t);
		}
		return out;
	}

	private static short lerp(short a, short b, float t) {
		return (short) Math.round(a + (b - a) * t);
	}

	private static short clamp(float v) {
		v = Math.max(-1f, Math.min(1f, v));
		return (short) Math.round(v * 32767f);
	}

	private static float[] hann(int n) {
		float[] w = new float[n];
		for (int i = 0; i < n; i++) {
			w[i] = 0.5f * (1f - (float) Math.cos(2 * Math.PI * i / (n - 1.0)));
		}
		return w;
	}
}
