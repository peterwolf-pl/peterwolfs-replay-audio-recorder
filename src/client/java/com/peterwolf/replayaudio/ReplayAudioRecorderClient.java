package com.peterwolf.replayaudio;

import com.mojang.blaze3d.platform.InputConstants;
import com.peterwolf.replayaudio.audio.CrashRecovery;
import com.peterwolf.replayaudio.command.ReplayAudioCommands;
import com.peterwolf.replayaudio.config.ReplayAudioConfig;
import com.peterwolf.replayaudio.gui.RecoveryScreen;
import com.peterwolf.replayaudio.gui.ReplayAudioConfigScreen;
import com.peterwolf.replayaudio.gui.ReplayAudioHud;
import com.peterwolf.replayaudio.playback.ReplayAudioPlayer;
import com.peterwolf.replayaudio.recorder.RecordingController;
import com.peterwolf.replayaudio.recorder.ShadowAudioMixer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Client-only entry point. Optional ReplayMod / Flashback integrations are loaded
 * reflectively only when those mods are present — their classes are never referenced
 * from this class's constant pool.
 */
public final class ReplayAudioRecorderClient implements ClientModInitializer {
	public static final String MOD_ID = "peterwolfs_replay_audio_recorder";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ReplayAudioConfig config;
	private static KeyMapping openConfigKey;
	private boolean recoveryChecked;

	@Override
	public void onInitializeClient() {
		LOGGER.info("PeterWolf's Replay Audio Recorder initializing (client-only)");
		config = ReplayAudioConfig.load();
		RecordingController.INSTANCE.init(config);
		ReplayAudioPlayer.INSTANCE.setConfig(config);
		ShadowAudioMixer.INSTANCE.setConfig(config);

		loadIntegration("replaymod", "com.peterwolf.replayaudio.integration.replaymod.ReplayModIntegration");
		loadIntegration("flashback", "com.peterwolf.replayaudio.integration.flashback.FlashbackIntegration");

		ReplayAudioCommands.register();

		openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.peterwolfs_replay_audio_recorder.config",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_F7,
			KeyMapping.Category.MISC
		));

		// MC 26.2 Fabric HUD API
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), (extractor, delta) -> {
			// Extractor-based HUD: status is primarily command/log driven; overlay optional.
			// No-op draw if extractor API is limited without full render path.
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (openConfigKey.consumeClick()) {
				client.setScreenAndShow(new ReplayAudioConfigScreen(client.gui.screen()));
			}
			ShadowAudioMixer.INSTANCE.tickMix();
			tickIntegration("com.peterwolf.replayaudio.integration.replaymod.ReplayModIntegration");
			tickIntegration("com.peterwolf.replayaudio.integration.flashback.FlashbackIntegration");

			if (!recoveryChecked && client.gui.screen() != null && config.crashRecoveryEnabled) {
				recoveryChecked = true;
				try {
					List<CrashRecovery.RecoveryItem> items = CrashRecovery.scan(RecordingController.INSTANCE.paths());
					if (!items.isEmpty()) {
						client.setScreenAndShow(new RecoveryScreen(client.gui.screen(), items));
					}
				} catch (Exception e) {
					LOGGER.warn("Crash recovery scan failed", e);
				}
			}

			if (RecordingController.INSTANCE.isActive() && client.level != null && client.player != null) {
				RecordingController.INSTANCE.session().setGameTick(client.level.getGameTime());
			}

			// Periodic action bar status while recording
			if (RecordingController.INSTANCE.isActive() && client.player != null && client.level != null
				&& client.level.getGameTime() % 40 == 0) {
				String line = ReplayAudioHud.statusLine();
				if (!line.isBlank()) {
					client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("§c● " + line));
				}
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			RecordingController.INSTANCE.noteEvent("client_shutdown");
			RecordingController.INSTANCE.stop();
			ReplayAudioPlayer.INSTANCE.unload();
		});

		LOGGER.info("Replay Audio Recorder ready. ReplayMod={} Flashback={}",
			FabricLoader.getInstance().isModLoaded("replaymod"),
			FabricLoader.getInstance().isModLoaded("flashback"));
	}

	private static void loadIntegration(String modId, String className) {
		if (!FabricLoader.getInstance().isModLoaded(modId)) {
			LOGGER.info("Optional integration {} skipped (mod not installed)", modId);
			return;
		}
		try {
			Class.forName(className).getMethod("init").invoke(null);
		} catch (Throwable t) {
			LOGGER.error("Failed to init integration {} ({})", modId, className, t);
		}
	}

	private static void tickIntegration(String className) {
		try {
			Class.forName(className).getMethod("clientTick").invoke(null);
		} catch (ClassNotFoundException ignored) {
		} catch (Throwable ignored) {
		}
	}

	public static ReplayAudioConfig config() {
		return config;
	}
}
