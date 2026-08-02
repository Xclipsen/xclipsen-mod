package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Silverfish
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object PestEspFeature {
	fun statusLine(): String {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return "Unavailable"
		val pests = Minecraft.getInstance().level?.entitiesForRendering()?.count { it is LivingEntity && isGardenPest(it) } ?: 0
		return "enabled=${config.pestEspModuleEnabled}, onGarden=${LocationTracker.isOnGarden}, detected=$pests"
	}

	fun render(context: LevelRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.pestEspModuleEnabled || !LocationTracker.isOnGarden) {
			return
		}

		val client = Minecraft.getInstance()
		val world = client.level ?: return
		val player = client.player ?: return
		if (client.options.hideGui) {
			return
		}

		val pests = world.entitiesForRendering().asSequence()
			.filterIsInstance<LivingEntity>()
			.filter(::isGardenPest)
			.toList()
		if (pests.isEmpty()) {
			return
		}

		val color = ClientColor.parseRgb(config.pestEspColorHex) ?: DEFAULT_COLOR
		val (redFloat, greenFloat, blueFloat) = ClientColor.rgbFloatChannels(color)
		val cameraPos = context.levelState().cameraRenderState.pos
		val matrices = context.poseStack()
		val consumers = context.bufferSource()

		matrices.pushPose()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.last()
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

		consumers.endBatch(XclipsenRenderLayers.getXrayFill())
		consumers.endBatch(lineLayer)
		matrices.popPose()
	}

	private fun drawBoundingBox(
		pest: LivingEntity,
		fillConsumer: VertexConsumer,
		lineConsumer: VertexConsumer,
		entry: com.mojang.blaze3d.vertex.PoseStack.Pose,
		matrices: com.mojang.blaze3d.vertex.PoseStack,
		red: Float,
		green: Float,
		blue: Float,
	) {
		val box = pest.boundingBox.inflate(BOX_EXPANSION_XZ, BOX_EXPANSION_Y, BOX_EXPANSION_XZ)
		XclipsenWorldRenderUtils.drawFilledBox(
			matrices.last(),
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
		XclipsenWorldRenderUtils.drawBox(entry, lineConsumer, box, red, green, blue, BOX_OUTLINE_ALPHA, OUTLINE_WIDTH.toFloat())
	}

	private fun drawLine(
		lineConsumer: VertexConsumer,
		entry: com.mojang.blaze3d.vertex.PoseStack.Pose,
		start: Vec3,
		end: Vec3,
		color: Int,
	) {
		val delta = end.subtract(start)
		val lengthSquared = delta.lengthSqr()
		if (!delta.x.isFinite() || !delta.y.isFinite() || !delta.z.isFinite() || lengthSquared < 0.0001) {
			return
		}

		val length = sqrt(lengthSquared)
		val normalX = (delta.x / length).toFloat()
		val normalY = (delta.y / length).toFloat()
		val normalZ = (delta.z / length).toFloat()
		val (red, green, blue) = ClientColor.rgbChannels(color)

		lineConsumer.addVertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.setColor(red, green, blue, LINE_ALPHA)
			.setNormal(entry, normalX, normalY, normalZ)
			.setLineWidth(OUTLINE_WIDTH.toFloat())
		lineConsumer.addVertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
			.setColor(red, green, blue, LINE_ALPHA)
			.setNormal(entry, normalX, normalY, normalZ)
			.setLineWidth(OUTLINE_WIDTH.toFloat())
	}

	private fun crosshairStart(cameraPos: Vec3, player: Player): Vec3 {
		val yawRadians = Math.toRadians(player.yRot.toDouble())
		val pitchRadians = Math.toRadians(player.xRot.toDouble())
		val horizontalScale = cos(pitchRadians)
		val look = Vec3(
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
		if (entity !is LivingEntity || entity is Player || entity is ArmorStand) {
			return false
		}
		if (entity !is Bat && entity !is Silverfish) {
			return false
		}

		return resolveDisplayNames(entity).any(::containsPestName)
	}

	private fun resolveDisplayNames(entity: LivingEntity): Sequence<String> = sequence {
		entity.customName?.string?.trim()?.takeIf { it.isNotEmpty() }?.let { yield(it) }
		entity.name.string.trim().takeIf { it.isNotEmpty() }?.let { yield(it) }

		val searchBox = entity.boundingBox.inflate(NAME_SEARCH_RANGE_XZ, NAME_SEARCH_RANGE_Y, NAME_SEARCH_RANGE_XZ)
		val nearbyArmorStands = entity.level().getEntities(entity, searchBox) { candidate ->
			candidate is ArmorStand &&
				candidate.isAlive &&
				!candidate.isRemoved &&
				candidate.distanceToSqr(entity) <= NAME_SEARCH_DISTANCE_SQUARED &&
				kotlin.math.abs(candidate.x - entity.x) <= MAX_NAME_OFFSET_XZ &&
				kotlin.math.abs(candidate.z - entity.z) <= MAX_NAME_OFFSET_XZ &&
				candidate.y >= entity.y - MAX_NAME_OFFSET_BELOW &&
				candidate.y <= entity.y + MAX_NAME_OFFSET_ABOVE
		}.filterIsInstance<ArmorStand>()

		for (stand in nearbyArmorStands.sortedBy { it.distanceToSqr(entity) }) {
			stand.customName?.string?.trim()?.takeIf { it.isNotEmpty() }?.let { yield(it) }
			stand.name.string.trim().takeIf { it.isNotEmpty() }?.let { yield(it) }
		}
	}

	private fun containsPestName(candidate: String): Boolean {
		return PEST_NAMES.any { pestName -> candidate.contains(pestName, ignoreCase = true) }
	}

	private fun Double.isFinite(): Boolean = !isNaN() && kotlin.math.abs(this) != Double.POSITIVE_INFINITY

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
		"Field MouseHandler",
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
