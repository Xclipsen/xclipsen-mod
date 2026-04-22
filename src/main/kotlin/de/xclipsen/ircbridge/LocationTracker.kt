package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient

/**
 * Tracks the player's current server-side area by reading the tab-list
 * (player-info) entries — exactly as LocationUtils does via
 * ClientboundPlayerInfoUpdatePacket.
 *
 * Galatea Island detection: an entry whose display-name starts with "Area: "
 * and contains "Galatea" (case-insensitive).
 *
 * [onTick] must be called every client tick (END_CLIENT_TICK).
 */
object LocationTracker {

	/** Raw area string as shown in the tab list, e.g. "Galatea Island". Empty when unknown. */
	@Volatile
	var currentArea: String = ""
		private set

	/** True while the player is anywhere on Galatea Island. */
	val isOnGalatea: Boolean
		get() = currentArea.contains("galatea", ignoreCase = true)

	// ── Tick ────────────────────────────────────────────────────────────

	private var tickCounter = 0
	private const val CHECK_INTERVAL = 20   // re-read tab list once per second

	fun onTick(client: MinecraftClient) {
		if (++tickCounter < CHECK_INTERVAL) return
		tickCounter = 0

		if (client.world == null || client.player == null) {
			currentArea = ""
			return
		}

		currentArea = readAreaFromTabList(client) ?: ""
	}

	// ── Tab-list reader ─────────────────────────────────────────────────

	/**
	 * Searches the player-list (tab list) for an entry whose display name
	 * starts with "Area: " or "Dungeon: " and returns the area portion.
	 * Returns null if no such entry is found (e.g. not on a SkyBlock server).
	 */
	private fun readAreaFromTabList(client: MinecraftClient): String? {
		val playerList = client.player?.networkHandler?.playerList ?: return null

		for (entry in playerList) {
			val display = entry.displayName?.string ?: continue

			for (prefix in AREA_PREFIXES) {
				if (display.startsWith(prefix, ignoreCase = true)) {
					return display.substring(prefix.length).trim()
				}
			}
		}
		return null
	}

	private val AREA_PREFIXES = listOf("Area: ", "Dungeon: ")
}
