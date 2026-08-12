package com.peterwolf.replayaudio.timeline;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction over ReplayMod / Flashback timeline clocks.
 * Common audio code must never reference replay-mod classes directly.
 */
public interface ReplayAudioTimeline {
	long getReplayTimeMicroseconds();

	boolean isPaused();

	double getPlaybackSpeed();

	List<AudioTimelineSegment> getExportSegments();

	Optional<String> getReplayIdentifier();

	default Optional<String> getReplayDisplayName() {
		return getReplayIdentifier();
	}

	default long getReplayDurationMicroseconds() {
		return -1L;
	}

	default boolean isReverse() {
		return getPlaybackSpeed() < 0;
	}
}
