package com.peterwolf.replayaudio.audio;

import com.peterwolf.replayaudio.timeline.AudioTimelineSegment;
import com.peterwolf.replayaudio.timeline.EditDecisionList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds export audio from a source PCM or decoded stream according to an EDL.
 * Source audio is provided as interleaved s16le stereo at 48 kHz.
 */
public final class AudioEditProcessor {
	private AudioEditProcessor() {
	}

	public static short[] apply(short[] sourceInterleaved, EditDecisionList edl) {
		if (sourceInterleaved == null) {
			sourceInterleaved = new short[0];
		}
		if (edl == null || edl.segments().isEmpty()) {
			return sourceInterleaved.clone();
		}
		long outMicros = edl.totalOutputDurationMicros();
		int outFrames = (int) Math.max(0, AudioConstants.microsToSamples(outMicros));
		short[] output = new short[outFrames * AudioConstants.CHANNELS];
		int fadeSamples = (int) (edl.fadeMilliseconds() * (AudioConstants.SAMPLE_RATE / 1000.0));

		List<AudioTimelineSegment> segments = edl.segments();
		for (int si = 0; si < segments.size(); si++) {
			AudioTimelineSegment seg = segments.get(si);
			// Silent reverse placeholder: reverse=true and speed==0 means silence of output duration from outputStart.
			if (seg.reverse() && seg.speed() == 0.0) {
				// leave silence; duration was packed into adjacent handling — use source range as duration marker
				// For our normalize path, reverse-disabled segments use sourceStart==sourceEnd and reverse=true speed=0
				// without known duration. Skip.
				continue;
			}
			int srcStart = (int) Math.max(0, AudioConstants.microsToSamples(seg.sourceStartMicros()));
			int srcEnd = (int) Math.max(srcStart, AudioConstants.microsToSamples(seg.sourceEndMicros()));
			srcEnd = Math.min(srcEnd, sourceInterleaved.length / 2);
			srcStart = Math.min(srcStart, srcEnd);
			short[] slice = sliceFrames(sourceInterleaved, srcStart, srcEnd);
			if (seg.reverse() && edl.reverseAudioEnabled()) {
				slice = TimeStretcher.reverse(slice);
			} else if (seg.reverse() && !edl.reverseAudioEnabled()) {
				slice = new short[slice.length]; // silence of same source length then stretch
			}
			double speed = Math.abs(seg.speed() <= 0 ? 1.0 : seg.speed());
			if (Math.abs(speed - 1.0) > 1e-6) {
				if (TimeStretcher.isSupportedSpeed(speed)) {
					slice = TimeStretcher.stretch(slice, speed);
				} else {
					slice = TimeStretcher.stretch(slice, speed); // resample fallback inside
				}
			}
			boolean hardIn = si == 0 || true;
			boolean hardOut = true;
			if (hardIn || hardOut) {
				slice = TimeStretcher.applyFade(slice, hardIn ? fadeSamples : 0, hardOut ? fadeSamples : 0);
			}
			int dest = (int) AudioConstants.microsToSamples(seg.outputStartMicros());
			mixInto(output, dest, slice);
		}
		return output;
	}

	public static Path writeWav(Path path, short[] interleaved) throws IOException {
		Files.createDirectories(path.getParent());
		int dataLen = interleaved.length * 2;
		ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		header.put("RIFF".getBytes());
		header.putInt(36 + dataLen);
		header.put("WAVE".getBytes());
		header.put("fmt ".getBytes());
		header.putInt(16);
		header.putShort((short) 1);
		header.putShort((short) AudioConstants.CHANNELS);
		header.putInt(AudioConstants.SAMPLE_RATE);
		header.putInt(AudioConstants.SAMPLE_RATE * AudioConstants.CHANNELS * 2);
		header.putShort((short) (AudioConstants.CHANNELS * 2));
		header.putShort((short) 16);
		header.put("data".getBytes());
		header.putInt(dataLen);
		byte[] pcm = new byte[dataLen];
		ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(interleaved);
		Files.write(path, concat(header.array(), pcm));
		return path;
	}

	public static short[] readWavPcm(Path path) throws IOException {
		byte[] all = Files.readAllBytes(path);
		if (all.length < 44) {
			return new short[0];
		}
		// Minimal WAV reader: find "data" chunk.
		int dataPos = 12;
		int dataSize = 0;
		while (dataPos + 8 <= all.length) {
			String id = new String(all, dataPos, 4);
			int size = ByteBuffer.wrap(all, dataPos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
			if ("data".equals(id)) {
				dataSize = size;
				dataPos += 8;
				break;
			}
			dataPos += 8 + size;
		}
		if (dataSize <= 0 || dataPos + dataSize > all.length) {
			// Assume raw after 44-byte header.
			dataPos = 44;
			dataSize = all.length - 44;
		}
		int samples = dataSize / 2;
		short[] out = new short[samples];
		ByteBuffer.wrap(all, dataPos, samples * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
		return out;
	}

	/** Decode Ogg Opus via FFmpeg into PCM when available. */
	public static short[] decodeOpusToPcm(Path opusFile, String ffmpegPath) throws IOException, InterruptedException {
		Path tmp = Files.createTempFile("replayaudio-decode-", ".wav");
		try {
			List<String> cmd = new ArrayList<>();
			cmd.add(ffmpegPath == null ? "ffmpeg" : ffmpegPath);
			cmd.add("-hide_banner");
			cmd.add("-loglevel");
			cmd.add("error");
			cmd.add("-y");
			cmd.add("-i");
			cmd.add(opusFile.toAbsolutePath().toString());
			cmd.add("-f");
			cmd.add("wav");
			cmd.add("-acodec");
			cmd.add("pcm_s16le");
			cmd.add("-ac");
			cmd.add("2");
			cmd.add("-ar");
			cmd.add(String.valueOf(AudioConstants.SAMPLE_RATE));
			cmd.add(tmp.toAbsolutePath().toString());
			Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.PIPE).start();
			int code = p.waitFor();
			if (code != 0) {
				String err = new String(p.getErrorStream().readAllBytes());
				throw new IOException("FFmpeg decode failed: " + err);
			}
			return readWavPcm(tmp);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	private static short[] sliceFrames(short[] src, int startFrame, int endFrame) {
		int frames = Math.max(0, endFrame - startFrame);
		short[] out = new short[frames * 2];
		System.arraycopy(src, startFrame * 2, out, 0, frames * 2);
		return out;
	}

	private static void mixInto(short[] dest, int destFrame, short[] src) {
		int destFrames = dest.length / 2;
		int srcFrames = src.length / 2;
		for (int i = 0; i < srcFrames; i++) {
			int d = destFrame + i;
			if (d < 0 || d >= destFrames) continue;
			int dl = dest[d * 2] + src[i * 2];
			int dr = dest[d * 2 + 1] + src[i * 2 + 1];
			dest[d * 2] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, dl));
			dest[d * 2 + 1] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, dr));
		}
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] c = new byte[a.length + b.length];
		System.arraycopy(a, 0, c, 0, a.length);
		System.arraycopy(b, 0, c, a.length, b.length);
		return c;
	}
}
