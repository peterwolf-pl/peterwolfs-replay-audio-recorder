package com.peterwolf.replayaudio.mixin;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
import com.peterwolf.replayaudio.recorder.ShadowAudioMixer;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.sound.sampled.AudioFormat;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * When an OpenAL channel attaches a static buffer, duplicate PCM into the shadow mixer.
 * Verified MC 26.3: com.mojang.blaze3d.audio.Channel#attachStaticBuffer(SoundBuffer)
 * and SoundBuffer#format().
 */
@Mixin(Channel.class)
public abstract class MixinChannel {
	@Inject(method = "attachStaticBuffer", at = @At("TAIL"), require = 0)
	private void replayaudio$onAttachStatic(SoundBuffer soundBuffer, CallbackInfo ci) {
		try {
			if (soundBuffer == null || !soundBuffer.isValid()) {
				return;
			}
			AudioFormat format = soundBuffer.format();
			ByteBuffer data = null;
			for (Field f : soundBuffer.getClass().getDeclaredFields()) {
				if (ByteBuffer.class.isAssignableFrom(f.getType())) {
					f.setAccessible(true);
					Object v = f.get(soundBuffer);
					if (v instanceof ByteBuffer bb) {
						data = bb;
						break;
					}
				}
			}
			if (data == null || format == null) {
				return;
			}
			int channels = format.getChannels();
			int sampleRate = (int) format.getSampleRate();
			boolean sixteen = format.getSampleSizeInBits() == 16;
			int alFormat = channels > 1
				? (sixteen ? org.lwjgl.openal.AL10.AL_FORMAT_STEREO16 : org.lwjgl.openal.AL10.AL_FORMAT_STEREO8)
				: (sixteen ? org.lwjgl.openal.AL10.AL_FORMAT_MONO16 : org.lwjgl.openal.AL10.AL_FORMAT_MONO8);
			short[] samples = ShadowAudioMixer.bytesToShorts(data.duplicate(), alFormat);
			ShadowAudioMixer.INSTANCE.play(samples, channels, sampleRate, SoundSource.MASTER, 1f, 1f, 0, 0, 0, true);
		} catch (Throwable ignored) {
			// Never crash the sound engine.
		}
	}
}
