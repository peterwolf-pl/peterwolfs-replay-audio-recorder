package com.peterwolf.replayaudio.audio;

/** Configurable Opus bitrates. Default is Very High (160 kbps stereo). */
public enum RecordingQuality {
	LOW(64_000),
	MEDIUM(96_000),
	HIGH(128_000),
	VERY_HIGH(160_000),
	MAXIMUM(256_000);

	private final int bitrate;

	RecordingQuality(int bitrate) {
		this.bitrate = bitrate;
	}

	public int bitrate() {
		return bitrate;
	}

	public static RecordingQuality fromName(String name) {
		if (name == null || name.isBlank()) {
			return VERY_HIGH;
		}
		try {
			return RecordingQuality.valueOf(name.trim().toUpperCase().replace(' ', '_'));
		} catch (IllegalArgumentException e) {
			return VERY_HIGH;
		}
	}
}
