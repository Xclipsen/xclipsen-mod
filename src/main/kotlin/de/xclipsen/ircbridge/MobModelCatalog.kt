package de.xclipsen.ircbridge

import net.minecraft.entity.EntityType
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.util.Locale

object MobModelCatalog {
	private val LOGGER = LoggerFactory.getLogger("xclipsen_mob_catalog")

	private val localTypesById: Map<String, EntityType<*>> = linkedMapOf(
		"minecraft:allay" to EntityType.ALLAY,
		"minecraft:armadillo" to EntityType.ARMADILLO,
		"minecraft:armor_stand" to EntityType.ARMOR_STAND,
		"minecraft:axolotl" to EntityType.AXOLOTL,
		"minecraft:bat" to EntityType.BAT,
		"minecraft:bee" to EntityType.BEE,
		"minecraft:blaze" to EntityType.BLAZE,
		"minecraft:bogged" to EntityType.BOGGED,
		"minecraft:breeze" to EntityType.BREEZE,
		"minecraft:camel" to EntityType.CAMEL,
		"minecraft:cat" to EntityType.CAT,
		"minecraft:cave_spider" to EntityType.CAVE_SPIDER,
		"minecraft:chicken" to EntityType.CHICKEN,
		"minecraft:cod" to EntityType.COD,
		"minecraft:copper_golem" to EntityType.COPPER_GOLEM,
		"minecraft:cow" to EntityType.COW,
		"minecraft:creaking" to EntityType.CREAKING,
		"minecraft:creeper" to EntityType.CREEPER,
		"minecraft:dolphin" to EntityType.DOLPHIN,
		"minecraft:donkey" to EntityType.DONKEY,
		"minecraft:drowned" to EntityType.DROWNED,
		"minecraft:elder_guardian" to EntityType.ELDER_GUARDIAN,
		"minecraft:ender_dragon" to EntityType.ENDER_DRAGON,
		"minecraft:enderman" to EntityType.ENDERMAN,
		"minecraft:endermite" to EntityType.ENDERMITE,
		"minecraft:evoker" to EntityType.EVOKER,
		"minecraft:fox" to EntityType.FOX,
		"minecraft:frog" to EntityType.FROG,
		"minecraft:ghast" to EntityType.GHAST,
		"minecraft:giant" to EntityType.GIANT,
		"minecraft:glow_squid" to EntityType.GLOW_SQUID,
		"minecraft:goat" to EntityType.GOAT,
		"minecraft:guardian" to EntityType.GUARDIAN,
		"minecraft:happy_ghast" to EntityType.HAPPY_GHAST,
		"minecraft:hoglin" to EntityType.HOGLIN,
		"minecraft:horse" to EntityType.HORSE,
		"minecraft:husk" to EntityType.HUSK,
		"minecraft:illusioner" to EntityType.ILLUSIONER,
		"minecraft:iron_golem" to EntityType.IRON_GOLEM,
		"minecraft:llama" to EntityType.LLAMA,
		"minecraft:magma_cube" to EntityType.MAGMA_CUBE,
		"minecraft:mannequin" to EntityType.MANNEQUIN,
		"minecraft:mooshroom" to EntityType.MOOSHROOM,
		"minecraft:mule" to EntityType.MULE,
		"minecraft:ocelot" to EntityType.OCELOT,
		"minecraft:panda" to EntityType.PANDA,
		"minecraft:parrot" to EntityType.PARROT,
		"minecraft:phantom" to EntityType.PHANTOM,
		"minecraft:pig" to EntityType.PIG,
		"minecraft:piglin" to EntityType.PIGLIN,
		"minecraft:piglin_brute" to EntityType.PIGLIN_BRUTE,
		"minecraft:pillager" to EntityType.PILLAGER,
		"minecraft:polar_bear" to EntityType.POLAR_BEAR,
		"minecraft:pufferfish" to EntityType.PUFFERFISH,
		"minecraft:rabbit" to EntityType.RABBIT,
		"minecraft:ravager" to EntityType.RAVAGER,
		"minecraft:salmon" to EntityType.SALMON,
		"minecraft:sheep" to EntityType.SHEEP,
		"minecraft:shulker" to EntityType.SHULKER,
		"minecraft:silverfish" to EntityType.SILVERFISH,
		"minecraft:skeleton" to EntityType.SKELETON,
		"minecraft:skeleton_horse" to EntityType.SKELETON_HORSE,
		"minecraft:slime" to EntityType.SLIME,
		"minecraft:sniffer" to EntityType.SNIFFER,
		"minecraft:snow_golem" to EntityType.SNOW_GOLEM,
		"minecraft:spider" to EntityType.SPIDER,
		"minecraft:squid" to EntityType.SQUID,
		"minecraft:stray" to EntityType.STRAY,
		"minecraft:strider" to EntityType.STRIDER,
		"minecraft:tadpole" to EntityType.TADPOLE,
		"minecraft:trader_llama" to EntityType.TRADER_LLAMA,
		"minecraft:tropical_fish" to EntityType.TROPICAL_FISH,
		"minecraft:turtle" to EntityType.TURTLE,
		"minecraft:vex" to EntityType.VEX,
		"minecraft:villager" to EntityType.VILLAGER,
		"minecraft:vindicator" to EntityType.VINDICATOR,
		"minecraft:wandering_trader" to EntityType.WANDERING_TRADER,
		"minecraft:warden" to EntityType.WARDEN,
		"minecraft:witch" to EntityType.WITCH,
		"minecraft:wither" to EntityType.WITHER,
		"minecraft:wither_skeleton" to EntityType.WITHER_SKELETON,
		"minecraft:wolf" to EntityType.WOLF,
		"minecraft:zoglin" to EntityType.ZOGLIN,
		"minecraft:zombie" to EntityType.ZOMBIE,
		"minecraft:zombie_horse" to EntityType.ZOMBIE_HORSE,
		"minecraft:zombie_villager" to EntityType.ZOMBIE_VILLAGER,
		"minecraft:zombified_piglin" to EntityType.ZOMBIFIED_PIGLIN,
	)

	private val sortedIds: List<String> = localTypesById.keys.sortedWith(compareBy({ displayName(it) }, { it }))

	fun ids(): List<String> = sortedIds

	fun count(): Int = localTypesById.size

	fun resolve(rawId: String?): EntityType<*>? {
		val normalized = normalize(rawId) ?: return null
		return localTypesById[normalized]
	}

	fun normalize(rawId: String?): String? {
		val trimmed = rawId.orEmpty().trim().lowercase(Locale.ROOT)
		if (trimmed.isBlank()) {
			return null
		}

		val namespaced = if (':' in trimmed) trimmed else "minecraft:$trimmed"
		return Identifier.tryParse(namespaced)?.toString()
	}

	fun displayName(rawId: String?): String {
		val id = Identifier.tryParse(rawId?.trim().orEmpty()) ?: return "Unknown"
		val baseName = id.path.substringAfterLast('/')
			.split('_', '-', '.')
			.filter { it.isNotBlank() }
			.joinToString(" ") { token -> token.replaceFirstChar { it.titlecase(Locale.ROOT) } }
			.ifBlank { id.toString() }
		return if (id.namespace == "minecraft") baseName else "$baseName (${id.namespace})"
	}

	fun logDiagnostics(reason: String) {
		LOGGER.info(
			"MobModelCatalog[{}]: localCount={}, zombieAvailable={}, sample={}",
			reason,
			localTypesById.size,
			localTypesById.containsKey("minecraft:zombie"),
			sortedIds.take(8),
		)
	}
}
