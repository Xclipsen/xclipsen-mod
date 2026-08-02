package de.xclipsen.ircbridge

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen

object XclipsenHudManager {
	val elements: List<XclipsenHudElement> = listOf(
		IrcChatTabHudElement,
		InventoryPreviewHudElement,
		HideonleafLostFightHudElement,
		HideonleafShardTrackerHudElement,
		M5IceSprayHudElement,
		M5AlertHudElement,
		FireFreezeTimersHudElement,
		FireFreezeRefreezeAlertHudElement,
		DeploybleAlertHudElement,
		SlayerSpawnAnnouncerHudElement,
		SlayerRngMeterHudElement,
		WormholeDepartureAlertHudElement,
		PickaxeAbilityCooldownHudElement,
		PickaxeAbilityReadyAlertHudElement,
	)

	fun render(context: GuiGraphicsExtractor) {
		if (Minecraft.getInstance().screen is XclipsenHudEditorScreen) {
			return
		}

		elements.forEach { element ->
			element.renderElement(context, example = false)
		}
	}

	/**
	 * Called by ScreenMouseClickHandler for every left-click on any Screen.
	 * Returns true if the click was consumed (prevents the screen from also handling it).
	 */
	fun handleScreenClick(mouseX: Int, mouseY: Int, button: Int): Boolean {
		if (button != 0) return false
		val client = Minecraft.getInstance()
		// Don't intercept clicks inside our own editor or config screens
		if (client.screen is XclipsenHudEditorScreen) return false
		if (client.screen is ChatScreen && IrcChatTabHudElement.handleClick(mouseX, mouseY)) return true
		return HideonleafShardTrackerHudElement.handleClick(mouseX, mouseY)
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

	override fun defaultX(context: GuiGraphicsExtractor): Float {
		return ((context.guiWidth() - DEFAULT_WIDTH) / 2f).coerceAtLeast(4f)
	}

	override fun defaultY(context: GuiGraphicsExtractor): Float {
		return (context.guiHeight() * 0.32f).coerceAtLeast(30f)
	}

	fun show() {
		visibleUntil = System.currentTimeMillis() + VISIBLE_MS
	}

	override fun draw(context: GuiGraphicsExtractor, example: Boolean): Tuple<Float, Float> {
		val client = Minecraft.getInstance()
		val text = "Hideonleaf lost the fight..."
		val textWidth = client.font.width(text)
		val width = textWidth.coerceAtLeast(DEFAULT_WIDTH)
		val height = client.font.lineHeight

		context.text(client.font, text, (width - textWidth) / 2, 0, 0xFFFFFFFF.toInt(), true)

		if (example) {
			context.text(client.font, "Alert", 5, height + 4, 0xFFA0A0A0.toInt(), true)
			return width.toFloat() to (height + client.font.lineHeight + 6).toFloat()
		}

		return width.toFloat() to height.toFloat()
	}

	private const val VISIBLE_MS = 2_800L
	private const val DEFAULT_WIDTH = 160
}
