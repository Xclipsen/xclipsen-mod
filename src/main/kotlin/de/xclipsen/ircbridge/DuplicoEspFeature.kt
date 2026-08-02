package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Interaction
import net.minecraft.world.entity.monster.Silverfish
import net.minecraft.world.phys.AABB

object DuplicoEspFeature {
	fun statusLine(): String {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return "Unavailable"
		val entities = Minecraft.getInstance().level?.entitiesForRendering()?.toList().orEmpty()
		val blockDisplays = entities.filterIsInstance<Display.BlockDisplay>().filter(::isUsable)
		val itemDisplays = entities.filterIsInstance<Display.ItemDisplay>().filter(::isUsable)
		val displays = blockDisplays + itemDisplays
		val detected = displays.count { isDuplico(it, entities) }
		return "enabled=${config.duplicoEspModuleEnabled}, safari=${LocationTracker.isInSafariArea}, " +
			"mode=${SafariEspMode.displayName(config.safariEspMode)}, blockDisplays=${blockDisplays.size}, " +
			"itemDisplays=${itemDisplays.size}, detected=$detected"
	}

	fun shouldGlow(entity: Entity): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		if (
			!config.duplicoEspModuleEnabled ||
			config.safariEspMode != SafariEspMode.GLOW ||
			!LocationTracker.isInSafariArea ||
			(entity !is Display.BlockDisplay && entity !is Display.ItemDisplay)
		) {
			return false
		}
		val entities = Minecraft.getInstance().level?.entitiesForRendering()?.toList() ?: return false
		return isDuplico(entity, entities)
	}

	fun colorValue(entity: Entity): Int? = DUPLICO_COLOR.takeIf { shouldGlow(entity) }

	fun render(context: LevelRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (
			!config.duplicoEspModuleEnabled ||
			config.safariEspMode != SafariEspMode.BOX_TRACER ||
			!LocationTracker.isInSafariArea
		) {
			return
		}

		val client = Minecraft.getInstance()
		val entities = client.level?.entitiesForRendering()?.toList() ?: return

		val boxes = entities.asSequence()
			.filterIsInstance<Display>()
			.filter { it is Display.BlockDisplay || it is Display.ItemDisplay }
			.filter(::isUsable)
			.filter { isDuplico(it, entities) }
			.map { displayBox(it, entities) }
			.toList()
		SafariEspRenderer.renderBoxes(context, boxes, DUPLICO_COLOR)
	}

	private fun isDuplico(display: Display, entities: List<Entity>): Boolean {
		if (entityNames(display).any(::containsDuplicoName)) {
			return true
		}
		if (display is Display.ItemDisplay && hasDuplicoEntityStack(display, entities)) {
			return true
		}

		return entities.asSequence()
			.filter { it !== display && isUsable(it) && it.distanceToSqr(display) <= NAME_SEARCH_DISTANCE_SQUARED }
			.filter {
				kotlin.math.abs(it.x - display.x) <= NAME_SEARCH_RANGE_XZ &&
					kotlin.math.abs(it.z - display.z) <= NAME_SEARCH_RANGE_XZ &&
					it.y >= display.y - NAME_SEARCH_BELOW &&
					it.y <= display.y + NAME_SEARCH_ABOVE
			}
			.flatMap(::entityNames)
			.any(::containsDuplicoName)
	}

	private fun hasDuplicoEntityStack(display: Display.ItemDisplay, entities: List<Entity>): Boolean {
		val hasInteraction = entities.any {
			it is Interaction && isUsable(it) && it.distanceToSqr(display) <= STACK_POSITION_TOLERANCE_SQUARED
		}
		val hasSilverfish = entities.any {
			it is Silverfish && isUsable(it) && it.distanceToSqr(display) <= STACK_POSITION_TOLERANCE_SQUARED
		}
		return hasInteraction && hasSilverfish
	}

	private fun entityNames(entity: Entity): Sequence<String> = sequence {
		entity.customName?.string?.let { yield(it) }
		if (entity is Display.TextDisplay) {
			yield(entity.text.string)
		}
	}

	private fun containsDuplicoName(raw: String): Boolean = DUPLICO_NAME_PATTERN.containsMatchIn(raw)

	private fun isUsable(entity: Entity): Boolean = entity.isAlive && !entity.isRemoved

	private fun displayBox(display: Display, entities: List<Entity>): AABB {
		val interactionBox = entities.asSequence()
			.filterIsInstance<Interaction>()
			.filter(::isUsable)
			.filter { it.distanceToSqr(display) <= STACK_POSITION_TOLERANCE_SQUARED }
			.minByOrNull { it.distanceToSqr(display) }
			?.boundingBox
		val box = interactionBox ?: display.boundingBox
		if (box.maxX - box.minX >= MIN_BOX_SIZE && box.maxY - box.minY >= MIN_BOX_SIZE && box.maxZ - box.minZ >= MIN_BOX_SIZE) {
			return box.inflate(BOX_EXPANSION)
		}
		return AABB(
			display.x - FALLBACK_BOX_RADIUS,
			display.y - FALLBACK_BOX_RADIUS,
			display.z - FALLBACK_BOX_RADIUS,
			display.x + FALLBACK_BOX_RADIUS,
			display.y + FALLBACK_BOX_RADIUS,
			display.z + FALLBACK_BOX_RADIUS,
		).inflate(BOX_EXPANSION)
	}

	private val DUPLICO_NAME_PATTERN = Regex("(?i)(?:^|[^a-z])duplico(?:$|[^a-z])")
	private const val NAME_SEARCH_DISTANCE_SQUARED = 9.0
	private const val NAME_SEARCH_RANGE_XZ = 1.5
	private const val NAME_SEARCH_BELOW = 1.0
	private const val NAME_SEARCH_ABOVE = 3.0
	private const val STACK_POSITION_TOLERANCE_SQUARED = 0.01
	private const val MIN_BOX_SIZE = 0.1
	private const val BOX_EXPANSION = 0.04
	private const val FALLBACK_BOX_RADIUS = 0.5
	// Feature-specific lime keeps Safari mimics distinct from the existing Galatea ESP colors.
	private const val DUPLICO_COLOR = 0x59FF61
}
