package com.peterwolf.replayaudio.audio;

import com.peterwolf.replayaudio.metadata.MetadataIo;
import com.peterwolf.replayaudio.metadata.RecordingMetadata;
import com.peterwolf.replayaudio.util.AtomicFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileSafetyTest {
	@TempDir Path tmp;

	@Test
	void atomicWriteLeavesNoTmp() throws Exception {
		Path target = tmp.resolve("a.json");
		AtomicFiles.writeStringAtomic(target, "{\"ok\":true}");
		assertTrue(Files.isRegularFile(target));
		assertFalse(Files.exists(tmp.resolve("a.json.tmp")));
	}

	@Test
	void crashRecoveryDetectsPartial() throws Exception {
		RecordingPaths paths = new RecordingPaths(tmp.resolve("game"));
		paths.ensureLayout();
		Files.createDirectories(paths.root().resolve("manual"));
		Path partial = paths.root().resolve("manual").resolve("x.replayaudio.json.partial");
		RecordingMetadata m = new RecordingMetadata();
		m.setAudioFile("x.replayaudio.ogg");
		m.setComplete(false);
		MetadataIo.writeAtomic(partial, m);
		// empty audio file
		Files.write(partial.resolveSibling("x.replayaudio.ogg"), new byte[]{1, 2, 3, 4});
		List<CrashRecovery.RecoveryItem> items = CrashRecovery.scan(paths);
		assertFalse(items.isEmpty());
	}

	@Test
	void associationScoresPreferReplayId() {
		RecordingMetadata m = new RecordingMetadata();
		m.setReplayId("uuid-1");
		m.setIntegration("replaymod");
		m.setReplayFileName("cool.mcpr");
		m.setComplete(true);
		int s = RecordingAssociation.score(m, "replaymod", "uuid-1", "cool.mcpr");
		assertTrue(s >= 100);
	}
}
