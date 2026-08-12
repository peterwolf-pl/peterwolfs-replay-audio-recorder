package com.peterwolf.replayaudio.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Write-then-rename helpers so incomplete files never replace good ones. */
public final class AtomicFiles {
	private AtomicFiles() {
	}

	public static void writeStringAtomic(Path target, String content) throws IOException {
		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(tmp, content, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	public static void moveReplace(Path source, Path target) throws IOException {
		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomicFailed) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
