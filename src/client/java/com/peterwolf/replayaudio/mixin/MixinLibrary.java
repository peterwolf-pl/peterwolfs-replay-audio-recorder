package com.peterwolf.replayaudio.mixin;

import com.mojang.blaze3d.audio.DeviceList;
import com.mojang.blaze3d.audio.Library;
import com.peterwolf.replayaudio.recorder.RecordingController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observe OpenAL library init/destroy.
 * Verified MC 26.3: Library#init(String, DeviceList, boolean), #cleanup().
 * Continuous gameplay recording does not switch the device to SOFTLoopback.
 */
@Mixin(Library.class)
public class MixinLibrary {
	private static final Logger LOGGER = LoggerFactory.getLogger("replay_audio_openal");

	@Inject(method = "init", at = @At("TAIL"), require = 0)
	private void replayaudio$onInit(String deviceName, DeviceList devices, boolean hrtf, CallbackInfo ci) {
		LOGGER.debug("OpenAL Library init device={} hrtf={}", deviceName, hrtf);
	}

	@Inject(method = "cleanup", at = @At("HEAD"), require = 0)
	private void replayaudio$onCleanup(CallbackInfo ci) {
		RecordingController.INSTANCE.noteEvent("openal_cleanup");
	}
}
