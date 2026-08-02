package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player

object HideyhoEspFeature {
	fun statusLine(): String {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return "Unavailable"
		val client = Minecraft.getInstance()
		val entities = client.level?.entitiesForRendering()?.toList().orEmpty()
		val detected = entities.filterIsInstance<Player>().count { isHideyho(it, entities, client.player) }
		return "enabled=${config.hideyhoEspModuleEnabled}, safari=${LocationTracker.isInSafariArea}, " +
			"mode=${SafariEspMode.displayName(config.safariEspMode)}, detected=$detected"
	}

	fun shouldGlow(entity: Entity): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		if (
			!config.hideyhoEspModuleEnabled ||
			config.safariEspMode != SafariEspMode.GLOW ||
			!LocationTracker.isInSafariArea ||
			entity !is Player
		) {
			return false
		}
		val client = Minecraft.getInstance()
		val entities = client.level?.entitiesForRendering()?.toList() ?: return false
		return isHideyho(entity, entities, client.player)
	}

	fun colorValue(entity: Entity): Int? = HIDEYHO_COLOR.takeIf { shouldGlow(entity) }

	fun render(context: LevelRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (
			!config.hideyhoEspModuleEnabled ||
			config.safariEspMode != SafariEspMode.BOX_TRACER ||
			!LocationTracker.isInSafariArea
		) {
			return
		}
		val client = Minecraft.getInstance()
		val entities = client.level?.entitiesForRendering()?.toList() ?: return
		val boxes = entities.asSequence()
			.filterIsInstance<Player>()
			.filter { isHideyho(it, entities, client.player) }
			.map { it.boundingBox.inflate(BOX_EXPANSION) }
			.toList()
		SafariEspRenderer.renderBoxes(context, boxes, HIDEYHO_COLOR)
	}

	private fun isHideyho(player: Player, entities: List<Entity>, localPlayer: Player?): Boolean {
		if (!player.isAlive || player.isRemoved || player === localPlayer) {
			return false
		}
		if (entityNames(player).any(::containsHideyhoName)) {
			return true
		}
		return entities.asSequence()
			.filterIsInstance<ArmorStand>()
			.filter { it.isAlive && !it.isRemoved && it.distanceToSqr(player) <= NAME_SEARCH_DISTANCE_SQUARED }
			.filter {
				kotlin.math.abs(it.x - player.x) <= NAME_SEARCH_RANGE_XZ &&
					kotlin.math.abs(it.z - player.z) <= NAME_SEARCH_RANGE_XZ &&
					it.y >= player.y + NAME_SEARCH_MIN_ABOVE &&
					it.y <= player.y + NAME_SEARCH_MAX_ABOVE
			}
			.flatMap(::entityNames)
			.any(::containsHideyhoName)
	}

	private fun entityNames(entity: Entity): Sequence<String> = sequence {
		entity.customName?.string?.let { yield(it) }
		yield(entity.name.string)
	}

	private fun containsHideyhoName(raw: String): Boolean = HIDEYHO_NAME_PATTERN.containsMatchIn(raw)

	private val HIDEYHO_NAME_PATTERN = Regex("(?i)(?:^|[^a-z])hideyho(?:$|[^a-z])")
	private const val NAME_SEARCH_DISTANCE_SQUARED = 12.25
	private const val NAME_SEARCH_RANGE_XZ = 1.25
	private const val NAME_SEARCH_MIN_ABOVE = 0.5
	private const val NAME_SEARCH_MAX_ABOVE = 3.25
	private const val BOX_EXPANSION = 0.08
	// Feature-specific orange separates Hideyho from lime Duplico targets.
	private const val HIDEYHO_COLOR = 0xFF9D32
}
