package de.xclipsen.ircbridge

import net.minecraft.text.Text

object ChatFeature {
	private val implosionMessagePattern = Regex("^Your Implosion hit \\d+ (?:enemy|enemies) for [\\d,.]+ damage\\.$")

	fun shouldSuppressMessage(message: Text?): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		if (!config.chatModuleEnabled || !config.chatImplosionHiderEnabled) {
			return false
		}

		val normalized = normalizeMessage(message?.string ?: return false)
		return implosionMessagePattern.matches(normalized)
	}

	private fun normalizeMessage(raw: String): String {
		return raw
			.replace(FORMATTING_CODE_PATTERN, "")
			.replace('\r', ' ')
			.replace('\n', ' ')
			.replace(WHITESPACE_PATTERN, " ")
			.trim()
	}

	private val FORMATTING_CODE_PATTERN = Regex("§.")
	private val WHITESPACE_PATTERN = Regex("\\s+")
}
