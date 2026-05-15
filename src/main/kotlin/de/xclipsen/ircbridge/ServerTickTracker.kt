package de.xclipsen.ircbridge

import java.util.concurrent.atomic.AtomicLong

object ServerTickTracker {
	private val serverTickCounter = AtomicLong()

	fun onServerTick() {
		serverTickCounter.incrementAndGet()
	}

	fun currentTickCount(): Long = serverTickCounter.get()

	fun reset() {
		serverTickCounter.set(0L)
	}
}
