package com.peterwolf.replayaudio.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Checksums {
	private Checksums() {
	}

	public static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream in = Files.newInputStream(file)) {
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) >= 0) {
					if (n > 0) {
						digest.update(buf, 0, n);
					}
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}
