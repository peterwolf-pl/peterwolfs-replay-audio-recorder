package com.peterwolf.replayaudio.metadata;

import com.peterwolf.replayaudio.sync.SyncAnchor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Schema versioned sidecar metadata for a recorded audio track. */
public final class RecordingMetadata {
	public static final int SCHEMA_VERSION = 1;

	private int schemaVersion = SCHEMA_VERSION;
	private String audioId = UUID.randomUUID().toString();
	private String replayId = "";
	private String integration = "manual";
	private String audioFile = "";
	private String codec = "opus";
	private String container = "ogg";
	private int sampleRate = 48_000;
	private int channels = 2;
	private int bitrate = 160_000;
	private long recordingStart;
	private long recordingEnd;
	private long firstSampleReplayTime;
	private long totalSamples;
	private long durationMicroseconds;
	private List<SyncAnchor> syncAnchors = new ArrayList<>();
	private Map<String, Boolean> audioCategories = new LinkedHashMap<>();
	private boolean complete;
	private String checksum = "";
	private long manualOffsetMilliseconds;
	private String minecraftVersion = "";
	private String replayModVersion = "";
	private String worldOrServerId = "";
	private String replayFileName = "";
	private String captureStrategy = "shadow_mixer";
	private long droppedSamples;
	private String recoveryNotes = "";

	public int schemaVersion() { return schemaVersion; }
	public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

	public String audioId() { return audioId; }
	public void setAudioId(String audioId) { this.audioId = audioId; }

	public String replayId() { return replayId; }
	public void setReplayId(String replayId) { this.replayId = Objects.requireNonNullElse(replayId, ""); }

	public String integration() { return integration; }
	public void setIntegration(String integration) { this.integration = Objects.requireNonNullElse(integration, "manual"); }

	public String audioFile() { return audioFile; }
	public void setAudioFile(String audioFile) { this.audioFile = Objects.requireNonNullElse(audioFile, ""); }

	public String codec() { return codec; }
	public void setCodec(String codec) { this.codec = codec; }

	public String container() { return container; }
	public void setContainer(String container) { this.container = container; }

	public int sampleRate() { return sampleRate; }
	public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }

	public int channels() { return channels; }
	public void setChannels(int channels) { this.channels = channels; }

	public int bitrate() { return bitrate; }
	public void setBitrate(int bitrate) { this.bitrate = bitrate; }

	public long recordingStart() { return recordingStart; }
	public void setRecordingStart(long recordingStart) { this.recordingStart = recordingStart; }

	public long recordingEnd() { return recordingEnd; }
	public void setRecordingEnd(long recordingEnd) { this.recordingEnd = recordingEnd; }

	public long firstSampleReplayTime() { return firstSampleReplayTime; }
	public void setFirstSampleReplayTime(long firstSampleReplayTime) { this.firstSampleReplayTime = firstSampleReplayTime; }

	public long totalSamples() { return totalSamples; }
	public void setTotalSamples(long totalSamples) { this.totalSamples = totalSamples; }

	public long durationMicroseconds() { return durationMicroseconds; }
	public void setDurationMicroseconds(long durationMicroseconds) { this.durationMicroseconds = durationMicroseconds; }

	public List<SyncAnchor> syncAnchors() { return syncAnchors; }
	public void setSyncAnchors(List<SyncAnchor> syncAnchors) {
		this.syncAnchors = syncAnchors == null ? new ArrayList<>() : new ArrayList<>(syncAnchors);
	}

	public Map<String, Boolean> audioCategories() { return audioCategories; }
	public void setAudioCategories(Map<String, Boolean> audioCategories) {
		this.audioCategories = audioCategories == null ? new LinkedHashMap<>() : new LinkedHashMap<>(audioCategories);
	}

	public boolean complete() { return complete; }
	public void setComplete(boolean complete) { this.complete = complete; }

	public String checksum() { return checksum; }
	public void setChecksum(String checksum) { this.checksum = Objects.requireNonNullElse(checksum, ""); }

	public long manualOffsetMilliseconds() { return manualOffsetMilliseconds; }
	public void setManualOffsetMilliseconds(long manualOffsetMilliseconds) {
		this.manualOffsetMilliseconds = manualOffsetMilliseconds;
	}

	public String minecraftVersion() { return minecraftVersion; }
	public void setMinecraftVersion(String minecraftVersion) { this.minecraftVersion = Objects.requireNonNullElse(minecraftVersion, ""); }

	public String replayModVersion() { return replayModVersion; }
	public void setReplayModVersion(String replayModVersion) { this.replayModVersion = Objects.requireNonNullElse(replayModVersion, ""); }

	public String worldOrServerId() { return worldOrServerId; }
	public void setWorldOrServerId(String worldOrServerId) { this.worldOrServerId = Objects.requireNonNullElse(worldOrServerId, ""); }

	public String replayFileName() { return replayFileName; }
	public void setReplayFileName(String replayFileName) { this.replayFileName = Objects.requireNonNullElse(replayFileName, ""); }

	public String captureStrategy() { return captureStrategy; }
	public void setCaptureStrategy(String captureStrategy) { this.captureStrategy = Objects.requireNonNullElse(captureStrategy, "shadow_mixer"); }

	public long droppedSamples() { return droppedSamples; }
	public void setDroppedSamples(long droppedSamples) { this.droppedSamples = droppedSamples; }

	public String recoveryNotes() { return recoveryNotes; }
	public void setRecoveryNotes(String recoveryNotes) { this.recoveryNotes = Objects.requireNonNullElse(recoveryNotes, ""); }

	public RecordingMetadata migrate() {
		if (schemaVersion < 1) {
			schemaVersion = 1;
		}
		if (codec == null || codec.isBlank()) {
			codec = "opus";
		}
		if (container == null || container.isBlank()) {
			container = "ogg";
		}
		if (sampleRate <= 0) {
			sampleRate = 48_000;
		}
		if (channels <= 0) {
			channels = 2;
		}
		return this;
	}
}
