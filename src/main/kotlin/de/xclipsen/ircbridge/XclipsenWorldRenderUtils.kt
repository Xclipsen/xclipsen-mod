package de.xclipsen.ircbridge

import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.Box
import kotlin.math.sqrt

object XclipsenWorldRenderUtils {
	fun drawFilledBox(
		entry: MatrixStack.Entry,
		consumer: VertexConsumer,
		minX: Float,
		minY: Float,
		minZ: Float,
		maxX: Float,
		maxY: Float,
		maxZ: Float,
		red: Float,
		green: Float,
		blue: Float,
		alpha: Float,
	) {
		face(consumer, entry, red, green, blue, alpha, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ)
		face(consumer, entry, red, green, blue, alpha, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ)
		face(consumer, entry, red, green, blue, alpha, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ)
		face(consumer, entry, red, green, blue, alpha, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ)
		face(consumer, entry, red, green, blue, alpha, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ)
		face(consumer, entry, red, green, blue, alpha, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, minX, minY, minZ)
	}

	fun drawBox(
		entry: MatrixStack.Entry,
		consumer: VertexConsumer,
		box: Box,
		red: Float,
		green: Float,
		blue: Float,
		alpha: Float,
		lineWidth: Float = 2.0f,
	) {
		val minX = box.minX.toFloat()
		val minY = box.minY.toFloat()
		val minZ = box.minZ.toFloat()
		val maxX = box.maxX.toFloat()
		val maxY = box.maxY.toFloat()
		val maxZ = box.maxZ.toFloat()
		line(consumer, entry, minX, minY, minZ, maxX, minY, minZ, red, green, blue, alpha, lineWidth)
		line(consumer, entry, maxX, minY, minZ, maxX, minY, maxZ, red, green, blue, alpha, lineWidth)
		line(consumer, entry, maxX, minY, maxZ, minX, minY, maxZ, red, green, blue, alpha, lineWidth)
		line(consumer, entry, minX, minY, maxZ, minX, minY, minZ, red, green, blue, alpha, lineWidth)
		line(consumer, entry, minX, maxY, minZ, maxX, maxY, minZ, red, green, blue, alpha, lineWidth)
		line(consumer, entry, maxX, maxY, minZ, maxX, maxY, maxZ, red, green, blue, alpha, lineWidth)
		line(consumer, entry, maxX, maxY, maxZ, minX, maxY, maxZ, red, green, blue, alpha, lineWidth)
		line(consumer, entry, minX, maxY, maxZ, minX, maxY, minZ, red, green, blue, alpha, lineWidth)
		line(consumer, entry, minX, minY, minZ, minX, maxY, minZ, red, green, blue, alpha, lineWidth)
		line(consumer, entry, maxX, minY, minZ, maxX, maxY, minZ, red, green, blue, alpha, lineWidth)
		line(consumer, entry, maxX, minY, maxZ, maxX, maxY, maxZ, red, green, blue, alpha, lineWidth)
		line(consumer, entry, minX, minY, maxZ, minX, maxY, maxZ, red, green, blue, alpha, lineWidth)
	}

	private fun face(
		consumer: VertexConsumer,
		entry: MatrixStack.Entry,
		red: Float,
		green: Float,
		blue: Float,
		alpha: Float,
		vararg xyz: Float,
	) {
		var index = 0
		while (index + 2 < xyz.size) {
			consumer.vertex(entry, xyz[index], xyz[index + 1], xyz[index + 2]).color(red, green, blue, alpha)
			index += 3
		}
	}

	private fun line(
		consumer: VertexConsumer,
		entry: MatrixStack.Entry,
		startX: Float,
		startY: Float,
		startZ: Float,
		endX: Float,
		endY: Float,
		endZ: Float,
		red: Float,
		green: Float,
		blue: Float,
		alpha: Float,
		lineWidth: Float,
	) {
		val dx = endX - startX
		val dy = endY - startY
		val dz = endZ - startZ
		val length = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat().coerceAtLeast(0.0001f)
		val normalX = dx / length
		val normalY = dy / length
		val normalZ = dz / length
		consumer.vertex(entry, startX, startY, startZ).color(red, green, blue, alpha).normal(entry, normalX, normalY, normalZ).lineWidth(lineWidth)
		consumer.vertex(entry, endX, endY, endZ).color(red, green, blue, alpha).normal(entry, normalX, normalY, normalZ).lineWidth(lineWidth)
	}
}
