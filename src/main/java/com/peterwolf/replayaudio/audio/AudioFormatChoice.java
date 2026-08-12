package com.peterwolf.replayaudio.audio;

/** Primary format is Ogg Opus. FLAC is optional archival only. */
public enum AudioFormatChoice {
	OPUS_OGG("opus", "ogg", ".ogg"),
	FLAC("flac", "flac", ".flac");

	private final String codec;
	private final String container;
	private final String extension;

	AudioFormatChoice(String codec, String container, String extension) {
		this.codec = codec;
		this.container = container;
		this.extension = extension;
	}

	public String codec() {
		return codec;
	}

	public String container() {
		return container;
	}

	public String extension() {
		return extension;
	}

	public static AudioFormatChoice fromName(String name) {
		if (name == null) {
			return OPUS_OGG;
		}
		String n = name.trim().toLowerCase();
		if (n.contains("flac")) {
			return FLAC;
		}
		return OPUS_OGG;
	}
}
