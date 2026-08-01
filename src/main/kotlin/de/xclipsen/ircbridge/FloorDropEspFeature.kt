package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.item.Items
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object FloorDropEspFeature {
	fun render(context: WorldRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.floorDropEspModuleEnabled) {
			return
		}

		val client = MinecraftClient.getInstance()
		val world = client.world ?: return
		val player = client.player ?: return
		if (client.options.hudHidden) {
			return
		}

		val drops = findFloorDrops(
			world.entities.asSequence()
				.filterIsInstance<DisplayEntity.ItemDisplayEntity>()
				.filter { it.isAlive && !it.isRemoved && it.itemStack.isOf(Items.STRING) }
				.toList(),
		)
		if (drops.isEmpty()) {
			return
		}

		val cameraPos = context.gameRenderer().camera.cameraPos
		val matrices = context.matrices()
		val consumers = context.consumers()
		val lineLayer = XclipsenRenderLayers.getXrayLine(OUTLINE_WIDTH)
		val fillLayer = XclipsenRenderLayers.getXrayFill()

		matrices.push()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.peek()
		val lineConsumer = consumers.getBuffer(lineLayer)
		val fillConsumer = consumers.getBuffer(fillLayer)

		for (drop in drops) {
			val box = Box(
				drop.x - BOX_RADIUS,
				drop.y - BOX_BELOW,
				drop.z - BOX_RADIUS,
				drop.x + BOX_RADIUS,
				drop.y + BOX_ABOVE,
				drop.z + BOX_RADIUS,
			)
			XclipsenWorldRenderUtils.drawFilledBox(
				entry,
				fillConsumer,
				box.minX.toFloat(),
				box.minY.toFloat(),
				box.minZ.toFloat(),
				box.maxX.toFloat(),
				box.maxY.toFloat(),
				box.maxZ.toFloat(),
				RED,
				GREEN,
				BLUE,
				FILL_ALPHA,
			)
			XclipsenWorldRenderUtils.drawBox(entry, lineConsumer, box, RED, GREEN, BLUE, OUTLINE_ALPHA, OUTLINE_WIDTH.toFloat())
		}
		if (config.floorDropEspTracerEnabled) {
			val playerPos = Vec3d(player.x, player.y, player.z)
			val nearestDrop = drops.minBy { it.squaredDistanceTo(playerPos) }
			drawLine(lineConsumer, entry, crosshairStart(cameraPos, player.yaw, player.pitch), nearestDrop, OUTLINE_WIDTH.toFloat())
		}

		(consumers as? VertexConsumerProvider.Immediate)?.draw(fillLayer)
		(consumers as? VertexConsumerProvider.Immediate)?.draw(lineLayer)
		matrices.pop()
	}

	private fun findFloorDrops(displays: List<DisplayEntity.ItemDisplayEntity>): List<Vec3d> {
		val remaining = displays.toMutableSet()
		val drops = mutableListOf<Vec3d>()
		while (remaining.isNotEmpty()) {
			val first = remaining.first()
			val group = remaining.filter { it.squaredDistanceTo(first) <= GROUP_DISTANCE_SQUARED }
			remaining.removeAll(group.toSet())
			if (group.size != EXPECTED_DISPLAY_COUNT || group.any { display -> group.any { it.squaredDistanceTo(display) > GROUP_DISTANCE_SQUARED } }) {
				continue
			}

			drops += Vec3d(
				group.sumOf { it.x } / group.size,
				group.sumOf { it.y } / group.size,
				group.sumOf { it.z } / group.size,
			)
		}
		return drops
	}

	private fun drawLine(consumer: VertexConsumer, entry: MatrixStack.Entry, start: Vec3d, end: Vec3d, width: Float) {
		val delta = end.subtract(start)
		val lengthSquared = delta.lengthSquared()
		if (lengthSquared < 0.0001) {
			return
		}
		val length = sqrt(lengthSquared)
		val normalX = (delta.x / length).toFloat()
		val normalY = (delta.y / length).toFloat()
		val normalZ = (delta.z / length).toFloat()
		consumer.vertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.color(RED, GREEN, BLUE, LINE_ALPHA)
			.normal(entry, normalX, normalY, normalZ)
			.lineWidth(width)
		consumer.vertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
			.color(RED, GREEN, BLUE, LINE_ALPHA)
			.normal(entry, normalX, normalY, normalZ)
			.lineWidth(width)
	}

	private fun crosshairStart(cameraPos: Vec3d, yaw: Float, pitch: Float): Vec3d {
		val yawRadians = Math.toRadians(yaw.toDouble())
		val pitchRadians = Math.toRadians(pitch.toDouble())
		val horizontalScale = cos(pitchRadians)
		return cameraPos.add(
			-sin(yawRadians) * horizontalScale * TRACER_START_OFFSET,
			-sin(pitchRadians) * TRACER_START_OFFSET,
			cos(yawRadians) * horizontalScale * TRACER_START_OFFSET,
		)
	}

	private const val EXPECTED_DISPLAY_COUNT = 3
	private const val GROUP_DISTANCE_SQUARED = 0.8 * 0.8
	private const val BOX_RADIUS = 0.65
	private const val BOX_BELOW = 0.35
	private const val BOX_ABOVE = 0.8
	private const val OUTLINE_WIDTH = 3.0
	private const val TRACER_START_OFFSET = 2.0
	private const val RED = 1.0f
	private const val GREEN = 0.84f
	private const val BLUE = 0.18f
	private const val FILL_ALPHA = 0.16f
	private const val OUTLINE_ALPHA = 0.95f
	private const val LINE_ALPHA = 0.94f
}
