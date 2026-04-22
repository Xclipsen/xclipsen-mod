package de.xclipsen.ircbridge

import java.time.LocalTime

object ClientTimeChanger {
	private val modeNames = listOf("Day", "Noon", "Sunset", "Night", "Midnight", "Sunrise", "Real Time")
	private val fixedTimes = longArrayOf(1000L, 6000L, 12000L, 13000L, 18000L, 23000L)

	val modeCount: Int
		get() = modeNames.size

	fun displayName(mode: Int): String = modeNames.getOrElse(mode.coerceIn(0, modeNames.lastIndex)) { modeNames.first() }

	fun overrideTimeOfDay(originalTimeOfDay: Long): Long {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return originalTimeOfDay
		if (!config.timeChangerEnabled) {
			return originalTimeOfDay
		}

		return customTime(config.timeChangerMode)
	}

	fun shouldTickTimeOfDay(originalShouldTick: Boolean): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return originalShouldTick
		if (!config.timeChangerEnabled) {
			return originalShouldTick
		}

		return false
	}

	private fun customTime(mode: Int): Long {
		return fixedTimes.getOrElse(mode) { realWorldTime() }
	}

	private fun realWorldTime(): Long {
		val now = LocalTime.now()
		val ticks = (now.hour * 1000) + (now.minute * 16.66).toLong() - 6000
		return if (ticks < 0) ticks + 24000 else ticks.toLong()
	}
}
