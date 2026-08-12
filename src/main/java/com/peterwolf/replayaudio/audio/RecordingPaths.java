package com.peterwolf.replayaudio.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Standard directory layout under .minecraft/replay_audio_recordings/. */
public final class RecordingPaths {
	public static final String ROOT_NAME = "replay_audio_recordings";

	private final Path root;

	public RecordingPaths(Path gameDir) {
		this.root = gameDir.resolve(ROOT_NAME);
	}

	public Path root() {
		return root;
	}

	public Path replayMod() {
		return root.resolve("replaymod");
	}

	public Path flashback() {
		return root.resolve("flashback");
	}

	public Path orphaned() {
		return root.resolve("orphaned");
	}

	public Path exports() {
		return root.resolve("exports");
	}

	public Path temporary() {
		return root.resolve("temporary");
	}

	public Path forIntegration(String integration) {
		if ("flashback".equalsIgnoreCase(integration)) {
			return flashback();
		}
		if ("replaymod".equalsIgnoreCase(integration)) {
			return replayMod();
		}
		return root.resolve("manual");
	}

	public void ensureLayout() throws IOException {
		Files.createDirectories(replayMod());
		Files.createDirectories(flashback());
		Files.createDirectories(orphaned());
		Files.createDirectories(exports());
		Files.createDirectories(temporary());
		Files.createDirectories(root.resolve("manual"));
	}
}
