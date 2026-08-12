package com.peterwolf.replayaudio.metadata;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.peterwolf.replayaudio.sync.SyncAnchor;
import com.peterwolf.replayaudio.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON metadata read/write with schema migration and atomic writes. */
public final class MetadataIo {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private MetadataIo() {
	}

	public static void writeAtomic(Path path, RecordingMetadata metadata) throws IOException {
		AtomicFiles.writeStringAtomic(path, toJson(metadata));
	}

	public static RecordingMetadata read(Path path) throws IOException {
		String raw = Files.readString(path, StandardCharsets.UTF_8);
		return fromJson(raw).migrate();
	}

	public static String toJson(RecordingMetadata m) {
		JsonObject o = new JsonObject();
		o.addProperty("schemaVersion", m.schemaVersion());
		o.addProperty("audioId", m.audioId());
		o.addProperty("replayId", m.replayId());
		o.addProperty("integration", m.integration());
		o.addProperty("audioFile", m.audioFile());
		o.addProperty("codec", m.codec());
		o.addProperty("container", m.container());
		o.addProperty("sampleRate", m.sampleRate());
		o.addProperty("channels", m.channels());
		o.addProperty("bitrate", m.bitrate());
		o.addProperty("recordingStart", m.recordingStart());
		o.addProperty("recordingEnd", m.recordingEnd());
		o.addProperty("firstSampleReplayTime", m.firstSampleReplayTime());
		o.addProperty("totalSamples", m.totalSamples());
		o.addProperty("durationMicroseconds", m.durationMicroseconds());
		o.add("syncAnchors", anchorsToJson(m.syncAnchors()));
		o.add("audioCategories", categoriesToJson(m.audioCategories()));
		o.addProperty("complete", m.complete());
		o.addProperty("checksum", m.checksum());
		o.addProperty("manualOffsetMilliseconds", m.manualOffsetMilliseconds());
		o.addProperty("minecraftVersion", m.minecraftVersion());
		o.addProperty("replayModVersion", m.replayModVersion());
		o.addProperty("worldOrServerId", m.worldOrServerId());
		o.addProperty("replayFileName", m.replayFileName());
		o.addProperty("captureStrategy", m.captureStrategy());
		o.addProperty("droppedSamples", m.droppedSamples());
		o.addProperty("recoveryNotes", m.recoveryNotes());
		return GSON.toJson(o);
	}

	public static RecordingMetadata fromJson(String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		RecordingMetadata m = new RecordingMetadata();
		m.setSchemaVersion(getInt(o, "schemaVersion", 1));
		m.setAudioId(getString(o, "audioId", m.audioId()));
		m.setReplayId(getString(o, "replayId", ""));
		m.setIntegration(getString(o, "integration", "manual"));
		m.setAudioFile(getString(o, "audioFile", ""));
		m.setCodec(getString(o, "codec", "opus"));
		m.setContainer(getString(o, "container", "ogg"));
		m.setSampleRate(getInt(o, "sampleRate", 48_000));
		m.setChannels(getInt(o, "channels", 2));
		m.setBitrate(getInt(o, "bitrate", 160_000));
		m.setRecordingStart(getLong(o, "recordingStart", 0));
		m.setRecordingEnd(getLong(o, "recordingEnd", 0));
		m.setFirstSampleReplayTime(getLong(o, "firstSampleReplayTime", 0));
		m.setTotalSamples(getLong(o, "totalSamples", 0));
		m.setDurationMicroseconds(getLong(o, "durationMicroseconds", 0));
		m.setSyncAnchors(anchorsFromJson(o.get("syncAnchors")));
		m.setAudioCategories(categoriesFromJson(o.get("audioCategories")));
		m.setComplete(o.has("complete") && o.get("complete").getAsBoolean());
		m.setChecksum(getString(o, "checksum", ""));
		m.setManualOffsetMilliseconds(getLong(o, "manualOffsetMilliseconds", 0));
		m.setMinecraftVersion(getString(o, "minecraftVersion", ""));
		m.setReplayModVersion(getString(o, "replayModVersion", ""));
		m.setWorldOrServerId(getString(o, "worldOrServerId", ""));
		m.setReplayFileName(getString(o, "replayFileName", ""));
		m.setCaptureStrategy(getString(o, "captureStrategy", "shadow_mixer"));
		m.setDroppedSamples(getLong(o, "droppedSamples", 0));
		m.setRecoveryNotes(getString(o, "recoveryNotes", ""));
		return m;
	}

	private static JsonArray anchorsToJson(List<SyncAnchor> anchors) {
		JsonArray arr = new JsonArray();
		for (SyncAnchor a : anchors) {
			JsonObject o = new JsonObject();
			o.addProperty("replayTimeMicroseconds", a.replayTimeMicroseconds());
			o.addProperty("audioSampleIndex", a.audioSampleIndex());
			o.addProperty("gameTick", a.gameTick());
			o.addProperty("monotonicTimeNanoseconds", a.monotonicTimeNanoseconds());
			o.addProperty("reason", a.reason());
			arr.add(o);
		}
		return arr;
	}

	private static List<SyncAnchor> anchorsFromJson(JsonElement el) {
		List<SyncAnchor> list = new ArrayList<>();
		if (el == null || !el.isJsonArray()) {
			return list;
		}
		for (JsonElement item : el.getAsJsonArray()) {
			if (!item.isJsonObject()) continue;
			JsonObject o = item.getAsJsonObject();
			list.add(new SyncAnchor(
				getLong(o, "replayTimeMicroseconds", 0),
				getLong(o, "audioSampleIndex", 0),
				getLong(o, "gameTick", 0),
				getLong(o, "monotonicTimeNanoseconds", 0),
				getString(o, "reason", "periodic")
			));
		}
		return list;
	}

	private static JsonObject categoriesToJson(Map<String, Boolean> map) {
		JsonObject o = new JsonObject();
		for (Map.Entry<String, Boolean> e : map.entrySet()) {
			o.addProperty(e.getKey(), e.getValue());
		}
		return o;
	}

	private static Map<String, Boolean> categoriesFromJson(JsonElement el) {
		Map<String, Boolean> map = new LinkedHashMap<>();
		if (el == null || !el.isJsonObject()) {
			return map;
		}
		for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
			map.put(e.getKey(), e.getValue().getAsBoolean());
		}
		return map;
	}

	private static String getString(JsonObject o, String key, String def) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
	}

	private static int getInt(JsonObject o, String key, int def) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
	}

	private static long getLong(JsonObject o, String key, long def) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : def;
	}
}
