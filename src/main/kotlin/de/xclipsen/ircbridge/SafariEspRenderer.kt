package de.xclipsen.ircbridge

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object SafariEspRenderer {
	fun renderBoxes(context: LevelRenderContext, boxes: List<AABB>, color: Int) {
		if (boxes.isEmpty()) {
			return
		}
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		if (client.options.hideGui) {
			return
		}

		val (red, green, blue) = ClientColor.rgbFloatChannels(color)
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
		val tracerStart = crosshairStart(cameraPos, player)

		for (box in boxes) {
			XclipsenWorldRenderUtils.drawFilledBox(
				entry,
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
				FILL_ALPHA,
			)
			XclipsenWorldRenderUtils.drawBox(entry, lineConsumer, box, red, green, blue, OUTLINE_ALPHA, OUTLINE_WIDTH.toFloat())
			drawTracer(lineConsumer, entry, tracerStart, box.center, color)
		}

		consumers.endBatch(fillLayer)
		consumers.endBatch(lineLayer)
		matrices.popPose()
	}

	private fun drawTracer(consumer: VertexConsumer, entry: PoseStack.Pose, start: Vec3, end: Vec3, color: Int) {
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
		consumer.addVertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.setColor(red, green, blue, TRACER_ALPHA)
			.setNormal(entry, normalX, normalY, normalZ)
			.setLineWidth(OUTLINE_WIDTH.toFloat())
		consumer.addVertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
			.setColor(red, green, blue, TRACER_ALPHA)
			.setNormal(entry, normalX, normalY, normalZ)
			.setLineWidth(OUTLINE_WIDTH.toFloat())
	}

	private fun crosshairStart(cameraPos: Vec3, player: Player): Vec3 {
		val yaw = Math.toRadians(player.yRot.toDouble())
		val pitch = Math.toRadians(player.xRot.toDouble())
		val horizontalScale = cos(pitch)
		return cameraPos.add(
			-sin(yaw) * horizontalScale * TRACER_START_OFFSET,
			-sin(pitch) * TRACER_START_OFFSET,
			cos(yaw) * horizontalScale * TRACER_START_OFFSET,
		)
	}

	private const val OUTLINE_WIDTH = 2.5
	private const val TRACER_START_OFFSET = 2.0
	private const val FILL_ALPHA = 0.14f
	private const val OUTLINE_ALPHA = 0.95f
	private const val TRACER_ALPHA = 235
}
