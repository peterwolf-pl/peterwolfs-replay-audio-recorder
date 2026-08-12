package com.peterwolf.replayaudio.audio;

import com.peterwolf.replayaudio.metadata.MetadataIo;
import com.peterwolf.replayaudio.metadata.RecordingMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** Locate sidecar audio for a replay using multiple stable identifiers, not only filenames. */
public final class RecordingAssociation {
	private RecordingAssociation() {
	}

	public record Match(Path metadataPath, Path audioPath, RecordingMetadata metadata, int score) {
	}

	public static Optional<Match> findBest(RecordingPaths paths, String integration, String replayId, String replayFileName) {
		List<Match> all = findAll(paths, integration, replayId, replayFileName);
		return all.stream().max(Comparator.comparingInt(Match::score));
	}

	public static List<Match> findAll(RecordingPaths paths, String integration, String replayId, String replayFileName) {
		List<Match> matches = new ArrayList<>();
		List<Path> roots = new ArrayList<>();
		if (integration != null) {
			roots.add(paths.forIntegration(integration));
		}
		roots.add(paths.replayMod());
		roots.add(paths.flashback());
		roots.add(paths.root().resolve("manual"));
		roots.add(paths.orphaned());
		for (Path root : roots) {
			if (!Files.isDirectory(root)) continue;
			try (Stream<Path> stream = Files.walk(root, 2)) {
				stream.filter(p -> p.getFileName().toString().endsWith(".replayaudio.json"))
					.forEach(metaPath -> {
						try {
							RecordingMetadata meta = MetadataIo.read(metaPath);
							Path audio = metaPath.resolveSibling(meta.audioFile());
							if (!Files.isRegularFile(audio)) {
								// try same base
								String base = metaPath.getFileName().toString().replace(".replayaudio.json", "");
								Path alt = metaPath.resolveSibling(base + ".replayaudio.ogg");
								if (Files.isRegularFile(alt)) {
									audio = alt;
								}
							}
							int score = score(meta, integration, replayId, replayFileName);
							if (score > 0) {
								matches.add(new Match(metaPath, audio, meta, score));
							}
						} catch (Exception ignored) {
						}
					});
			} catch (IOException ignored) {
			}
		}
		return matches;
	}

	public static int score(RecordingMetadata meta, String integration, String replayId, String replayFileName) {
		int score = 0;
		if (replayId != null && !replayId.isBlank() && replayId.equals(meta.replayId())) {
			score += 100;
		}
		if (integration != null && integration.equalsIgnoreCase(meta.integration())) {
			score += 20;
		}
		if (replayFileName != null && !replayFileName.isBlank()) {
			String a = replayFileName.toLowerCase(Locale.ROOT);
			String b = meta.replayFileName() == null ? "" : meta.replayFileName().toLowerCase(Locale.ROOT);
			String c = meta.audioFile() == null ? "" : meta.audioFile().toLowerCase(Locale.ROOT);
			if (!b.isBlank() && (a.equals(b) || a.contains(b) || b.contains(a))) {
				score += 40;
			}
			if (c.contains(FileNamesBase(a))) {
				score += 15;
			}
		}
		if (meta.complete()) {
			score += 5;
		}
		return score;
	}

	private static String FileNamesBase(String name) {
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		String n = slash >= 0 ? name.substring(slash + 1) : name;
		int dot = n.lastIndexOf('.');
		return dot > 0 ? n.substring(0, dot) : n;
	}
}
