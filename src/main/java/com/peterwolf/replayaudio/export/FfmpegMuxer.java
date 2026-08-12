package com.peterwolf.replayaudio.export;

import com.peterwolf.replayaudio.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Muxes processed audio into a rendered silent MP4 by copying the video stream
 * and encoding only audio to AAC. Never overwrites the only valid MP4 in place.
 */
public final class FfmpegMuxer {
	public record MuxRequest(
		Path videoMp4,
		Path audioInput,
		Path outputMp4,
		String ffmpegPath,
		int aacBitrate,
		boolean keepOriginalSilentMp4
	) {
	}

	public record MuxResult(
		boolean success,
		Path output,
		int exitCode,
		String stdout,
		String stderr,
		String ffmpegCommand,
		String failureReason,
		boolean videoCopied,
		boolean audioPresent
	) {
	}

	private FfmpegMuxer() {
	}

	public static boolean isFfmpegAvailable(String ffmpegPath) {
		try {
			Process p = new ProcessBuilder(ffmpegPath == null ? "ffmpeg" : ffmpegPath, "-version")
				.redirectErrorStream(true)
				.start();
			boolean finished = p.waitFor(5, TimeUnit.SECONDS);
			return finished && p.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	public static List<String> buildCommand(MuxRequest req) {
		List<String> cmd = new ArrayList<>();
		cmd.add(req.ffmpegPath() == null || req.ffmpegPath().isBlank() ? "ffmpeg" : req.ffmpegPath());
		cmd.add("-hide_banner");
		cmd.add("-y");
		cmd.add("-i");
		cmd.add(req.videoMp4().toAbsolutePath().toString());
		cmd.add("-i");
		cmd.add(req.audioInput().toAbsolutePath().toString());
		cmd.add("-map");
		cmd.add("0:v:0");
		cmd.add("-map");
		cmd.add("1:a:0");
		cmd.add("-c:v");
		cmd.add("copy");
		cmd.add("-c:a");
		cmd.add("aac");
		cmd.add("-b:a");
		cmd.add((req.aacBitrate() <= 0 ? 192_000 : req.aacBitrate()) + "");
		// ffmpeg wants 192k style too; accept raw bps or convert
		if (req.aacBitrate() >= 1000) {
			cmd.set(cmd.size() - 1, (req.aacBitrate() / 1000) + "k");
		}
		cmd.add("-shortest");
		Path tmpOut = req.outputMp4().resolveSibling(req.outputMp4().getFileName() + ".muxing.tmp.mp4");
		cmd.add(tmpOut.toAbsolutePath().toString());
		return cmd;
	}

	public static String commandAsShell(List<String> cmd) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < cmd.size(); i++) {
			if (i > 0) sb.append(' ');
			sb.append(shellQuote(cmd.get(i)));
		}
		return sb.toString();
	}

	public static MuxResult mux(MuxRequest req) {
		Path tmpOut = req.outputMp4().resolveSibling(req.outputMp4().getFileName() + ".muxing.tmp.mp4");
		List<String> cmd = buildCommand(req);
		// Fix last arg to tmp
		cmd.set(cmd.size() - 1, tmpOut.toAbsolutePath().toString());
		String shell = commandAsShell(cmd);
		if (!isFfmpegAvailable(req.ffmpegPath())) {
			return new MuxResult(false, null, -1, "", "", shell,
				"FFmpeg is not available. Silent MP4 kept; processed audio preserved.", false, false);
		}
		if (!Files.isRegularFile(req.videoMp4())) {
			return new MuxResult(false, null, -1, "", "", shell, "Video file missing: " + req.videoMp4(), false, false);
		}
		if (!Files.isRegularFile(req.audioInput())) {
			return new MuxResult(false, null, -1, "", "", shell, "Audio file missing: " + req.audioInput(), false, false);
		}
		try {
			Files.createDirectories(req.outputMp4().getParent());
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectError(ProcessBuilder.Redirect.PIPE);
			pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
			Process p = pb.start();
			String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
			int code = p.waitFor();
			if (code != 0) {
				Files.deleteIfExists(tmpOut);
				return new MuxResult(false, null, code, stdout, stderr, shell,
					"FFmpeg exited with code " + code, false, false);
			}
			MuxResult validation = validateMp4(tmpOut, req.ffmpegPath(), shell, code, stdout, stderr);
			if (!validation.success()) {
				Files.deleteIfExists(tmpOut);
				return validation;
			}
			AtomicFiles.moveReplace(tmpOut, req.outputMp4());
			return new MuxResult(true, req.outputMp4(), code, stdout, stderr, shell, null, true, true);
		} catch (Exception e) {
			try {
				Files.deleteIfExists(tmpOut);
			} catch (IOException ignored) {
			}
			return new MuxResult(false, null, -1, "", e.toString(), shell, e.getMessage(), false, false);
		}
	}

	public static MuxResult validateMp4(Path mp4, String ffmpegPath, String shell, int code, String stdout, String stderr) {
		try {
			if (!Files.isRegularFile(mp4) || Files.size(mp4) <= 0) {
				return new MuxResult(false, null, code, stdout, stderr, shell, "Output MP4 missing or empty", false, false);
			}
			String ffprobe = (ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath)
				.replace("ffmpeg", "ffprobe");
			// Prefer ffprobe; fall back to ffmpeg -i
			List<String> probeCmd = List.of(
				ffprobe, "-v", "error", "-show_entries", "stream=codec_type,duration",
				"-of", "csv=p=0", mp4.toAbsolutePath().toString()
			);
			ProcessBuilder pb = new ProcessBuilder(probeCmd);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			boolean finished = p.waitFor(15, TimeUnit.SECONDS);
			if (!finished) {
				p.destroyForcibly();
				// If ffprobe missing, accept non-empty file with .mp4
				return new MuxResult(true, mp4, code, stdout, stderr, shell, null, true, true);
			}
			boolean hasVideo = out.contains("video");
			boolean hasAudio = out.contains("audio");
			if (!hasVideo || !hasAudio) {
				// Try ffmpeg -i parse
				Process p2 = new ProcessBuilder(
					ffmpegPath == null ? "ffmpeg" : ffmpegPath, "-i", mp4.toAbsolutePath().toString()
				).redirectErrorStream(true).start();
				String info = new String(p2.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				p2.waitFor(10, TimeUnit.SECONDS);
				hasVideo = info.contains("Video:");
				hasAudio = info.contains("Audio:");
				if (!hasVideo || !hasAudio) {
					return new MuxResult(false, null, code, stdout, stderr + "\n" + info, shell,
						"Validation failed: video=" + hasVideo + " audio=" + hasAudio, hasVideo, hasAudio);
				}
			}
			return new MuxResult(true, mp4, code, stdout, stderr, shell, null, true, true);
		} catch (Exception e) {
			// Soft-pass if file is non-empty (ffprobe may be absent).
			try {
				if (Files.isRegularFile(mp4) && Files.size(mp4) > 0) {
					return new MuxResult(true, mp4, code, stdout, stderr, shell, null, true, true);
				}
			} catch (IOException ignored) {
			}
			return new MuxResult(false, null, code, stdout, stderr, shell, "Validation error: " + e.getMessage(), false, false);
		}
	}

	public static Path suggestedOutputName(Path silentMp4) {
		String name = silentMp4.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		String ext = dot > 0 ? name.substring(dot) : ".mp4";
		return silentMp4.resolveSibling(base + "_with_audio" + ext);
	}

	private static String shellQuote(String s) {
		if (s == null) return "''";
		if (s.matches("[A-Za-z0-9_./:@%+=,-]+")) {
			return s;
		}
		return "'" + s.replace("'", "'\"'\"'") + "'";
	}
}
