package de.xclipsen.ircbridge

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import java.util.concurrent.atomic.AtomicLong

object ClientSessionLifecycle {
	private val generation = AtomicLong()
	@Volatile
	private var level: ClientLevel? = null

	fun snapshot(): Long = generation.get()

	fun isCurrent(snapshot: Long): Boolean = generation.get() == snapshot

	fun invalidate() {
		level = Minecraft.getInstance().level
		generation.incrementAndGet()
	}

	fun updateLevel(client: Minecraft): Boolean {
		val current = client.level
		if (current === level) {
			return false
		}
		level = current
		generation.incrementAndGet()
		return true
	}
}
