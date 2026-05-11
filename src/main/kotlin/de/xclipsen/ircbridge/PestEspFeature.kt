package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.entity.mob.SilverfishEntity
import net.minecraft.entity.passive.BatEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object PestEspFeature {
	fun render(context: WorldRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.pestEspModuleEnabled || !LocationTracker.isOnGarden) {
			return
		}

		val client = MinecraftClient.getInstance()
		val world = client.world ?: return
		val player = client.player ?: return
		if (client.options.hudHidden) {
			return
		}

		val pests = world.entities.asSequence()
			.filterIsInstance<LivingEntity>()
			.filter(::isGardenPest)
			.toList()
		if (pests.isEmpty()) {
			return
		}

		val color = parseColor(config.pestEspColorHex) ?: DEFAULT_COLOR
		val red = color shr 16 and 0xFF
		val green = color shr 8 and 0xFF
		val blue = color and 0xFF
		val redFloat = red / 255.0f
		val greenFloat = green / 255.0f
		val blueFloat = blue / 255.0f
		val cameraPos = context.gameRenderer().camera.cameraPos
		val matrices = context.matrices()
		val consumers = context.consumers()

		matrices.push()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.peek()
		val lineLayer = XclipsenRenderLayers.getXrayLine(OUTLINE_WIDTH)
		val lineConsumer = consumers.getBuffer(lineLayer)
		val fillConsumer = consumers.getBuffer(XclipsenRenderLayers.getXrayFill())

		for (pest in pests) {
			drawBoundingBox(pest, fillConsumer, lineConsumer, entry, matrices, redFloat, greenFloat, blueFloat)
			if (config.pestEspTracerEnabled) {
				drawLine(
					lineConsumer,
					entry,
					crosshairStart(cameraPos, player),
					pest.boundingBox.center,
					color,
				)
			}
		}

		(consumers as? VertexConsumerProvider.Immediate)?.draw(XclipsenRenderLayers.getXrayFill())
		(consumers as? VertexConsumerProvider.Immediate)?.draw(lineLayer)
		matrices.pop()
	}

	private fun drawBoundingBox(
		pest: LivingEntity,
		fillConsumer: VertexConsumer,
		lineConsumer: VertexConsumer,
		entry: net.minecraft.client.util.math.MatrixStack.Entry,
		matrices: net.minecraft.client.util.math.MatrixStack,
		red: Float,
		green: Float,
		blue: Float,
	) {
		val box = pest.boundingBox.expand(BOX_EXPANSION_XZ, BOX_EXPANSION_Y, BOX_EXPANSION_XZ)
		VertexRendering.drawFilledBox(
			matrices,
			fillConsumer,
			box.minX.toFloat(),
			box.minY.toFloat(),
			box.minZ.toFloat(),
			box.maxX.toFloat(),
			box.maxY.toFloat(),
			box.maxZ.toFloat(),
			red,
			green,
			blue,
			BOX_FILL_ALPHA,
		)
		VertexRendering.drawBox(entry, lineConsumer, box, red, green, blue, BOX_OUTLINE_ALPHA)
	}

	private fun drawLine(
		lineConsumer: VertexConsumer,
		entry: net.minecraft.client.util.math.MatrixStack.Entry,
		start: Vec3d,
		end: Vec3d,
		color: Int,
	) {
		val delta = end.subtract(start)
		val lengthSquared = delta.lengthSquared()
		if (!delta.x.isFinite() || !delta.y.isFinite() || !delta.z.isFinite() || lengthSquared < 0.0001) {
			return
		}

		val length = sqrt(lengthSquared)
		val normalX = (delta.x / length).toFloat()
		val normalY = (delta.y / length).toFloat()
		val normalZ = (delta.z / length).toFloat()
		val red = color shr 16 and 0xFF
		val green = color shr 8 and 0xFF
		val blue = color and 0xFF

		lineConsumer.vertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.color(red, green, blue, LINE_ALPHA)
			.normal(entry, normalX, normalY, normalZ)
		lineConsumer.vertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
			.color(red, green, blue, LINE_ALPHA)
			.normal(entry, normalX, normalY, normalZ)
	}

	private fun crosshairStart(cameraPos: Vec3d, player: PlayerEntity): Vec3d {
		val yawRadians = Math.toRadians(player.yaw.toDouble())
		val pitchRadians = Math.toRadians(player.pitch.toDouble())
		val horizontalScale = cos(pitchRadians)
		val look = Vec3d(
			-sin(yawRadians) * horizontalScale,
			-sin(pitchRadians),
			cos(yawRadians) * horizontalScale,
		)
		return cameraPos.add(
			look.x * CROSSHAIR_OFFSET,
			look.y * CROSSHAIR_OFFSET,
			look.z * CROSSHAIR_OFFSET,
		)
	}

	private fun isGardenPest(entity: Entity): Boolean {
		if (!entity.isAlive || entity.isRemoved) {
			return false
		}
		if (entity !is LivingEntity || entity is PlayerEntity || entity is ArmorStandEntity) {
			return false
		}
		if (entity !is BatEntity && entity !is SilverfishEntity) {
			return false
		}

		return resolveDisplayNames(entity).any(::containsPestName)
	}

	private fun resolveDisplayNames(entity: LivingEntity): Sequence<String> = sequence {
		entity.customName?.string?.trim()?.takeIf { it.isNotEmpty() }?.let { yield(it) }
		entity.name.string.trim().takeIf { it.isNotEmpty() }?.let { yield(it) }

		val searchBox = entity.boundingBox.expand(NAME_SEARCH_RANGE_XZ, NAME_SEARCH_RANGE_Y, NAME_SEARCH_RANGE_XZ)
		val nearbyArmorStands = entity.entityWorld.getEntitiesByClass(
			ArmorStandEntity::class.java,
			searchBox,
		) { stand ->
			stand.isAlive &&
				!stand.isRemoved &&
				stand.squaredDistanceTo(entity) <= NAME_SEARCH_DISTANCE_SQUARED &&
				kotlin.math.abs(stand.x - entity.x) <= MAX_NAME_OFFSET_XZ &&
				kotlin.math.abs(stand.z - entity.z) <= MAX_NAME_OFFSET_XZ &&
				stand.y >= entity.y - MAX_NAME_OFFSET_BELOW &&
				stand.y <= entity.y + MAX_NAME_OFFSET_ABOVE
		}

		for (stand in nearbyArmorStands.sortedBy { it.squaredDistanceTo(entity) }) {
			stand.customName?.string?.trim()?.takeIf { it.isNotEmpty() }?.let { yield(it) }
			stand.name.string.trim().takeIf { it.isNotEmpty() }?.let { yield(it) }
		}
	}

	private fun containsPestName(candidate: String): Boolean {
		return PEST_NAMES.any { pestName -> candidate.contains(pestName, ignoreCase = true) }
	}

	private fun parseColor(hex: String): Int? {
		val candidate = hex.trim().removePrefix("#")
		if (!HEX_COLOR_PATTERN.matches(candidate)) {
			return null
		}
		return candidate.toInt(16)
	}

	private fun Double.isFinite(): Boolean = !isNaN() && kotlin.math.abs(this) != Double.POSITIVE_INFINITY

	private val HEX_COLOR_PATTERN = Regex("[0-9a-fA-F]{6}")
	private const val NAME_SEARCH_RANGE_XZ = 2.25
	private const val NAME_SEARCH_RANGE_Y = 3.5
	private const val NAME_SEARCH_DISTANCE_SQUARED = 12.25
	private const val MAX_NAME_OFFSET_XZ = 1.5
	private const val MAX_NAME_OFFSET_BELOW = 0.75
	private const val MAX_NAME_OFFSET_ABOVE = 2.75
	private const val DEFAULT_COLOR = 0x7CFF6B
	private const val CROSSHAIR_OFFSET = 2.0
	private const val BOX_EXPANSION_XZ = 0.28
	private const val BOX_EXPANSION_Y = 0.4
	private const val OUTLINE_WIDTH = 2.0
	private const val BOX_FILL_ALPHA = 0.12f
	private const val BOX_OUTLINE_ALPHA = 0.9f
	private const val LINE_ALPHA = 230
	private val PEST_NAMES = setOf(
		"Beetle",
		"Cricket",
		"Earthworm",
		"Field Mouse",
		"Fly",
		"Locust",
		"Mite",
		"Mosquito",
		"Moth",
		"Rat",
		"Slug",
		"Praying Mantis",
		"Firefly",
		"Dragonfly",
	)
}
