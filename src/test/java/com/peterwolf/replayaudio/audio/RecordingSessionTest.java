package com.peterwolf.replayaudio.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecordingSessionTest {
	@TempDir Path tmp;

	@Test
	void recordsAndFinalizesMetadata() throws Exception {
		RecordingSession session = new RecordingSession(64);
		session.start(tmp, "manual", "id-1", "test_replay", RecordingQuality.HIGH,
			AudioFormatChoice.OPUS_OGG, Map.of("music", true), true, "26.2", "", "world");
		assertTrue(session.isActive());
		short[] frame = new short[AudioConstants.FRAME_SAMPLES * 2];
		for (int n = 0; n < 30; n++) {
			for (int i = 0; i < AudioConstants.FRAME_SAMPLES; i++) {
				short s = (short) (Math.sin(2 * Math.PI * 880 * i / 48000.0) * 8000);
				frame[i * 2] = s;
				frame[i * 2 + 1] = s;
			}
			session.offerPcm(frame, AudioConstants.FRAME_SAMPLES);
		}
		Thread.sleep(200);
		var meta = session.stop();
		assertTrue(meta.complete());
		assertTrue(Files.isRegularFile(session.metadataPath()));
		assertEquals("opus", meta.codec());
		assertEquals(48_000, meta.sampleRate());
		assertFalse(meta.syncAnchors().isEmpty());
	}

	@Test
	void manualDoesNotRequireReplayId() throws Exception {
		RecordingSession session = new RecordingSession(32);
		session.start(tmp, "manual", "", "manual", RecordingQuality.LOW,
			AudioFormatChoice.OPUS_OGG, Map.of(), true, "26.2", "", "");
		session.stop();
		assertEquals(RecordingSession.State.STOPPED, session.state());
	}
}
