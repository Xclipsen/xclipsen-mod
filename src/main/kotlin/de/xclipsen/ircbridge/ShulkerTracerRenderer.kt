package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.RenderLayer
import net.minecraft.entity.mob.ShulkerEntity
import net.minecraft.util.math.Vec3d

object ShulkerTracerRenderer {
	fun render(context: WorldRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.hideonleafHelperEnabled || !config.shulkerTracerLineEnabled) {
			return
		}

		val client = MinecraftClient.getInstance()
		val world = client.world ?: return
		if (client.player == null || client.options.hudHidden) {
			return
		}

		val cameraPos = context.gameRenderer().camera.pos
		val target = world.entities
			.asSequence()
			.filterIsInstance<ShulkerEntity>()
			.filter { it.isAlive && !it.isRemoved }
			.minByOrNull { it.squaredDistanceTo(cameraPos) }
			?.boundingBox
			?.center ?: return

		val color = parseColor(config.shulkerTracerLineColorHex) ?: DEFAULT_LINE_COLOR
		drawLine(context, cameraPos, target, color)
	}

	private fun drawLine(context: WorldRenderContext, start: Vec3d, end: Vec3d, color: Int) {
		val matrices = context.matrices()
		val direction = end.subtract(start).normalize()
		if (!direction.x.isFinite() || !direction.y.isFinite() || !direction.z.isFinite()) {
			return
		}

		val red = color shr 16 and 0xFF
		val green = color shr 8 and 0xFF
		val blue = color and 0xFF
		val alpha = 230

		matrices.push()
		matrices.translate(-start.x, -start.y, -start.z)
		val entry = matrices.peek()
		val consumer = context.consumers().getBuffer(RenderLayer.getLines())
		consumer.vertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.color(red, green, blue, alpha)
			.normal(entry, direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
		consumer.vertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
			.color(red, green, blue, alpha)
			.normal(entry, direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
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

	private val HEX_COLOR_PATTERN = Regex("[0-9a-fA-F]{6}")
	private const val DEFAULT_LINE_COLOR = 0x36C5F0
}
