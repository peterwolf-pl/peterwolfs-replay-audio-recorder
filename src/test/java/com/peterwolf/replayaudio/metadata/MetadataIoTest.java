package com.peterwolf.replayaudio.metadata;

import com.peterwolf.replayaudio.sync.SyncAnchor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetadataIoTest {
	@TempDir Path tmp;

	@Test
	void roundTripAndAtomicWrite() throws Exception {
		RecordingMetadata m = new RecordingMetadata();
		m.setReplayId("abc");
		m.setIntegration("replaymod");
		m.setAudioFile("x.replayaudio.ogg");
		m.setCodec("opus");
		m.setBitrate(160_000);
		m.setComplete(true);
		m.setSyncAnchors(List.of(new SyncAnchor(0, 0, 0, 1, "start")));
		m.setAudioCategories(Map.of("music", true, "ui", false));
		Path p = tmp.resolve("meta.replayaudio.json");
		MetadataIo.writeAtomic(p, m);
		assertTrue(Files.isRegularFile(p));
		RecordingMetadata back = MetadataIo.read(p);
		assertEquals("abc", back.replayId());
		assertEquals("opus", back.codec());
		assertEquals(1, back.syncAnchors().size());
		assertFalse(back.audioCategories().get("ui"));
	}

	@Test
	void schemaMigration() {
		RecordingMetadata m = new RecordingMetadata();
		m.setSchemaVersion(0);
		m.setCodec("");
		m.migrate();
		assertEquals(1, m.schemaVersion());
		assertEquals("opus", m.codec());
	}

	@Test
	void missingFieldsDefaults() {
		RecordingMetadata m = MetadataIo.fromJson("{\"schemaVersion\":1}");
		assertEquals(48_000, m.sampleRate());
		assertEquals(2, m.channels());
	}
}
