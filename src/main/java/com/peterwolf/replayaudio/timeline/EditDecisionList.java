package com.peterwolf.replayaudio.timeline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Edit decision list for cuts, ranges, speed changes, and reverse detection. */
public final class EditDecisionList {
	private final List<AudioTimelineSegment> segments;
	private final boolean reverseAudioEnabled;
	private final int fadeMilliseconds;

	public EditDecisionList(List<AudioTimelineSegment> segments, boolean reverseAudioEnabled, int fadeMilliseconds) {
		this.segments = segments == null ? List.of() : List.copyOf(segments);
		this.reverseAudioEnabled = reverseAudioEnabled;
		this.fadeMilliseconds = Math.max(3, Math.min(10, fadeMilliseconds));
	}

	public List<AudioTimelineSegment> segments() {
		return segments;
	}

	public boolean reverseAudioEnabled() {
		return reverseAudioEnabled;
	}

	public int fadeMilliseconds() {
		return fadeMilliseconds;
	}

	public long totalOutputDurationMicros() {
		long end = 0;
		for (AudioTimelineSegment s : segments) {
			end = Math.max(end, s.outputStartMicros() + s.outputDurationMicros());
		}
		return end;
	}

	public boolean hasReverseSegments() {
		for (AudioTimelineSegment s : segments) {
			if (s.reverse() || s.speed() < 0) {
				return true;
			}
		}
		return false;
	}

	public static EditDecisionList singleRange(long startMicros, long endMicros, int fadeMs) {
		if (endMicros < startMicros) {
			long t = startMicros;
			startMicros = endMicros;
			endMicros = t;
		}
		return new EditDecisionList(
			List.of(new AudioTimelineSegment(startMicros, endMicros, 0, 1.0, false)),
			false,
			fadeMs
		);
	}

	public static EditDecisionList normalize(List<AudioTimelineSegment> raw, boolean reverseAudioEnabled, int fadeMs) {
		List<AudioTimelineSegment> cleaned = new ArrayList<>();
		long nextOut = 0;
		for (AudioTimelineSegment s : raw) {
			if (s == null) continue;
			long srcStart = s.sourceStartMicros();
			long srcEnd = s.sourceEndMicros();
			if (srcEnd <= srcStart && !s.reverse()) {
				continue; // empty
			}
			boolean reverse = s.reverse() || s.speed() < 0;
			double speed = Math.abs(s.speed());
			if (speed <= 0) speed = 1.0;
			// Reject overlapping invalid output by packing sequentially when outputStart is negative.
			long outStart = s.outputStartMicros() >= 0 ? s.outputStartMicros() : nextOut;
			AudioTimelineSegment fixed = new AudioTimelineSegment(srcStart, srcEnd, outStart, reverse ? -speed : speed, reverse);
			// If reverse audio disabled, replace reverse with silence-duration placeholder segment (source empty).
			if (reverse && !reverseAudioEnabled) {
				fixed = new AudioTimelineSegment(srcStart, srcStart, outStart, 1.0, false);
				// Keep duration via a synthetic zero-source segment with explicit output duration encoding:
				// represented as speed-adjusted empty source is not enough; store as silent gap using speed meta.
				// Use a dedicated silent segment by zero source length but track desired out duration via speed field abuse avoided:
				// Instead keep reverse flag false and use source length equal to desired output at speed 1.
				long outDur = Math.round(Math.abs(srcEnd - srcStart) / speed);
				fixed = new AudioTimelineSegment(srcStart, srcStart + outDur, outStart, 1.0, false);
				// Source audio is not played (caller must treat zero-gain silence when reverse disabled).
				// Mark by reverse=true still? Task: do not play forward audio over reverse video.
				// We emit a silent segment of correct duration by using empty source and custom marker speed=0 meaning silence.
				fixed = new AudioTimelineSegment(srcStart, srcStart, outStart, 0.0, true);
			}
			cleaned.add(fixed);
			nextOut = Math.max(nextOut, fixed.outputStartMicros() + Math.max(0, fixed.outputDurationMicros()));
		}
		return new EditDecisionList(cleaned, reverseAudioEnabled, fadeMs);
	}

	public String toJson() {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		JsonObject root = new JsonObject();
		JsonArray arr = new JsonArray();
		for (AudioTimelineSegment s : segments) {
			JsonObject o = new JsonObject();
			o.addProperty("sourceStart", s.sourceStartMicros());
			o.addProperty("sourceEnd", s.sourceEndMicros());
			o.addProperty("outputStart", s.outputStartMicros());
			o.addProperty("speed", s.speed());
			o.addProperty("reverse", s.reverse());
			arr.add(o);
		}
		root.add("segments", arr);
		root.addProperty("reverseAudioEnabled", reverseAudioEnabled);
		root.addProperty("fadeMilliseconds", fadeMilliseconds);
		return gson.toJson(root);
	}

	public static EditDecisionList fromJson(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		List<AudioTimelineSegment> list = new ArrayList<>();
		if (root.has("segments") && root.get("segments").isJsonArray()) {
			for (JsonElement el : root.getAsJsonArray("segments")) {
				JsonObject o = el.getAsJsonObject();
				list.add(new AudioTimelineSegment(
					o.get("sourceStart").getAsLong(),
					o.get("sourceEnd").getAsLong(),
					o.get("outputStart").getAsLong(),
					o.has("speed") ? o.get("speed").getAsDouble() : 1.0,
					o.has("reverse") && o.get("reverse").getAsBoolean()
				));
			}
		}
		boolean rev = root.has("reverseAudioEnabled") && root.get("reverseAudioEnabled").getAsBoolean();
		int fade = root.has("fadeMilliseconds") ? root.get("fadeMilliseconds").getAsInt() : 5;
		return new EditDecisionList(list, rev, fade);
	}

	public static EditDecisionList empty() {
		return new EditDecisionList(Collections.emptyList(), false, 5);
	}
}
