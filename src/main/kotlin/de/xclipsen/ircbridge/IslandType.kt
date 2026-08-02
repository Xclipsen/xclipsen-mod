package de.xclipsen.ircbridge

import java.util.Locale

enum class IslandType(val modeIdentifier: String? = null) {
	// General
	PRIVATE_ISLAND("dynamic"),
	PRIVATE_ISLAND_GUEST,
	HUB("hub"),
	DARK_AUCTION("dark_auction"),
	WINTER("winter"),

	// Farming
	THE_FARMING_ISLANDS("farming_1"),
	GARDEN("garden"),
	GARDEN_GUEST,

	// Mining
	GOLD_MINES("mining_1"),
	DEEP_CAVERNS("mining_2"),
	DWARVEN_MINES("mining_3"),
	CRYSTAL_HOLLOWS("crystal_hollows"),
	MINESHAFT("mineshaft"),

	// Fishing
	BACKWATER_BAYOU("fishing_1"),
	LOTUS_ATOLL("lotus_atoll"),

	// Foraging
	THE_PARK("foraging_1"),
	GALATEA("foraging_2"),
	TORRHUS_CANYON("foraging_3"),

	// Combat
	SPIDER_DEN("combat_1"),
	THE_END("combat_3"),
	CRIMSON_ISLE("crimson_isle"),

	// Dungeons
	DUNGEON_HUB("dungeon_hub"),
	CATACOMBS("dungeon"),
	KUUDRA_ARENA("kuudra"),

	// Special
	THE_RIFT("rift"),
	SAFARI("safari"),

	// Internal states
	NONE,
	ANY,
	UNKNOWN,
	;

	fun matches(current: IslandType): Boolean = this == ANY || this == current

	companion object {
		private val modePattern = Regex("[a-z0-9_]{1,64}")
		private val mappedEntries = entries
			.mapNotNull { island -> island.modeIdentifier?.let { mode -> mode to island } }
		private val byMode = mappedEntries.toMap()

		init {
			require(byMode.size == mappedEntries.size) { "Island mode identifiers must be unique" }
		}

		fun fromMode(modeIdentifier: String?): IslandType {
			val normalized = modeIdentifier?.trim()?.lowercase(Locale.ROOT)
			return if (normalized == null || !modePattern.matches(normalized)) UNKNOWN else byMode[normalized] ?: UNKNOWN
		}
	}
}
