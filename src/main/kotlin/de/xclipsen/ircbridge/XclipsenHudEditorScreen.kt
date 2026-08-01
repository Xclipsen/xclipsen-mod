package de.xclipsen.ircbridge

import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component
import java.io.IOException

class XclipsenHudEditorScreen(
	private val parent: Screen?,
	private val mod: XclipsenIrcBridgeClient,
) : Screen(Component.literal("Xclipsen HUD Editor")) {
	override fun init() {
		super.init()
		addRenderableWidget(
			Button.builder(Component.literal("Reset HUD")) {
				val context = MinecraftDrawContextHolder.current ?: return@builder
				XclipsenHudManager.elements.forEach { element -> element.reset(context) }
			}.bounds(width / 2 - 50, height - 56, 100, 20).build(),
		)
	}

	override fun isPauseScreen(): Boolean = false

	override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		MinecraftDrawContextHolder.current = context
		context.fill(0, 0, width, height, 0x96000000.toInt())

		XclipsenHudManager.elements.forEach { element ->
			element.drawEditor(context, mouseX, mouseY)
		}

		val dragged = XclipsenHudManager.elements.firstOrNull { it.isDragging }
		context.centeredText(font, dragged?.displayName ?: "HUD Editor", width / 2, 10, TEXT_WHITE)
		context.centeredText(
			font,
			"Drag elements | Scroll while dragging to scale | ESC saves",
			width / 2,
			height - 26,
			TEXT_MUTED,
		)

		super.extractRenderState(context, mouseX, mouseY, delta)
	}

	override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
		if (click.button() < 0) {
			return false
		}

		if (super.mouseClicked(click, doubled)) {
			return true
		}

		if (click.button() == LEFT_MOUSE_BUTTON) {
			val context = MinecraftDrawContextHolder.current ?: return false
			XclipsenHudManager.elements.asReversed().forEach { element ->
				if (element.startDragging(context, click.x().toInt(), click.y().toInt())) {
					return true
				}
			}
		}

		return false
	}

	override fun mouseReleased(click: MouseButtonEvent): Boolean {
		XclipsenHudManager.elements.forEach { element -> element.stopDragging() }
		if (click.button() < 0) {
			return false
		}
		return super.mouseReleased(click)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
		val context = MinecraftDrawContextHolder.current ?: return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
		XclipsenHudManager.elements.forEach { element ->
			if (element.isDragging) {
				element.scaleBy(context, (verticalAmount * 0.1).toFloat())
				return true
			}
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
	}

	override fun onClose() {
		try {
			mod.saveCurrentConfig()
		} catch (_: IOException) {
		}
		minecraft.setScreen(parent)
	}

	private object MinecraftDrawContextHolder {
		var current: GuiGraphicsExtractor? = null
	}

	companion object {
		private const val LEFT_MOUSE_BUTTON = 0
		private const val TEXT_WHITE = 0xFFFFFFFF.toInt()
		private const val TEXT_MUTED = 0xFFA0A0A0.toInt()
	}
}
