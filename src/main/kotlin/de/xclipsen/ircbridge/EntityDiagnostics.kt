package de.xclipsen.ircbridge

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.core.registries.BuiltInRegistries
import org.slf4j.LoggerFactory
import java.util.Locale

object EntityDiagnostics {
	private val LOGGER = LoggerFactory.getLogger("xclipsen_entity_diagnostics")
	private const val SCAN_RADIUS = 50.0

	fun logNearby(client: Minecraft): ScanResult? {
		val world = client.level ?: return null
		val player = client.player ?: return null
		val maxDistanceSquared = SCAN_RADIUS * SCAN_RADIUS
		val entities = world.entitiesForRendering()
			.filter { it.distanceToSqr(player) <= maxDistanceSquared }
			.sortedBy { it.distanceToSqr(player) }
		val itemDisplayCount = entities.count { it is Display.ItemDisplay }

		LOGGER.info("Entity scan: {} entities within {} blocks ({} item displays)", entities.size, SCAN_RADIUS.toInt(), itemDisplayCount)
		for (entity in entities) {
			val typeId = BuiltInRegistries.ENTITY_TYPE.getId(entity.type)
			val distance = entity.distanceTo(player)
			val details = if (entity is Display.ItemDisplay) {
				val stack = entity.getSlot(Entity.CONTENTS_SLOT_INDEX)?.get() ?: ItemStack.EMPTY
				" | item=$stack | name=${stack.hoverName.string} | components=${stack.components}"
			} else {
				""
			}

			LOGGER.info(
				"{} | {} | pos=({}, {}, {}) | distance={}{}",
				typeId,
				entity.javaClass.name,
				format(entity.x),
				format(entity.y),
				format(entity.z),
				format(distance.toDouble()),
				details,
			)
		}

		return ScanResult(entities.size, itemDisplayCount)
	}

	private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

	data class ScanResult(val entityCount: Int, val itemDisplayCount: Int)
}
