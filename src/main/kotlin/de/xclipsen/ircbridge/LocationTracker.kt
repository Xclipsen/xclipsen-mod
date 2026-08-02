package de.xclipsen.ircbridge

import net.minecraft.client.Minecraft
import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket
import java.util.Locale

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
	private var initialized = false
	private var modApiLocationReceived = false
	private var modApiSkyBlock = false

	@Volatile
	var currentIsland: IslandType = IslandType.NONE
		private set

	@Volatile
	var currentModeIdentifier: String = ""
		private set

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
		get() = isOnHypixel && if (modApiLocationReceived) modApiSkyBlock else scoreboardTitle.contains("skyblock", ignoreCase = true)

	/** True while the player is anywhere on Galatea Island. */
	val isOnGalatea: Boolean
		get() = isOnHypixelSkyBlock && if (modApiLocationReceived) {
			currentIsland in GALATEA_ISLANDS
		} else {
			normalizedGalateaArea(currentArea) != null
		}

	/** True while the tab list reports the Safari area. */
	val isInSafariArea: Boolean
		get() = isOnHypixelSkyBlock && if (modApiLocationReceived) currentIsland == IslandType.SAFARI else currentArea.equals("Safari", ignoreCase = true)

	/** True while the player is on The End island or one of its sub-areas. */
	val isOnEndIsland: Boolean
		get() = isOnHypixelSkyBlock && if (modApiLocationReceived) currentIsland == IslandType.THE_END else normalizedEndArea(currentArea) != null

	/** True while the player is on The Garden or one of its plots. */
	val isOnGarden: Boolean
		get() = isOnHypixelSkyBlock && if (modApiLocationReceived) currentIsland in GARDEN_ISLANDS else normalizedGardenArea(currentArea) != null

	val isInMineshaft: Boolean
		get() = isOnHypixelSkyBlock && if (modApiLocationReceived) currentIsland == IslandType.MINESHAFT else currentArea.contains("mineshaft", ignoreCase = true)

	/** True while the Critter Safari event is listed in the Hypixel tab list. */
	@Volatile
	var isCritterSafariActive: Boolean = false
		private set

	// ── SavedTick ────────────────────────────────────────────────────────────

	private var tickCounter = 0
	private const val CHECK_INTERVAL = 20   // re-read tab list once per second

	fun init() {
		if (initialized) return
		initialized = true
		val api = HypixelModAPI.getInstance()
		api.createHandler(ClientboundLocationPacket::class.java) { packet ->
			Minecraft.getInstance().execute { updateFromModApi(packet) }
		}
		api.subscribeToEventPacket(ClientboundLocationPacket::class.java)
	}

	fun reset() {
		tickCounter = 0
		currentArea = ""
		scoreboardTitle = ""
		isOnHypixel = false
		isCritterSafariActive = false
		modApiLocationReceived = false
		modApiSkyBlock = false
		currentIsland = IslandType.NONE
		currentModeIdentifier = ""
	}

	private fun updateFromModApi(packet: ClientboundLocationPacket) {
		if (!isHypixelServer(Minecraft.getInstance())) return
		modApiLocationReceived = true
		isOnHypixel = true
		modApiSkyBlock = packet.serverType.map { it.name.equals("SKYBLOCK", ignoreCase = true) }.orElse(false)
		val mode = packet.mode.orElse("").trim().lowercase(Locale.ROOT)
		currentModeIdentifier = mode.filterNot(Char::isISOControl).take(64)
		currentIsland = if (modApiSkyBlock) IslandType.fromMode(mode) else IslandType.NONE
	}

	fun onTick(client: Minecraft) {
		if (++tickCounter < CHECK_INTERVAL) return
		tickCounter = 0

		if (client.level == null || client.player == null) {
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
	private fun readAreaFromTabList(client: Minecraft): String? {
		val playerList = client.player?.connection?.listedOnlinePlayers ?: return null

		for (entry in playerList) {
			val display = entry.tabListDisplayName?.string ?: continue

			for (prefix in AREA_PREFIXES) {
				if (display.startsWith(prefix, ignoreCase = true)) {
					return display.substring(prefix.length).trim()
				}
			}
		}
		return null
	}

	private fun hasTabListEntry(client: Minecraft, text: String): Boolean {
		return client.player?.connection?.listedOnlinePlayers?.any { entry ->
			entry.tabListDisplayName?.string?.contains(text, ignoreCase = true) == true
		} == true
	}

	private fun readScoreboardTitle(client: Minecraft): String? {
		val scoreboard = client.level?.scoreboard ?: return null
		val objective = scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR) ?: return null
		return objective.displayName?.string?.trim()?.takeUnless { it.isEmpty() }
	}

	private fun isHypixelServer(client: Minecraft): Boolean {
		val address = client.currentServer?.ip?.trim()?.lowercase() ?: return false
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
	private val GALATEA_ISLANDS = setOf(IslandType.GALATEA, IslandType.TORRHUS_CANYON, IslandType.SAFARI)
	private val GARDEN_ISLANDS = setOf(IslandType.GARDEN, IslandType.GARDEN_GUEST)
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
