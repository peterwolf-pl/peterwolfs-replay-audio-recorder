package com.peterwolf.replayaudio.util;

import java.util.Locale;
import java.util.UUID;

/** Cross-platform filename sanitization and suggested recording names. */
public final class FileNames {
	private FileNames() {
	}

	public static String sanitize(String raw) {
		if (raw == null || raw.isBlank()) {
			return "recording";
		}
		String cleaned = raw
			.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_")
			.replaceAll("\\s+", "_")
			.replaceAll("_+", "_")
			.replaceAll("^\\.+", "")
			.trim();
		if (cleaned.isBlank() || cleaned.equals(".") || cleaned.equals("..")) {
			return "recording";
		}
		if (cleaned.length() > 120) {
			cleaned = cleaned.substring(0, 120);
		}
		return cleaned;
	}

	public static String recordingBaseName(String replayName, String dateTag, UUID audioId) {
		return sanitize(replayName) + "_" + sanitize(dateTag) + "_" + audioId.toString().replace("-", "").substring(0, 12);
	}

	public static String lower(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT);
	}
}
