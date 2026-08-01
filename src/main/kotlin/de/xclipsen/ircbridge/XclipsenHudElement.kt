package de.xclipsen.ircbridge

import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max

typealias Tuple<A, B> = Pair<A, B>

abstract class XclipsenHudElement(
	val id: String,
	val displayName: String,
) {
	var width: Float = 80f
		private set
	var height: Float = 20f
		private set
	var isDragging: Boolean = false
		private set

	private var dragOffsetX = 0f
	private var dragOffsetY = 0f

	open fun isEnabled(config: BridgeConfig): Boolean = true

	open fun shouldDraw(config: BridgeConfig): Boolean = isEnabled(config)

	open fun defaultX(context: GuiGraphicsExtractor): Float = 20f

	open fun defaultY(context: GuiGraphicsExtractor): Float = 20f

	fun renderElement(context: GuiGraphicsExtractor, example: Boolean) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!example && !shouldDraw(config)) {
			return
		}

		val placement = placement(context)
		val matrices = context.pose()
		matrices.pushMatrix()
		matrices.translate(placement.x, placement.y)
		matrices.scale(placement.scale)
		val size = draw(context, example)
		width = max(1f, size.first)
		height = max(1f, size.second)
		matrices.popMatrix()
	}

	fun drawEditor(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!isEnabled(config)) {
			return
		}

		val placement = placement(context)
		if (isDragging) {
			val maxX = (context.guiWidth() - width * placement.scale).coerceAtLeast(1f)
			val maxY = (context.guiHeight() - height * placement.scale).coerceAtLeast(1f)
			placement.x = (mouseX - dragOffsetX).coerceIn(0f, maxX)
			placement.y = (mouseY - dragOffsetY).coerceIn(0f, maxY)
		}

		drawEditorBackground(context, mouseX, mouseY)
		renderElement(context, example = true)
	}

	fun startDragging(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
		val placement = placement(context)
		if (!isHovered(context, mouseX, mouseY)) {
			return false
		}

		isDragging = true
		dragOffsetX = mouseX - placement.x
		dragOffsetY = mouseY - placement.y
		return true
	}

	fun stopDragging() {
		isDragging = false
	}

	fun scaleBy(context: GuiGraphicsExtractor, delta: Float) {
		val placement = placement(context)
		placement.scale = (placement.scale + delta).coerceIn(0.5f, 4f)
	}

	fun reset(context: GuiGraphicsExtractor) {
		val placement = placement(context)
		placement.x = defaultX(context)
		placement.y = defaultY(context)
		placement.scale = 1f
	}

	protected fun currentPlacement(context: GuiGraphicsExtractor): HudElementPlacement = placement(context)

	protected abstract fun draw(context: GuiGraphicsExtractor, example: Boolean): Tuple<Float, Float>

	private fun drawEditorBackground(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		val placement = placement(context)
		val scaledWidth = (width * placement.scale).toInt()
		val scaledHeight = (height * placement.scale).toInt()
		val left = placement.x.toInt()
		val top = placement.y.toInt()
		val hovered = isHovered(context, mouseX, mouseY)
		val borderColor = if (isDragging || hovered) ACCENT else 0x50FFFFFF

		context.fill(left, top, left + scaledWidth, top + scaledHeight, 0x760A0A0A)
		context.fill(left, top, left + scaledWidth, top + 1, borderColor)
		context.fill(left, top + scaledHeight - 1, left + scaledWidth, top + scaledHeight, borderColor)
		context.fill(left, top, left + 1, top + scaledHeight, borderColor)
		context.fill(left + scaledWidth - 1, top, left + scaledWidth, top + scaledHeight, borderColor)
	}

	private fun isHovered(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
		val placement = placement(context)
		val scaledWidth = width * placement.scale
		val scaledHeight = height * placement.scale
		return mouseX >= placement.x &&
			mouseX <= placement.x + scaledWidth &&
			mouseY >= placement.y &&
			mouseY <= placement.y + scaledHeight
	}

	private fun placement(context: GuiGraphicsExtractor): HudElementPlacement {
		val config = XclipsenIrcBridgeClient.instance?.config()
		val map = config?.hudElements
		val current = map?.get(id)
		if (current != null && current.x.isFinite() && current.y.isFinite() && current.x >= 0f && current.y >= 0f) {
			current.scale = current.scale.takeIf { it.isFinite() }?.coerceIn(0.5f, 4f) ?: 1f
			return current
		}

		val fallback = HudElementPlacement(defaultX(context), defaultY(context), 1f)
		map?.set(id, fallback)
		return fallback
	}

	companion object {
		private const val ACCENT = 0xFF36C5F0.toInt()
	}
}
