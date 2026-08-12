package com.peterwolf.replayaudio.export;

import com.peterwolf.replayaudio.audio.AudioConstants;
import com.peterwolf.replayaudio.audio.AudioEditProcessor;
import com.peterwolf.replayaudio.metadata.RecordingMetadata;
import com.peterwolf.replayaudio.timeline.EditDecisionList;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Full export pipeline: EDL transform → temporary audio → FFmpeg mux (video copy, AAC audio).
 */
public final class ExportPipeline {
	public record ExportRequest(
		Path sourceAudio,
		RecordingMetadata metadata,
		EditDecisionList edl,
		Path silentVideoMp4,
		Path outputDirectory,
		String ffmpegPath,
		int aacBitrate,
		boolean keepOriginalSilentMp4,
		boolean keepProcessedAudio
	) {
	}

	public record ExportResult(
		boolean success,
		Path processedAudio,
		Path outputMp4,
		FfmpegMuxer.MuxResult mux,
		String message
	) {
	}

	private ExportPipeline() {
	}

	public static ExportResult run(ExportRequest req) {
		try {
			if (req.sourceAudio() == null || !Files.isRegularFile(req.sourceAudio())) {
				return new ExportResult(false, null, null, null, "No source audio for export");
			}
			if (req.silentVideoMp4() == null || !Files.isRegularFile(req.silentVideoMp4())) {
				return new ExportResult(false, null, null, null, "Silent video MP4 not found");
			}
			Files.createDirectories(req.outputDirectory());
			String base = req.silentVideoMp4().getFileName().toString();
			int dot = base.lastIndexOf('.');
			if (dot > 0) base = base.substring(0, dot);
			Path processed = req.outputDirectory().resolve(base + ".audio-processing.tmp.wav");
			Path finalAudioKeep = req.outputDirectory().resolve(base + ".processed-audio.wav");

			// Decode source (opus/ogg) via ffmpeg when needed; if already wav, read directly.
			short[] pcm;
			String name = req.sourceAudio().getFileName().toString().toLowerCase();
			if (name.endsWith(".wav")) {
				pcm = AudioEditProcessor.readWavPcm(req.sourceAudio());
			} else {
				pcm = AudioEditProcessor.decodeOpusToPcm(req.sourceAudio(), req.ffmpegPath());
			}

			// Apply manual offset from metadata.
			long offsetSamples = 0;
			if (req.metadata() != null) {
				offsetSamples = req.metadata().manualOffsetMilliseconds() * AudioConstants.SAMPLE_RATE / 1000L;
			}
			if (offsetSamples != 0) {
				pcm = applyOffset(pcm, (int) offsetSamples);
			}

			EditDecisionList edl = req.edl() == null ? EditDecisionList.singleRange(0,
				AudioConstants.samplesToMicros(pcm.length / 2L), 5) : req.edl();
			short[] outPcm = AudioEditProcessor.apply(pcm, edl);
			AudioEditProcessor.writeWav(processed, outPcm);

			Path outMp4 = FfmpegMuxer.suggestedOutputName(req.silentVideoMp4());
			if (req.outputDirectory() != null) {
				outMp4 = req.outputDirectory().resolve(outMp4.getFileName());
			}

			FfmpegMuxer.MuxResult mux = FfmpegMuxer.mux(new FfmpegMuxer.MuxRequest(
				req.silentVideoMp4(),
				processed,
				outMp4,
				req.ffmpegPath(),
				req.aacBitrate() <= 0 ? 192_000 : req.aacBitrate(),
				req.keepOriginalSilentMp4()
			));

			if (mux.success()) {
				if (req.keepProcessedAudio()) {
					Files.move(processed, finalAudioKeep);
					processed = finalAudioKeep;
				} else {
					Files.deleteIfExists(processed);
					processed = null;
				}
				return new ExportResult(true, processed, mux.output(), mux, "Muxed audio into MP4 (video stream copied)");
			}

			// Keep processed audio for manual ffmpeg
			Path kept = finalAudioKeep;
			Files.move(processed, kept);
			String msg = "Mux failed: " + mux.failureReason() + ". Processed audio kept at " + kept
				+ ". Suggested command: " + mux.ffmpegCommand();
			return new ExportResult(false, kept, null, mux, msg);
		} catch (Exception e) {
			return new ExportResult(false, null, null, null, "Export pipeline error: " + e.getMessage());
		}
	}

	private static short[] applyOffset(short[] pcm, int offsetSamples) {
		if (offsetSamples == 0) return pcm;
		int frames = pcm.length / 2;
		if (offsetSamples > 0) {
			// delay audio: prepend silence
			short[] out = new short[(frames + offsetSamples) * 2];
			System.arraycopy(pcm, 0, out, offsetSamples * 2, pcm.length);
			return out;
		}
		// negative: skip samples
		int skip = Math.min(frames, -offsetSamples);
		int remain = frames - skip;
		short[] out = new short[remain * 2];
		System.arraycopy(pcm, skip * 2, out, 0, remain * 2);
		return out;
	}
}
