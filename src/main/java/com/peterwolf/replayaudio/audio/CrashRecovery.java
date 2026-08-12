package com.peterwolf.replayaudio.audio;

import com.peterwolf.replayaudio.metadata.MetadataIo;
import com.peterwolf.replayaudio.metadata.RecordingMetadata;
import com.peterwolf.replayaudio.util.AtomicFiles;
import com.peterwolf.replayaudio.util.Checksums;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Detect and repair incomplete recordings after crash / forced shutdown. */
public final class CrashRecovery {
	public record RecoveryItem(Path partialMetadata, Path audioFile, RecordingMetadata metadata, String status) {
	}

	private CrashRecovery() {
	}

	public static List<RecoveryItem> scan(RecordingPaths paths) throws IOException {
		paths.ensureLayout();
		List<RecoveryItem> items = new ArrayList<>();
		try (Stream<Path> stream = Files.walk(paths.root(), 3)) {
			stream.filter(p -> p.getFileName().toString().endsWith(".replayaudio.json.partial"))
				.forEach(partial -> items.add(inspect(partial, paths)));
		}
		// Also incomplete complete=false metadata
		try (Stream<Path> stream = Files.walk(paths.root(), 3)) {
			stream.filter(p -> p.getFileName().toString().endsWith(".replayaudio.json"))
				.forEach(metaPath -> {
					try {
						RecordingMetadata m = MetadataIo.read(metaPath);
						if (!m.complete()) {
							Path audio = metaPath.resolveSibling(m.audioFile());
							items.add(new RecoveryItem(metaPath, audio, m, Files.isRegularFile(audio) ? "incomplete" : "missing_audio"));
						}
					} catch (Exception ignored) {
					}
				});
		}
		return items;
	}

	private static RecoveryItem inspect(Path partial, RecordingPaths paths) {
		try {
			RecordingMetadata m = MetadataIo.read(partial);
			Path audio = partial.resolveSibling(m.audioFile());
			if (!Files.isRegularFile(audio)) {
				String base = partial.getFileName().toString().replace(".replayaudio.json.partial", "");
				Path alt = partial.resolveSibling(base + ".replayaudio.ogg");
				if (Files.isRegularFile(alt)) {
					audio = alt;
				}
			}
			if (!Files.isRegularFile(audio)) {
				return new RecoveryItem(partial, audio, m, "missing_audio");
			}
			return new RecoveryItem(partial, audio, m, "recoverable");
		} catch (Exception e) {
			return new RecoveryItem(partial, null, null, "unreadable");
		}
	}

	public static RecordingMetadata repair(RecoveryItem item, RecordingPaths paths) throws IOException {
		if (item.metadata() == null || item.audioFile() == null || !Files.isRegularFile(item.audioFile())) {
			// move unrecoverable
			if (item.partialMetadata() != null && Files.exists(item.partialMetadata())) {
				Path dest = paths.orphaned().resolve(item.partialMetadata().getFileName());
				AtomicFiles.moveReplace(item.partialMetadata(), dest);
			}
			throw new IOException("Unrecoverable recording");
		}
		RecordingMetadata m = item.metadata();
		// Estimate duration from file size for Opus ~ bitrate; better: use sample count if known.
		long size = Files.size(item.audioFile());
		if (m.totalSamples() <= 0 && m.bitrate() > 0) {
			// rough estimate: size * 8 / bitrate * sampleRate
			long micros = size * 8_000_000L / Math.max(1, m.bitrate());
			m.setDurationMicroseconds(micros);
			m.setTotalSamples(AudioConstants.microsToSamples(micros));
		}
		m.setComplete(true);
		m.setRecoveryNotes("Repaired after incomplete shutdown");
		m.setChecksum(Checksums.sha256(item.audioFile()));
		Path finalMeta = item.audioFile().resolveSibling(
			item.audioFile().getFileName().toString()
				.replace(".replayaudio.ogg", ".replayaudio.json")
				.replace(".ogg", ".replayaudio.json")
		);
		if (!finalMeta.getFileName().toString().endsWith(".json")) {
			finalMeta = item.audioFile().resolveSibling(item.audioFile().getFileName() + ".replayaudio.json");
		}
		// Prefer replacing .partial with proper name
		String name = item.partialMetadata().getFileName().toString();
		if (name.endsWith(".partial")) {
			finalMeta = item.partialMetadata().resolveSibling(name.substring(0, name.length() - ".partial".length()));
		}
		MetadataIo.writeAtomic(finalMeta, m);
		Files.deleteIfExists(item.partialMetadata());
		return m;
	}

	public static void moveToOrphaned(RecoveryItem item, RecordingPaths paths) throws IOException {
		paths.ensureLayout();
		if (item.partialMetadata() != null && Files.exists(item.partialMetadata())) {
			AtomicFiles.moveReplace(item.partialMetadata(), paths.orphaned().resolve(item.partialMetadata().getFileName()));
		}
		if (item.audioFile() != null && Files.exists(item.audioFile())) {
			AtomicFiles.moveReplace(item.audioFile(), paths.orphaned().resolve(item.audioFile().getFileName()));
		}
	}
}
