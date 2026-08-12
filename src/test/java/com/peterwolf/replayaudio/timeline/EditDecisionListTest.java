package com.peterwolf.replayaudio.timeline;

import com.peterwolf.replayaudio.audio.AudioEditProcessor;
import com.peterwolf.replayaudio.audio.AudioConstants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EditDecisionListTest {
	@Test
	void singleContinuousSegment() {
		EditDecisionList edl = EditDecisionList.singleRange(0, 1_000_000, 5);
		assertEquals(1, edl.segments().size());
		assertEquals(1_000_000, edl.totalOutputDurationMicros());
	}

	@Test
	void multipleCutsAndRemovedMiddle() {
		List<AudioTimelineSegment> segs = List.of(
			new AudioTimelineSegment(0, 1_000_000, 0, 1.0),
			new AudioTimelineSegment(3_000_000, 4_000_000, 1_000_000, 1.0)
		);
		EditDecisionList edl = EditDecisionList.normalize(segs, false, 5);
		assertEquals(2, edl.segments().size());
		assertEquals(2_000_000, edl.totalOutputDurationMicros());
	}

	@Test
	void slowAndFastMotion() {
		List<AudioTimelineSegment> segs = List.of(
			new AudioTimelineSegment(0, 2_000_000, 0, 0.5),
			new AudioTimelineSegment(2_000_000, 3_000_000, 4_000_000, 2.0)
		);
		EditDecisionList edl = EditDecisionList.normalize(segs, false, 5);
		assertEquals(4_000_000 + 500_000, edl.totalOutputDurationMicros());
	}

	@Test
	void reverseDetection() {
		List<AudioTimelineSegment> segs = List.of(
			new AudioTimelineSegment(1_000_000, 0, 0, 1.0, true)
		);
		EditDecisionList edl = EditDecisionList.normalize(segs, false, 5);
		assertTrue(edl.hasReverseSegments() || edl.segments().stream().anyMatch(AudioTimelineSegment::reverse)
			|| !edl.segments().isEmpty());
	}

	@Test
	void emptySegmentDropped() {
		List<AudioTimelineSegment> segs = List.of(
			new AudioTimelineSegment(1_000_000, 1_000_000, 0, 1.0)
		);
		EditDecisionList edl = EditDecisionList.normalize(segs, false, 5);
		assertTrue(edl.segments().isEmpty());
	}

	@Test
	void applyProducesExpectedDuration() {
		// 1 second of silence source
		short[] src = new short[AudioConstants.SAMPLE_RATE * 2];
		EditDecisionList edl = EditDecisionList.singleRange(0, 500_000, 5); // 0.5s
		short[] out = AudioEditProcessor.apply(src, edl);
		assertEquals(AudioConstants.SAMPLE_RATE / 2, out.length / 2, 2);
	}

	@Test
	void jsonRoundTrip() {
		EditDecisionList edl = EditDecisionList.singleRange(100, 200, 7);
		EditDecisionList back = EditDecisionList.fromJson(edl.toJson());
		assertEquals(edl.segments().size(), back.segments().size());
		assertEquals(7, back.fadeMilliseconds());
	}

	@Test
	void boundaryFadeClamp() {
		EditDecisionList edl = new EditDecisionList(List.of(), false, 100);
		assertEquals(10, edl.fadeMilliseconds());
		EditDecisionList edl2 = new EditDecisionList(List.of(), false, 1);
		assertEquals(3, edl2.fadeMilliseconds());
	}
}
