package com.peterwolf.replayaudio.audio;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe bounded PCM transfer between capture and encoder threads. */
public final class BoundedPcmQueue {
	private final ArrayBlockingQueue<PcmFrame> queue;
	private final AtomicLong droppedSamples = new AtomicLong();
	private final AtomicLong offeredSamples = new AtomicLong();

	public BoundedPcmQueue(int capacityFrames) {
		this.queue = new ArrayBlockingQueue<>(Math.max(8, capacityFrames));
	}

	/** Non-blocking offer. Drops oldest on overflow and tracks dropped samples. */
	public boolean offer(PcmFrame frame) {
		if (frame == null) {
			return false;
		}
		if (queue.offer(frame)) {
			offeredSamples.addAndGet(frame.frames());
			return true;
		}
		PcmFrame removed = queue.poll();
		if (removed != null) {
			droppedSamples.addAndGet(removed.frames());
		}
		boolean ok = queue.offer(frame);
		if (ok) {
			offeredSamples.addAndGet(frame.frames());
		} else {
			droppedSamples.addAndGet(frame.frames());
		}
		return ok;
	}

	public PcmFrame poll(long timeoutMs) throws InterruptedException {
		return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
	}

	public PcmFrame poll() {
		return queue.poll();
	}

	public long droppedSamples() {
		return droppedSamples.get();
	}

	public long offeredSamples() {
		return offeredSamples.get();
	}

	public int size() {
		return queue.size();
	}

	public void clear() {
		queue.clear();
	}
}
