package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.render.XclipsenRenderLayers
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.entity.Display
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object FloorDropEspFeature {
	fun statusLine(): String {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return "Unavailable"
		val world = Minecraft.getInstance().level ?: return "enabled=${config.floorDropEspModuleEnabled}, world=unavailable"
		val displays = world.entitiesForRendering().count { it is Display.ItemDisplay && it.isAlive && !it.isRemoved && it.itemStack.item === Items.STRING }
		return "enabled=${config.floorDropEspModuleEnabled}, candidateDisplays=$displays"
	}

	fun render(context: LevelRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.floorDropEspModuleEnabled) {
			return
		}

		val client = Minecraft.getInstance()
		val world = client.level ?: return
		val player = client.player ?: return
		if (client.options.hideGui) {
			return
		}

		val drops = findFloorDrops(
			world.entitiesForRendering().asSequence()
				.filterIsInstance<Display.ItemDisplay>()
				.filter { it.isAlive && !it.isRemoved && it.itemStack.item === Items.STRING }
				.toList(),
		)
		if (drops.isEmpty()) {
			return
		}

		val cameraPos = context.levelState().cameraRenderState.pos
		val matrices = context.poseStack()
		val consumers = context.bufferSource()
		val lineLayer = XclipsenRenderLayers.getXrayLine(OUTLINE_WIDTH)
		val fillLayer = XclipsenRenderLayers.getXrayFill()

		matrices.pushPose()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.last()
		val lineConsumer = consumers.getBuffer(lineLayer)
		val fillConsumer = consumers.getBuffer(fillLayer)

		for (drop in drops) {
			val box = AABB(
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
			val playerPos = player.position()
			val nearestDrop = drops.minBy { it.distanceToSqr(playerPos) }
			drawLine(lineConsumer, entry, crosshairStart(cameraPos, player.yRot, player.xRot), nearestDrop, OUTLINE_WIDTH.toFloat())
		}

		consumers.endBatch(fillLayer)
		consumers.endBatch(lineLayer)
		matrices.popPose()
	}

	private fun findFloorDrops(displays: List<Display.ItemDisplay>): List<Vec3> {
		val remaining = displays.toMutableSet()
		val drops = mutableListOf<Vec3>()
		while (remaining.isNotEmpty()) {
			val first = remaining.first()
			val group = remaining.filter { it.distanceToSqr(first) <= GROUP_DISTANCE_SQUARED }
			remaining.removeAll(group.toSet())
			if (group.size != EXPECTED_DISPLAY_COUNT || group.any { display -> group.any { it.distanceToSqr(display) > GROUP_DISTANCE_SQUARED } }) {
				continue
			}

			drops += Vec3(
				group.sumOf { it.x } / group.size,
				group.sumOf { it.y } / group.size,
				group.sumOf { it.z } / group.size,
			)
		}
		return drops
	}

	private fun drawLine(consumer: VertexConsumer, entry: PoseStack.Pose, start: Vec3, end: Vec3, width: Float) {
		val delta = end.subtract(start)
		val lengthSquared = delta.lengthSqr()
		if (lengthSquared < 0.0001) {
			return
		}
		val length = sqrt(lengthSquared)
		val normalX = (delta.x / length).toFloat()
		val normalY = (delta.y / length).toFloat()
		val normalZ = (delta.z / length).toFloat()
		consumer.addVertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.setColor(RED, GREEN, BLUE, LINE_ALPHA)
			.setNormal(entry, normalX, normalY, normalZ)
			.setLineWidth(width)
		consumer.addVertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
			.setColor(RED, GREEN, BLUE, LINE_ALPHA)
			.setNormal(entry, normalX, normalY, normalZ)
			.setLineWidth(width)
	}

	private fun crosshairStart(cameraPos: Vec3, yaw: Float, pitch: Float): Vec3 {
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
