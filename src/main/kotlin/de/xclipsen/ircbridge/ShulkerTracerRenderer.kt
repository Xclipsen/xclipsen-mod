package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.entity.mob.ShulkerEntity
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.sqrt

object ShulkerTracerRenderer {
	fun render(context: WorldRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		val lineMode = config.shulkerTracerLineMode.coerceIn(0, 3)
		if (!config.hideonleafHelperEnabled || lineMode == 0) {
			return
		}

		val client = MinecraftClient.getInstance()
		val world = client.world ?: return
		if (client.player == null || client.options.hudHidden) {
			return
		}

		val cameraPos = context.gameRenderer().camera.pos
		val shulkerCenters = world.entities
			.asSequence()
			.filterIsInstance<ShulkerEntity>()
			.filter { it.isAlive && !it.isRemoved }
			.sortedBy { it.squaredDistanceTo(cameraPos) }
			.take(lineMode)
			.map { it.boundingBox.center }
			.toList()
		if (shulkerCenters.isEmpty()) {
			return
		}

		val color = parseColor(config.shulkerTracerLineColorHex) ?: DEFAULT_LINE_COLOR
		val start = crosshairStart(context, cameraPos)
		val lineWidth = config.shulkerTracerLineWidth.coerceIn(1.0f, 8.0f)
		var previous = start
		for (center in shulkerCenters) {
			drawLine(context, cameraPos, previous, center, color, lineWidth)
			previous = center
		}
	}

	private fun crosshairStart(context: WorldRenderContext, cameraPos: Vec3d): Vec3d {
		val look = context.gameRenderer().camera.horizontalPlane.normalized()
		return cameraPos.add(
			look.x.toDouble() * CROSSHAIR_OFFSET,
			look.y.toDouble() * CROSSHAIR_OFFSET,
			look.z.toDouble() * CROSSHAIR_OFFSET,
		)
	}

	private fun drawLine(context: WorldRenderContext, cameraPos: Vec3d, start: Vec3d, end: Vec3d, color: Int, width: Float) {
		val matrices = context.matrices()
		val delta = end.subtract(start)
		if (!delta.x.isFinite() || !delta.y.isFinite() || !delta.z.isFinite() || delta.lengthSquared() < 0.0001) {
			return
		}

		val red = color shr 16 and 0xFF
		val green = color shr 8 and 0xFF
		val blue = color and 0xFF
		val alpha = 230

		matrices.push()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.peek()
		val renderLayer = XclipsenRenderLayers.getXrayLine(width.toDouble())
		val consumer = context.consumers().getBuffer(renderLayer)
		consumer.vertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.color(red, green, blue, alpha)
		consumer.vertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
			.color(red, green, blue, alpha)
		(context.consumers() as? VertexConsumerProvider.Immediate)?.draw(renderLayer)
		matrices.pop()
	}

	private fun parseColor(hex: String): Int? {
		val candidate = hex.trim().removePrefix("#")
		if (!HEX_COLOR_PATTERN.matches(candidate)) {
			return null
		}
		return candidate.toInt(16)
	}

	private fun Double.isFinite(): Boolean = !isNaN() && kotlin.math.abs(this) != Double.POSITIVE_INFINITY

	private fun Vector3f.normalized(): Vector3f {
		val length = sqrt((x * x) + (y * y) + (z * z))
		return if (length > 0.0001f) Vector3f(x / length, y / length, z / length) else Vector3f(0f, 0f, 1f)
	}

	private val HEX_COLOR_PATTERN = Regex("[0-9a-fA-F]{6}")
	private const val CROSSHAIR_OFFSET = 2.0
	private const val DEFAULT_LINE_COLOR = 0x36C5F0
}
