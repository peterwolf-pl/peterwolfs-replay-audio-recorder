package com.peterwolf.replayaudio.audio;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Streaming Ogg Opus encoder.
 * Uses Concentus (pure Java) when available; otherwise falls back to an FFmpeg PCM→Opus process.
 */
public final class OggOpusEncoder implements Closeable {
	public enum Backend { CONCENTUS, FFMPEG, RAW_PCM_FALLBACK }

	private final Path outputPath;
	private final int bitrate;
	private final Backend backend;
	private final Object encodeLock = new Object();

	private ConcentusBackend concentus;
	private FfmpegBackend ffmpeg;
	private RawPcmBackend raw;
	private long totalFramesEncoded;
	private boolean closed;
	private boolean finalized;
	private String initError;

	public OggOpusEncoder(Path outputPath, int bitrate) throws IOException {
		this.outputPath = outputPath;
		this.bitrate = bitrate <= 0 ? AudioConstants.DEFAULT_BITRATE : bitrate;
		Files.createDirectories(outputPath.getParent());
		Backend chosen;
		try {
			this.concentus = new ConcentusBackend(outputPath, this.bitrate);
			chosen = Backend.CONCENTUS;
		} catch (Throwable concentusFailed) {
			this.concentus = null;
			try {
				this.ffmpeg = new FfmpegBackend(outputPath, this.bitrate);
				chosen = Backend.FFMPEG;
			} catch (Throwable ffmpegFailed) {
				this.ffmpeg = null;
				// Last resort: write a .pcm sidecar-compatible stream with .ogg extension replacement avoided.
				// We still produce a valid-enough container recovery marker via raw PCM file + .opuspcm note.
				Path pcmPath = outputPath.resolveSibling(outputPath.getFileName().toString().replace(".ogg", ".s16le.pcm"));
				this.raw = new RawPcmBackend(pcmPath);
				chosen = Backend.RAW_PCM_FALLBACK;
				this.initError = "Concentus unavailable (" + concentusFailed.getClass().getSimpleName()
					+ ") and FFmpeg unavailable (" + ffmpegFailed.getClass().getSimpleName()
					+ "); writing raw PCM for recovery.";
			}
		}
		this.backend = chosen;
	}

	public Backend backend() {
		return backend;
	}

	public String initError() {
		return initError;
	}

	public Path outputPath() {
		return outputPath;
	}

	public long totalFramesEncoded() {
		return totalFramesEncoded;
	}

	public void encodeInterleavedS16(short[] interleaved, int frameCount) throws IOException {
		if (closed || finalized || frameCount <= 0) {
			return;
		}
		synchronized (encodeLock) {
			if (concentus != null) {
				concentus.encode(interleaved, frameCount);
			} else if (ffmpeg != null) {
				ffmpeg.encode(interleaved, frameCount);
			} else if (raw != null) {
				raw.encode(interleaved, frameCount);
			}
			totalFramesEncoded += frameCount;
		}
	}

	public void flush() throws IOException {
		synchronized (encodeLock) {
			if (ffmpeg != null) {
				ffmpeg.flush();
			}
			if (raw != null) {
				raw.flush();
			}
		}
	}

	public void finish() throws IOException {
		synchronized (encodeLock) {
			if (finalized) return;
			if (concentus != null) {
				concentus.finish();
			} else if (ffmpeg != null) {
				ffmpeg.finish();
			} else if (raw != null) {
				raw.finish();
			}
			finalized = true;
		}
	}

	@Override
	public void close() throws IOException {
		synchronized (encodeLock) {
			if (closed) return;
			try {
				if (!finalized) {
					finish();
				}
			} finally {
				closed = true;
				if (concentus != null) concentus.close();
				if (ffmpeg != null) ffmpeg.close();
				if (raw != null) raw.close();
			}
		}
	}

	// ---- Concentus backend ----

	private static final class ConcentusBackend implements Closeable {
		private final OutputStream out;
		private final Object encoder;
		private final java.lang.reflect.Method encode;
		private long granule;
		private int pageSequence;
		private final int serial;
		private final short[] remainder = new short[AudioConstants.FRAME_SAMPLES * AudioConstants.CHANNELS];
		private int remainderFrames;
		private boolean headerWritten;

		ConcentusBackend(Path path, int bitrate) throws Exception {
			this.out = new BufferedOutputStream(Files.newOutputStream(path));
			this.serial = ThreadLocalRandom.current().nextInt();
			Class<?> encoderClass = Class.forName("org.concentus.OpusEncoder");
			Class<?> appClass = Class.forName("org.concentus.OpusApplication");
			@SuppressWarnings({"unchecked", "rawtypes"})
			Object app = Enum.valueOf((Class<? extends Enum>) appClass.asSubclass(Enum.class), "OPUS_APPLICATION_AUDIO");
			this.encoder = encoderClass
				.getConstructor(int.class, int.class, appClass)
				.newInstance(AudioConstants.SAMPLE_RATE, AudioConstants.CHANNELS, app);
			encoderClass.getMethod("setBitrate", int.class).invoke(encoder, bitrate);
			try {
				encoderClass.getMethod("setSignalType", Class.forName("org.concentus.OpusSignal"))
					.invoke(encoder, Enum.valueOf(Class.forName("org.concentus.OpusSignal").asSubclass(Enum.class), "OPUS_SIGNAL_MUSIC"));
			} catch (ReflectiveOperationException ignored) {
				// optional
			}
			this.encode = encoderClass.getMethod("encode", short[].class, int.class, int.class, byte[].class, int.class, int.class);
			writeIdHeader();
			writeCommentHeader();
			headerWritten = true;
		}

		void encode(short[] interleaved, int frameCount) throws IOException {
			int offsetFrames = 0;
			while (offsetFrames < frameCount) {
				int need = AudioConstants.FRAME_SAMPLES - remainderFrames;
				int take = Math.min(need, frameCount - offsetFrames);
				System.arraycopy(
					interleaved,
					offsetFrames * AudioConstants.CHANNELS,
					remainder,
					remainderFrames * AudioConstants.CHANNELS,
					take * AudioConstants.CHANNELS
				);
				remainderFrames += take;
				offsetFrames += take;
				if (remainderFrames == AudioConstants.FRAME_SAMPLES) {
					encodeFullFrame(remainder);
					remainderFrames = 0;
				}
			}
		}

		private void encodeFullFrame(short[] frame) throws IOException {
			try {
				byte[] packet = new byte[4000];
				int len = (Integer) encode.invoke(encoder, frame, 0, AudioConstants.FRAME_SAMPLES, packet, 0, packet.length);
				if (len > 0) {
					granule += AudioConstants.FRAME_SAMPLES;
					writeOggPage(new byte[][]{copy(packet, len)}, granule, false);
				}
			} catch (ReflectiveOperationException e) {
				throw new IOException("Concentus encode failed", e);
			}
		}

		void finish() throws IOException {
			if (remainderFrames > 0) {
				short[] padded = new short[AudioConstants.FRAME_SAMPLES * AudioConstants.CHANNELS];
				System.arraycopy(remainder, 0, padded, 0, remainderFrames * AudioConstants.CHANNELS);
				encodeFullFrame(padded);
				remainderFrames = 0;
			}
			// EOS page
			writeOggPage(new byte[][]{new byte[0]}, granule, true);
			out.flush();
		}

		private void writeIdHeader() throws IOException {
			ByteBuffer id = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
			id.put("OpusHead".getBytes(StandardCharsets.US_ASCII));
			id.put((byte) 1); // version
			id.put((byte) AudioConstants.CHANNELS);
			id.putShort((short) 0); // pre-skip
			id.putInt(AudioConstants.SAMPLE_RATE);
			id.putShort((short) 0); // output gain
			id.put((byte) 0); // channel mapping family
			writeOggPage(new byte[][]{id.array()}, 0, false);
		}

		private void writeCommentHeader() throws IOException {
			byte[] vendor = "PeterWolfReplayAudioRecorder".getBytes(StandardCharsets.UTF_8);
			ByteBuffer comments = ByteBuffer.allocate(8 + 4 + vendor.length + 4).order(ByteOrder.LITTLE_ENDIAN);
			comments.put("OpusTags".getBytes(StandardCharsets.US_ASCII));
			comments.putInt(vendor.length);
			comments.put(vendor);
			comments.putInt(0);
			writeOggPage(new byte[][]{comments.array()}, 0, false);
		}

		private void writeOggPage(byte[][] packets, long granulePos, boolean eos) throws IOException {
			int dataLen = 0;
			for (byte[] p : packets) dataLen += p.length;
			int segments = 0;
			for (byte[] p : packets) {
				segments += Math.max(1, (p.length + 255) / 255);
				if (p.length == 0) segments = Math.max(segments, 1);
			}
			byte[] segmentTable = new byte[segments];
			int si = 0;
			for (byte[] p : packets) {
				int remaining = p.length;
				if (remaining == 0) {
					segmentTable[si++] = 0;
					continue;
				}
				while (remaining >= 255) {
					segmentTable[si++] = (byte) 255;
					remaining -= 255;
				}
				segmentTable[si++] = (byte) remaining;
			}
			byte headerType = 0;
			if (pageSequence == 0) headerType |= 0x02; // BOS
			if (eos) headerType |= 0x04;
			ByteBuffer header = ByteBuffer.allocate(27 + segmentTable.length).order(ByteOrder.LITTLE_ENDIAN);
			header.put("OggS".getBytes(StandardCharsets.US_ASCII));
			header.put((byte) 0);
			header.put(headerType);
			header.putLong(granulePos);
			header.putInt(serial);
			header.putInt(pageSequence++);
			header.putInt(0); // CRC placeholder
			header.put((byte) segmentTable.length);
			header.put(segmentTable);
			byte[] headerBytes = header.array();
			byte[] page = new byte[headerBytes.length + dataLen];
			System.arraycopy(headerBytes, 0, page, 0, headerBytes.length);
			int pos = headerBytes.length;
			for (byte[] p : packets) {
				System.arraycopy(p, 0, page, pos, p.length);
				pos += p.length;
			}
			int crc = oggCrc(page);
			page[22] = (byte) (crc);
			page[23] = (byte) (crc >>> 8);
			page[24] = (byte) (crc >>> 16);
			page[25] = (byte) (crc >>> 24);
			out.write(page);
		}

		@Override
		public void close() throws IOException {
			out.close();
		}

		private static byte[] copy(byte[] src, int len) {
			byte[] out = new byte[len];
			System.arraycopy(src, 0, out, 0, len);
			return out;
		}

		private static final int[] CRC_TABLE = new int[256];
		static {
			for (int i = 0; i < 256; i++) {
				int r = i << 24;
				for (int j = 0; j < 8; j++) {
					if ((r & 0x80000000) != 0) {
						r = (r << 1) ^ 0x04c11db7;
					} else {
						r <<= 1;
					}
				}
				CRC_TABLE[i] = r;
			}
		}

		private static int oggCrc(byte[] page) {
			int crc = 0;
			for (int i = 0; i < page.length; i++) {
				// skip existing crc field
				byte b = (i >= 22 && i < 26) ? 0 : page[i];
				crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) & 0xff) ^ (b & 0xff)];
			}
			return crc;
		}
	}

	// ---- FFmpeg backend ----

	private static final class FfmpegBackend implements Closeable {
		private final Process process;
		private final OutputStream stdin;

		FfmpegBackend(Path output, int bitrate) throws IOException {
			String ffmpeg = System.getenv().getOrDefault("FFMPEG", "ffmpeg");
			ProcessBuilder pb = new ProcessBuilder(
				ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
				"-f", "s16le", "-ar", String.valueOf(AudioConstants.SAMPLE_RATE),
				"-ac", String.valueOf(AudioConstants.CHANNELS),
				"-i", "pipe:0",
				"-c:a", "libopus", "-b:a", String.valueOf(bitrate),
				"-application", "audio",
				"-f", "ogg",
				output.toAbsolutePath().toString()
			);
			pb.redirectError(ProcessBuilder.Redirect.PIPE);
			this.process = pb.start();
			this.stdin = new BufferedOutputStream(process.getOutputStream(), 64 * 1024);
		}

		void encode(short[] interleaved, int frameCount) throws IOException {
			byte[] bytes = new byte[frameCount * AudioConstants.CHANNELS * 2];
			ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(interleaved, 0, frameCount * AudioConstants.CHANNELS);
			stdin.write(bytes);
		}

		void flush() throws IOException {
			stdin.flush();
		}

		void finish() throws IOException {
			stdin.flush();
			stdin.close();
			try {
				int code = process.waitFor();
				if (code != 0) {
					String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
					throw new IOException("FFmpeg opus encode failed (" + code + "): " + err);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted waiting for FFmpeg", e);
			}
		}

		@Override
		public void close() {
			process.destroyForcibly();
		}
	}

	// ---- Raw PCM fallback ----

	private static final class RawPcmBackend implements Closeable {
		private final OutputStream out;

		RawPcmBackend(Path path) throws IOException {
			Files.createDirectories(path.getParent());
			this.out = new BufferedOutputStream(Files.newOutputStream(path));
		}

		void encode(short[] interleaved, int frameCount) throws IOException {
			byte[] bytes = new byte[frameCount * AudioConstants.CHANNELS * 2];
			ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(interleaved, 0, frameCount * AudioConstants.CHANNELS);
			out.write(bytes);
		}

		void flush() throws IOException {
			out.flush();
		}

		void finish() throws IOException {
			out.flush();
		}

		@Override
		public void close() throws IOException {
			out.close();
		}
	}
}
