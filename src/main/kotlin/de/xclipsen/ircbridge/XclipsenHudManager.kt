package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

object XclipsenHudManager {
	val elements: List<XclipsenHudElement> = listOf(
		HideonleafLostFightHudElement,
	)

	fun render(context: DrawContext) {
		if (MinecraftClient.getInstance().currentScreen is XclipsenHudEditorScreen) {
			return
		}

		elements.forEach { element ->
			element.renderElement(context, example = false)
		}
	}

	fun showHideonleafLostFightAlert() {
		HideonleafLostFightHudElement.show()
	}
}

private object HideonleafLostFightHudElement : XclipsenHudElement(
	id = "hideonleaf_lost_fight_alert",
	displayName = "Hideonleaf Lost Fight",
) {
	@Volatile
	private var visibleUntil = 0L

	override fun isEnabled(config: BridgeConfig): Boolean =
		config.hideonleafHelperEnabled && config.hideonleafLostFightAlertEnabled

	override fun shouldDraw(config: BridgeConfig): Boolean =
		isEnabled(config) && System.currentTimeMillis() <= visibleUntil

	override fun defaultX(context: DrawContext): Float {
		return ((context.scaledWindowWidth - DEFAULT_WIDTH) / 2f).coerceAtLeast(4f)
	}

	override fun defaultY(context: DrawContext): Float {
		return (context.scaledWindowHeight * 0.32f).coerceAtLeast(30f)
	}

	fun show() {
		visibleUntil = System.currentTimeMillis() + VISIBLE_MS
	}

	override fun draw(context: DrawContext, example: Boolean): Pair<Float, Float> {
		val client = MinecraftClient.getInstance()
		val text = "Hideonleaf lost the fight..."
		val textWidth = client.textRenderer.getWidth(text)
		val width = (textWidth + 18).coerceAtLeast(DEFAULT_WIDTH)
		val height = 22

		context.fill(0, 0, width, height, 0xE6111111.toInt())
		context.fill(0, 0, width, 2, 0xFF36C5F0.toInt())
		context.fill(0, height - 1, width, height, 0x70000000)
		context.drawCenteredTextWithShadow(client.textRenderer, text, width / 2, 7, 0xFFFFFFFF.toInt())

		if (example) {
			context.drawTextWithShadow(client.textRenderer, "Alert", 5, height + 4, 0xFFA0A0A0.toInt())
			return width.toFloat() to (height + client.textRenderer.fontHeight + 6).toFloat()
		}

		return width.toFloat() to height.toFloat()
	}

	private const val VISIBLE_MS = 2_800L
	private const val DEFAULT_WIDTH = 160
}
