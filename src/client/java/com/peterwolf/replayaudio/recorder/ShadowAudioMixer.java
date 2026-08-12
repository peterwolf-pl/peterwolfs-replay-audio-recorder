package com.peterwolf.replayaudio.recorder;

import com.peterwolf.replayaudio.audio.AudioConstants;
import com.peterwolf.replayaudio.config.ReplayAudioConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.openal.AL10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Software shadow mixer that mirrors Minecraft sound-engine play operations.
 *
 * Capture strategy (verified approach for continuous gameplay recording):
 * - OpenAL Soft loopback (SOFTLoopback) is used by Flashback during export by
 *   reopening the audio device; that is not suitable for continuous gameplay
 *   because it would replace the player's real output device.
 * - This mixer instead duplicates decoded PCM submitted for playback, applies
 *   category volume gates and simple stereo panning from relative positions,
 *   and produces a continuous 48 kHz stereo stream for the Opus encoder.
 *
 * Differences from the final OpenAL mix (not bit-perfect):
 * - No HRTF
 * - No OpenAL environmental effects / reverb / occlusion
 * - No native OpenAL Soft filters
 * - Voice-chat audio only if routed through Minecraft SoundSource paths and enabled
 * - Sounds submitted directly to OpenAL by other mods may be missing
 */
public final class ShadowAudioMixer {
	public static final ShadowAudioMixer INSTANCE = new ShadowAudioMixer();

	private final Map<Long, ActiveVoice> voices = new ConcurrentHashMap<>();
	private final AtomicLong nextId = new AtomicLong(1);
	private final Object mixLock = new Object();
	private volatile RecordingController controller;
	private volatile ReplayAudioConfig config;
	private long lastMixNanos = System.nanoTime();
	private float[] listenerPos = new float[]{0, 0, 0};

	private ShadowAudioMixer() {
	}

	public void setController(RecordingController controller) {
		this.controller = controller;
	}

	public void setConfig(ReplayAudioConfig config) {
		this.config = config;
	}

	public void setListenerPosition(float x, float y, float z) {
		listenerPos = new float[]{x, y, z};
	}

	/** Called when a sound is requested; updates listener from the local player camera. */
	public void setListenerNeedsUpdate() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				var pos = mc.player.getEyePosition(1.0f);
				setListenerPosition((float) pos.x, (float) pos.y, (float) pos.z);
			}
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Inject mono/stereo PCM with category for a one-shot voice.
	 * Used by mixins when decoded samples are available.
	 */
	public void playRaw(short[] pcm, int channels, int sampleRate, SoundSource source, float volume, float pitch,
			boolean relative, float x, float y, float z) {
		play(pcm, channels, sampleRate, source, volume, pitch, x, y, z, relative);
	}

	public long play(short[] monoOrStereoPcm, int channels, int sampleRate, SoundSource source,
			float volume, float pitch, float x, float y, float z, boolean relative) {
		ReplayAudioConfig cfg = config;
		if (cfg == null || !categoryEnabled(cfg, source)) {
			return -1;
		}
		if (source == SoundSource.VOICE && (cfg == null || !cfg.recordVoiceChat)) {
			// Voice category in vanilla is not Simple Voice Chat; still gated by category map.
		}
		if (monoOrStereoPcm == null || monoOrStereoPcm.length == 0) {
			return -1;
		}
		short[] data = resampleIfNeeded(monoOrStereoPcm, channels, sampleRate);
		long id = nextId.getAndIncrement();
		voices.put(id, new ActiveVoice(data, channels == 2, volume, pitch, x, y, z, relative, source));
		return id;
	}

	/** Called from the client tick / sound tick to advance the mix and push PCM. */
	public void tickMix() {
		RecordingController c = controller;
		if (c == null || !c.isRecordingPcm()) {
			voices.clear();
			lastMixNanos = System.nanoTime();
			return;
		}
		long now = System.nanoTime();
		long elapsed = now - lastMixNanos;
		if (elapsed < 5_000_000L) { // 5ms
			return;
		}
		int frames = (int) Math.min(AudioConstants.SAMPLE_RATE / 20, // max 50ms
			Math.max(1, elapsed * AudioConstants.SAMPLE_RATE / 1_000_000_000L));
		lastMixNanos = now;
		short[] mixed = mixFrames(frames);
		c.offerCapturedPcm(mixed, frames);
	}

	private short[] mixFrames(int frames) {
		float[] left = new float[frames];
		float[] right = new float[frames];
		float[] lp = listenerPos;
		Iterator<Map.Entry<Long, ActiveVoice>> it = voices.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Long, ActiveVoice> e = it.next();
			ActiveVoice v = e.getValue();
			float panL = 1f, panR = 1f, atten = 1f;
			if (!v.relative) {
				float dx = v.x - lp[0];
				float dy = v.y - lp[1];
				float dz = v.z - lp[2];
				float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				atten = 1f / (1f + dist * 0.08f);
				// simple left/right from X relative to listener (original player perspective)
				float pan = Math.max(-1f, Math.min(1f, dx / 16f));
				panL = 0.5f * (1f - pan);
				panR = 0.5f * (1f + pan);
			}
			float gain = v.volume * atten;
			double step = Math.max(0.05, v.pitch);
			for (int i = 0; i < frames; i++) {
				int srcFrame = (int) v.cursor;
				if (srcFrame >= v.frames()) {
					break;
				}
				float sL, sR;
				if (v.stereo) {
					sL = v.pcm[srcFrame * 2] / 32768f;
					sR = v.pcm[srcFrame * 2 + 1] / 32768f;
				} else {
					float s = v.pcm[srcFrame] / 32768f;
					sL = s;
					sR = s;
				}
				left[i] += sL * gain * panL;
				right[i] += sR * gain * panR;
				v.cursor += step;
			}
			if ((int) v.cursor >= v.frames()) {
				it.remove();
			}
		}
		short[] out = new short[frames * 2];
		for (int i = 0; i < frames; i++) {
			out[i * 2] = clamp(left[i]);
			out[i * 2 + 1] = clamp(right[i]);
		}
		return out;
	}

	private static boolean categoryEnabled(ReplayAudioConfig cfg, SoundSource source) {
		if (cfg.audioCategories == null) return true;
		String key = switch (source) {
			case MASTER -> "master";
			case MUSIC -> "music";
			case RECORDS -> "records";
			case WEATHER -> "weather";
			case BLOCKS -> "blocks";
			case HOSTILE -> "hostile";
			case NEUTRAL -> "neutral";
			case PLAYERS -> "players";
			case AMBIENT -> "ambient";
			case VOICE -> "voice";
			case UI -> "ui";
		};
		return cfg.audioCategories.getOrDefault(key, true);
	}

	private static short[] resampleIfNeeded(short[] pcm, int channels, int sampleRate) {
		if (sampleRate == AudioConstants.SAMPLE_RATE) {
			return pcm;
		}
		int inFrames = pcm.length / Math.max(1, channels);
		int outFrames = (int) ((long) inFrames * AudioConstants.SAMPLE_RATE / Math.max(1, sampleRate));
		short[] out = new short[outFrames * channels];
		for (int i = 0; i < outFrames; i++) {
			int src = (int) ((long) i * sampleRate / AudioConstants.SAMPLE_RATE);
			src = Math.min(inFrames - 1, src);
			for (int c = 0; c < channels; c++) {
				out[i * channels + c] = pcm[src * channels + c];
			}
		}
		return out;
	}

	public static short[] bytesToShorts(ByteBuffer buffer, int format) {
		if (buffer == null) return new short[0];
		ByteBuffer bb = buffer.duplicate().order(ByteOrder.nativeOrder());
		int samples;
		if (format == AL10.AL_FORMAT_MONO16 || format == AL10.AL_FORMAT_STEREO16) {
			samples = bb.remaining() / 2;
			short[] s = new short[samples];
			bb.asShortBuffer().get(s);
			return s;
		}
		if (format == AL10.AL_FORMAT_MONO8 || format == AL10.AL_FORMAT_STEREO8) {
			samples = bb.remaining();
			short[] s = new short[samples];
			for (int i = 0; i < samples; i++) {
				s[i] = (short) ((bb.get() & 0xff) * 256 - 32768);
			}
			return s;
		}
		return new short[0];
	}

	private static short clamp(float v) {
		v = Math.max(-1f, Math.min(1f, v));
		return (short) (v * 32767f);
	}

	private static final class ActiveVoice {
		final short[] pcm;
		final boolean stereo;
		final float volume;
		final float pitch;
		final float x, y, z;
		final boolean relative;
		final SoundSource source;
		double cursor;

		ActiveVoice(short[] pcm, boolean stereo, float volume, float pitch, float x, float y, float z, boolean relative, SoundSource source) {
			this.pcm = pcm;
			this.stereo = stereo;
			this.volume = volume;
			this.pitch = pitch;
			this.x = x;
			this.y = y;
			this.z = z;
			this.relative = relative;
			this.source = source;
		}

		int frames() {
			return stereo ? pcm.length / 2 : pcm.length;
		}
	}
}
