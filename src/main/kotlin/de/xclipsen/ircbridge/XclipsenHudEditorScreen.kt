package de.xclipsen.ircbridge

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import java.io.IOException

class XclipsenHudEditorScreen(
	private val parent: Screen?,
	private val mod: XclipsenIrcBridgeClient,
) : Screen(Text.literal("Xclipsen HUD Editor")) {
	override fun init() {
		super.init()
		addDrawableChild(
			ButtonWidget.builder(Text.literal("Reset HUD")) {
				val context = MinecraftDrawContextHolder.current ?: return@builder
				XclipsenHudManager.elements.forEach { element -> element.reset(context) }
			}.dimensions(width / 2 - 50, height - 56, 100, 20).build(),
		)
	}

	override fun shouldPause(): Boolean = false

	override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
		MinecraftDrawContextHolder.current = context
		context.fill(0, 0, width, height, 0x96000000.toInt())

		XclipsenHudManager.elements.forEach { element ->
			element.drawEditor(context, mouseX, mouseY)
		}

		val dragged = XclipsenHudManager.elements.firstOrNull { it.isDragging }
		context.drawCenteredTextWithShadow(textRenderer, dragged?.displayName ?: "HUD Editor", width / 2, 10, TEXT_WHITE)
		context.drawCenteredTextWithShadow(
			textRenderer,
			"Drag elements | Scroll while dragging to scale | ESC saves",
			width / 2,
			height - 26,
			TEXT_MUTED,
		)

		super.render(context, mouseX, mouseY, delta)
	}

	override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
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

	override fun mouseReleased(click: Click): Boolean {
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

	override fun close() {
		try {
			mod.saveCurrentConfig()
		} catch (_: IOException) {
		}
		client?.setScreen(parent)
	}

	private object MinecraftDrawContextHolder {
		var current: DrawContext? = null
	}

	companion object {
		private const val LEFT_MOUSE_BUTTON = 0
		private const val TEXT_WHITE = 0xFFFFFFFF.toInt()
		private const val TEXT_MUTED = 0xFFA0A0A0.toInt()
	}
}
