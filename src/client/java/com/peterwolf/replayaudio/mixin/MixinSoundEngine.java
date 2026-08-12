package com.peterwolf.replayaudio.mixin;

import com.peterwolf.replayaudio.recorder.ShadowAudioMixer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks SoundEngine play + tick for continuous shadow mix.
 * Verified MC 26.2: SoundEngine#tick(boolean), #play(SoundInstance) -> PlayResult.
 */
@Mixin(SoundEngine.class)
public class MixinSoundEngine {
	@Inject(method = "tick", at = @At("HEAD"), require = 0)
	private void replayaudio$tick(boolean paused, CallbackInfo ci) {
		try {
			ShadowAudioMixer.INSTANCE.tickMix();
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "play", at = @At("HEAD"), require = 0)
	private void replayaudio$play(SoundInstance instance, CallbackInfoReturnable<?> cir) {
		try {
			if (instance == null) return;
			ShadowAudioMixer.INSTANCE.setListenerNeedsUpdate();
			// Category-aware capture is applied when PCM arrives via Channel.attachStaticBuffer.
			// Store last play request attributes for future association if needed.
		} catch (Throwable ignored) {
		}
	}
}
