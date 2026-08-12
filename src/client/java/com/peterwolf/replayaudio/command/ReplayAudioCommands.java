package com.peterwolf.replayaudio.command;

import com.mojang.brigadier.CommandDispatcher;
import com.peterwolf.replayaudio.gui.ReplayAudioConfigScreen;
import com.peterwolf.replayaudio.playback.ReplayAudioPlayer;
import com.peterwolf.replayaudio.recorder.RecordingController;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.nio.file.Path;

public final class ReplayAudioCommands {
	private ReplayAudioCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("replayaudiorecorder")
			.then(ClientCommands.literal("start").executes(ctx -> {
				boolean ok = RecordingController.INSTANCE.startManual();
				ctx.getSource().sendFeedback(Component.literal(ok ? "Manual recording started" : "Could not start recording"));
				return ok ? 1 : 0;
			}))
			.then(ClientCommands.literal("stop").executes(ctx -> {
				RecordingController.INSTANCE.stop();
				ctx.getSource().sendFeedback(Component.literal("Recording stop requested"));
				return 1;
			}))
			.then(ClientCommands.literal("pause").executes(ctx -> {
				RecordingController.INSTANCE.pause();
				ctx.getSource().sendFeedback(Component.literal("Recording paused"));
				return 1;
			}))
			.then(ClientCommands.literal("resume").executes(ctx -> {
				RecordingController.INSTANCE.resume();
				ctx.getSource().sendFeedback(Component.literal("Recording resumed"));
				return 1;
			}))
			.then(ClientCommands.literal("status").executes(ctx -> {
				ctx.getSource().sendFeedback(Component.literal(RecordingController.INSTANCE.statusText()));
				ctx.getSource().sendFeedback(Component.literal("Playback: " + ReplayAudioPlayer.INSTANCE.status()));
				return 1;
			}))
			.then(ClientCommands.literal("openfolder").executes(ctx -> {
				Path root = RecordingController.INSTANCE.paths().root();
				Util.getPlatform().openPath(root);
				ctx.getSource().sendFeedback(Component.literal("Opened " + root));
				return 1;
			}))
			.then(ClientCommands.literal("config").executes(ctx -> {
				Minecraft mc = Minecraft.getInstance();
				mc.execute(() -> mc.setScreenAndShow(new ReplayAudioConfigScreen(mc.gui.screen())));
				return 1;
			}))
		);
	}
}
