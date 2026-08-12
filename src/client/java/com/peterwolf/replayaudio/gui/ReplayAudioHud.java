package com.peterwolf.replayaudio.gui;

import com.peterwolf.replayaudio.playback.ReplayAudioPlayer;
import com.peterwolf.replayaudio.recorder.RecordingController;
import net.minecraft.network.chat.Component;

/**
 * Unobtrusive recording / playback status. In MC 26.2 HUD elements use
 * GuiGraphicsExtractor; we keep text construction here for the HudElement adapter.
 */
public final class ReplayAudioHud {
	private ReplayAudioHud() {
	}

	public static String statusLine() {
		var cfg = RecordingController.INSTANCE.config();
		if (cfg == null || !cfg.showRecordingIndicator) return "";

		StringBuilder sb = new StringBuilder();
		if (RecordingController.INSTANCE.isActive()) {
			sb.append("REC AUDIO ").append(RecordingController.INSTANCE.statusText());
		}
		if (ReplayAudioPlayer.INSTANCE.isLoaded()) {
			if (!sb.isEmpty()) sb.append(" | ");
			sb.append(ReplayAudioPlayer.INSTANCE.status());
		}
		return sb.toString();
	}

	public static Component statusComponent() {
		String line = statusLine();
		return line.isBlank() ? Component.empty() : Component.literal(line);
	}
}
