package com.peterwolf.replayaudio.gui;

import com.peterwolf.replayaudio.audio.CrashRecovery;
import com.peterwolf.replayaudio.recorder.RecordingController;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Shown after startup when incomplete recordings are detected. */
public final class RecoveryScreen extends Screen {
	private final Screen parent;
	private final List<CrashRecovery.RecoveryItem> items;
	private StringWidget messageWidget;

	public RecoveryScreen(Screen parent, List<CrashRecovery.RecoveryItem> items) {
		super(Component.literal("Replay Audio Recovery"));
		this.parent = parent;
		this.items = items;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		addRenderableWidget(new StringWidget(cx - 160, 24, 320, 12, title, font));
		addRenderableWidget(new StringWidget(cx - 160, 42, 320, 12,
			Component.literal(items.size() + " incomplete recording(s) found"), font));
		int y = 64;
		for (int i = 0; i < Math.min(items.size(), 10); i++) {
			CrashRecovery.RecoveryItem item = items.get(i);
			String line = item.status() + " — " + (item.audioFile() == null ? "?" : item.audioFile().getFileName());
			addRenderableWidget(new StringWidget(20, y, width - 40, 12, Component.literal(line), font));
			y += 12;
		}
		messageWidget = addRenderableWidget(new StringWidget(cx - 160, height - 88, 320, 12, Component.empty(), font));
		addRenderableWidget(Button.builder(Component.literal("Repair all recoverable"), b -> {
			int ok = 0;
			for (CrashRecovery.RecoveryItem item : items) {
				try {
					if ("recoverable".equals(item.status()) || "incomplete".equals(item.status())) {
						CrashRecovery.repair(item, RecordingController.INSTANCE.paths());
						ok++;
					}
				} catch (Exception e) {
					messageWidget.setMessage(Component.literal("Repair error: " + e.getMessage()));
				}
			}
			messageWidget.setMessage(Component.literal("Repaired " + ok + " recording(s)"));
		}).bounds(cx - 120, height - 64, 240, 20).build());

		addRenderableWidget(Button.builder(Component.literal("Move unrecoverable to orphaned"), b -> {
			for (CrashRecovery.RecoveryItem item : items) {
				try {
					if ("missing_audio".equals(item.status()) || "unreadable".equals(item.status())) {
						CrashRecovery.moveToOrphaned(item, RecordingController.INSTANCE.paths());
					}
				} catch (Exception ignored) {
				}
			}
			messageWidget.setMessage(Component.literal("Moved unrecoverable items to orphaned/"));
		}).bounds(cx - 120, height - 40, 240, 20).build());

		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
			.bounds(cx - 60, height - 16, 120, 20).build());
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}
}
