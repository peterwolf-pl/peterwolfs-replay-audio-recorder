package com.peterwolf.replayaudio.gui;

import com.peterwolf.replayaudio.audio.RecordingQuality;
import com.peterwolf.replayaudio.config.ReplayAudioConfig;
import com.peterwolf.replayaudio.export.FfmpegMuxer;
import com.peterwolf.replayaudio.recorder.RecordingController;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client configuration screen for recording, export, and privacy options. */
public final class ReplayAudioConfigScreen extends Screen {
	private final Screen parent;
	private final ReplayAudioConfig config;

	public ReplayAudioConfigScreen(Screen parent) {
		super(Component.literal("PeterWolf's Replay Audio Recorder"));
		this.parent = parent;
		this.config = RecordingController.INSTANCE.config();
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int y = 28;
		addRenderableWidget(new StringWidget(cx - 180, y, 360, 12,
			Component.literal("Original game-mix only — free camera does not change the recorded spatial mix"), font));
		y += 18;
		addRenderableWidget(new StringWidget(cx - 180, y, 360, 12,
			Component.literal("Output: .minecraft/replay_audio_recordings/"), font));
		y += 22;

		addRenderableWidget(CycleButton.onOffBuilder(config.autoRecordReplayMod)
			.create(cx - 150, y, 300, 20, Component.literal("Auto-record with ReplayMod"), (b, v) -> {
				config.autoRecordReplayMod = v;
				config.save();
			}));
		y += 24;
		addRenderableWidget(CycleButton.onOffBuilder(config.autoRecordFlashback)
			.create(cx - 150, y, 300, 20, Component.literal("Auto-record with Flashback"), (b, v) -> {
				config.autoRecordFlashback = v;
				config.save();
			}));
		y += 24;
		addRenderableWidget(CycleButton.builder((RecordingQuality q) ->
					Component.literal(q.name() + " (" + (q.bitrate() / 1000) + " kbps)"), config.quality())
			.withValues(RecordingQuality.values())
			.create(cx - 150, y, 300, 20, Component.literal("Recording quality"), (b, v) -> {
				config.recordingQuality = v.name();
				config.save();
			}));
		y += 24;
		addRenderableWidget(CycleButton.onOffBuilder(config.recordVoiceChat)
			.create(cx - 150, y, 300, 20, Component.literal("Record voice chat audio"), (b, v) -> {
				config.recordVoiceChat = v;
				config.save();
				if (v && minecraft.player != null) {
					minecraft.player.sendSystemMessage(Component.literal(
						"Privacy warning: voice chat recording enabled. Only enable with participant consent."));
				}
			}));
		y += 24;
		addRenderableWidget(CycleButton.onOffBuilder(config.autoMuxAfterExport)
			.create(cx - 150, y, 300, 20, Component.literal("Auto-mux audio after MP4 export"), (b, v) -> {
				config.autoMuxAfterExport = v;
				config.save();
			}));
		y += 24;
		addRenderableWidget(CycleButton.onOffBuilder(config.keepOriginalSilentMp4)
			.create(cx - 150, y, 300, 20, Component.literal("Keep original silent MP4"), (b, v) -> {
				config.keepOriginalSilentMp4 = v;
				config.save();
			}));
		y += 24;
		addRenderableWidget(CycleButton.onOffBuilder(config.enableReplayAudio)
			.create(cx - 150, y, 300, 20, Component.literal("Enable replay audio playback"), (b, v) -> {
				config.enableReplayAudio = v;
				config.save();
			}));
		y += 26;
		boolean ffmpegOk = FfmpegMuxer.isFfmpegAvailable(config.ffmpegPath);
		addRenderableWidget(new StringWidget(cx - 150, y, 300, 12,
			Component.literal("FFmpeg: " + (ffmpegOk ? "found (" + config.ffmpegPath + ")" : "NOT FOUND")), font));
		y += 16;
		addRenderableWidget(new StringWidget(cx - 150, y, 300, 12,
			Component.literal("Status: " + RecordingController.INSTANCE.statusText()), font));
		y += 28;
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
			.bounds(cx - 100, Math.min(y, height - 28), 200, 20).build());
	}

	@Override
	public void onClose() {
		config.save();
		minecraft.setScreenAndShow(parent);
	}
}
