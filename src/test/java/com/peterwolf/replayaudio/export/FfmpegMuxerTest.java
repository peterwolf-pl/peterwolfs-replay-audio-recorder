package com.peterwolf.replayaudio.export;

import com.peterwolf.replayaudio.audio.AudioConstants;
import com.peterwolf.replayaudio.audio.AudioEditProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FfmpegMuxerTest {
	@TempDir Path tmp;

	static boolean ffmpegAvailable() {
		return FfmpegMuxer.isFfmpegAvailable(System.getProperty("replayaudio.tests.ffmpeg", "ffmpeg"));
	}

	@Test
	void buildsQuotedCommandWithoutShellConcat() {
		Path v = Path.of("/tmp/my video.mp4");
		Path a = Path.of("/tmp/my audio.ogg");
		Path o = Path.of("/tmp/out with spaces.mp4");
		List<String> cmd = FfmpegMuxer.buildCommand(new FfmpegMuxer.MuxRequest(v, a, o, "ffmpeg", 192_000, true));
		assertTrue(cmd.contains("-c:v"));
		assertTrue(cmd.contains("copy"));
		assertTrue(cmd.contains("-c:a"));
		assertTrue(cmd.contains("aac"));
		assertFalse(cmd.stream().anyMatch(s -> s.contains("&&")));
		String shell = FfmpegMuxer.commandAsShell(cmd);
		assertTrue(shell.contains("'") || shell.contains("video"));
	}

	@Test
	@EnabledIf("ffmpegAvailable")
	void muxesAudioIntoGeneratedMp4() throws Exception {
		String ffmpeg = System.getProperty("replayaudio.tests.ffmpeg", "ffmpeg");
		// Generate silent video 1s
		Path video = tmp.resolve("silent.mp4");
		Process p = new ProcessBuilder(ffmpeg, "-y", "-f", "lavfi", "-i", "color=c=black:s=320x240:d=1",
			"-c:v", "libx264", "-pix_fmt", "yuv420p", video.toString())
			.redirectErrorStream(true).start();
		p.waitFor();
		assertTrue(Files.size(video) > 0);

		short[] pcm = new short[AudioConstants.SAMPLE_RATE * 2];
		for (int i = 0; i < AudioConstants.SAMPLE_RATE; i++) {
			short s = (short) (Math.sin(2 * Math.PI * 440 * i / 48000.0) * 10000);
			pcm[i * 2] = s;
			pcm[i * 2 + 1] = s;
		}
		Path audio = tmp.resolve("a.wav");
		AudioEditProcessor.writeWav(audio, pcm);
		Path out = tmp.resolve("with_audio.mp4");
		FfmpegMuxer.MuxResult r = FfmpegMuxer.mux(new FfmpegMuxer.MuxRequest(video, audio, out, ffmpeg, 192_000, true));
		assertTrue(r.success(), r.failureReason() + " / " + r.stderr());
		assertTrue(Files.isRegularFile(out));
		assertTrue(Files.size(out) > 0);
		assertTrue(r.videoCopied());
		assertTrue(r.audioPresent());
	}

	@Test
	void missingFfmpegDoesNotThrow() {
		FfmpegMuxer.MuxResult r = FfmpegMuxer.mux(new FfmpegMuxer.MuxRequest(
			tmp.resolve("no.mp4"), tmp.resolve("no.wav"), tmp.resolve("o.mp4"),
			"/nonexistent/ffmpeg-binary-xyz", 192_000, true));
		assertFalse(r.success());
		assertNotNull(r.ffmpegCommand());
	}
}
