package com.peterwolf.replayaudio.sync;

import com.peterwolf.replayaudio.audio.AudioConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SyncClockTest {
	@Test
	void mapsReplayTimeToSamplesWithAnchors() {
		SyncClock clock = new SyncClock();
		clock.addSamples(48_000);
		clock.addAnchor("mid");
		clock.addSamples(48_000);
		clock.addAnchor("end");
		long mid = clock.sampleIndexForReplayTime(clock.anchors().get(1).replayTimeMicroseconds());
		assertTrue(mid >= 0);
		long back = clock.replayTimeForSampleIndex(mid);
		assertTrue(back >= 0);
	}

	@Test
	void pauseDoesNotAdvanceIdealSamplesWhilePaused() throws Exception {
		SyncClock clock = new SyncClock();
		long t0 = clock.replayTimeMicrosecondsNow();
		clock.pause();
		Thread.sleep(30);
		long t1 = clock.replayTimeMicrosecondsNow();
		assertTrue(Math.abs(t1 - t0) < 15_000, "paused clock should barely advance");
		clock.resume();
	}

	@Test
	void samplesToMicrosRoundTrip() {
		long samples = 480_000;
		long micros = AudioConstants.samplesToMicros(samples);
		assertEquals(samples, AudioConstants.microsToSamples(micros));
	}

	@Test
	void driftEstimateFinite() {
		SyncClock clock = new SyncClock();
		clock.addSamples(1000);
		clock.addAnchor("a");
		assertDoesNotThrow(clock::estimatedDriftMicros);
	}
}
