package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.registry.Registries
import org.slf4j.LoggerFactory
import java.util.Locale

object EntityDiagnostics {
	private val LOGGER = LoggerFactory.getLogger("xclipsen_entity_diagnostics")
	private const val SCAN_RADIUS = 50.0

	fun logNearby(client: MinecraftClient): ScanResult? {
		val world = client.world ?: return null
		val player = client.player ?: return null
		val maxDistanceSquared = SCAN_RADIUS * SCAN_RADIUS
		val entities = world.entities
			.filter { it.squaredDistanceTo(player) <= maxDistanceSquared }
			.sortedBy { it.squaredDistanceTo(player) }
		val itemDisplayCount = entities.count { it is DisplayEntity.ItemDisplayEntity }

		LOGGER.info("Entity scan: {} entities within {} blocks ({} item displays)", entities.size, SCAN_RADIUS.toInt(), itemDisplayCount)
		for (entity in entities) {
			val typeId = Registries.ENTITY_TYPE.getId(entity.type)
			val distance = entity.distanceTo(player)
			val details = if (entity is DisplayEntity.ItemDisplayEntity) {
				val stack = entity.itemStack
				" | item=$stack | name=${stack.name.string} | components=${stack.components}"
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
