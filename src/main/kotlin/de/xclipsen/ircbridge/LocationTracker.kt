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

	/** Raw scoreboard title, e.g. "SKYBLOCK". Empty when unknown. */
	@Volatile
	var scoreboardTitle: String = ""
		private set

	/** True while connected to a Hypixel server entry. */
	@Volatile
	var isOnHypixel: Boolean = false
		private set

	/** True while the client appears to be on Hypixel SkyBlock. */
	val isOnHypixelSkyBlock: Boolean
		get() = isOnHypixel && scoreboardTitle.contains("skyblock", ignoreCase = true)

	/** True while the player is anywhere on Galatea Island. */
	val isOnGalatea: Boolean
		get() = isOnHypixelSkyBlock && normalizedGalateaArea(currentArea) != null

	/** True while the tab list reports the Safari area. */
	val isInSafariArea: Boolean
		get() = isOnHypixelSkyBlock && currentArea.equals("Safari", ignoreCase = true)

	/** True while the player is on The End island or one of its sub-areas. */
	val isOnEndIsland: Boolean
		get() = isOnHypixelSkyBlock && normalizedEndArea(currentArea) != null

	/** True while the player is on The Garden or one of its plots. */
	val isOnGarden: Boolean
		get() = isOnHypixelSkyBlock && normalizedGardenArea(currentArea) != null

	/** True while the Critter Safari event is listed in the Hypixel tab list. */
	@Volatile
	var isCritterSafariActive: Boolean = false
		private set

	// ── Tick ────────────────────────────────────────────────────────────

	private var tickCounter = 0
	private const val CHECK_INTERVAL = 20   // re-read tab list once per second

	fun onTick(client: MinecraftClient) {
		if (++tickCounter < CHECK_INTERVAL) return
		tickCounter = 0

		if (client.world == null || client.player == null) {
			currentArea = ""
			scoreboardTitle = ""
			isOnHypixel = false
			isCritterSafariActive = false
			return
		}

		isOnHypixel = isHypixelServer(client)
		scoreboardTitle = readScoreboardTitle(client) ?: ""
		currentArea = readAreaFromTabList(client) ?: ""
		isCritterSafariActive = hasTabListEntry(client, "Critter Safari")
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

	private fun hasTabListEntry(client: MinecraftClient, text: String): Boolean {
		return client.player?.networkHandler?.playerList?.any { entry ->
			entry.displayName?.string?.contains(text, ignoreCase = true) == true
		} == true
	}

	private fun readScoreboardTitle(client: MinecraftClient): String? {
		val scoreboard = client.world?.scoreboard ?: return null
		val objective = scoreboard.getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR) ?: return null
		return objective.displayName?.string?.trim()?.takeUnless { it.isEmpty() }
	}

	private fun isHypixelServer(client: MinecraftClient): Boolean {
		val address = client.currentServerEntry?.address?.trim()?.lowercase() ?: return false
		return address == "hypixel.net" || address.endsWith(".hypixel.net")
	}

	private fun normalizedEndArea(raw: String): String? {
		val normalized = raw.trim().lowercase()
		return END_ISLAND_AREAS.firstOrNull { area ->
			normalized == area || normalized.contains(area)
		}
	}

	private fun normalizedGalateaArea(raw: String): String? {
		val normalized = raw.trim().lowercase()
		return GALATEA_AREAS.firstOrNull { area -> normalized == area || normalized.contains(area) }
	}

	private fun normalizedGardenArea(raw: String): String? {
		val normalized = raw.trim().lowercase()
		if (normalized.isBlank()) {
			return null
		}
		if (normalized == "the garden" || normalized.startsWith("plot ")) {
			return normalized
		}
		return if (normalized.contains("garden")) normalized else null
	}

	private val AREA_PREFIXES = listOf("Area: ", "Dungeon: ")
	private val GALATEA_AREAS = setOf(
		"galatea",
		"torrhus canyon",
		"critter safari entrance",
		"safari",
	)
	private val END_ISLAND_AREAS = setOf(
		"the end",
		"dragon's nest",
		"zealot bruiser hideout",
		"void slate",
		"void sepulture",
		"forgotten skull",
		"dragontail",
	)
}
