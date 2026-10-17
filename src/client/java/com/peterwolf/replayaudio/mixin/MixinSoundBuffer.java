package com.peterwolf.replayaudio.mixin;

import com.mojang.blaze3d.audio.SoundBuffer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Attachment point for SoundBuffer lifetime.
 * Verified: com.mojang.blaze3d.audio.SoundBuffer(ByteBuffer, AudioFormat) in MC 26.3.
 * PCM is read from Channel.attachStaticBuffer via reflection on buffer fields / format().
 */
@Mixin(SoundBuffer.class)
public abstract class MixinSoundBuffer {
}
