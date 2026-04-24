package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.hud.ChatHud
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.text.Text

object IrcChatTabManager {
	enum class ChatTab {
		MAIN,
		IRC,
	}

	@Volatile
	private var activeTab: ChatTab = ChatTab.MAIN

	@Volatile
	private var ircChatHud: ChatHud? = null

	fun activeTab(): ChatTab = activeTab

	fun isIrcTabActive(): Boolean = activeTab == ChatTab.IRC

	fun isSecondaryChatHud(chatHud: ChatHud): Boolean = ircChatHud === chatHud

	fun shouldProxy(mainChatHud: ChatHud, client: MinecraftClient?): Boolean {
		return client?.inGameHud?.chatHud === mainChatHud && isIrcTabActive()
	}

	fun ircChatHud(client: MinecraftClient): ChatHud {
		val current = ircChatHud
		if (current != null) {
			return current
		}

		return synchronized(this) {
			ircChatHud ?: ChatHud(client).also { ircChatHud = it }
		}
	}

	fun addIrcMessage(message: Text) {
		val client = MinecraftClient.getInstance()
		client.execute {
			ircChatHud(client).addMessage(message)
		}
	}

	fun clearIrcChat(clearHistory: Boolean) {
		ircChatHud?.clear(clearHistory)
	}

	fun resetIrcChat() {
		ircChatHud?.reset()
	}

	fun toggleTab() {
		activeTab = if (activeTab == ChatTab.MAIN) ChatTab.IRC else ChatTab.MAIN
	}

	fun setActiveTab(tab: ChatTab) {
		activeTab = tab
	}
}

object IrcChatTabHudElement : XclipsenHudElement(
	id = "irc_chat_tab_button",
	displayName = "IRC Chat Tab",
) {
	override fun isEnabled(config: BridgeConfig): Boolean = true

	override fun shouldDraw(config: BridgeConfig): Boolean =
		config.ircBridgeEnabled && MinecraftClient.getInstance().currentScreen is ChatScreen

	override fun defaultX(context: DrawContext): Float = 6f

	override fun defaultY(context: DrawContext): Float =
		(context.scaledWindowHeight - 27f).coerceAtLeast(6f)

	fun handleClick(mouseX: Int, mouseY: Int): Boolean {
		val client = MinecraftClient.getInstance()
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		if (!shouldDraw(config)) {
			return false
		}

		val placement = placement(client)
		val scaledWidth = width * placement.scale
		val scaledHeight = height * placement.scale
		if (mouseX < placement.x || mouseX > placement.x + scaledWidth || mouseY < placement.y || mouseY > placement.y + scaledHeight) {
			return false
		}

		IrcChatTabManager.toggleTab()
		return true
	}

	override fun draw(context: DrawContext, example: Boolean): Pair<Float, Float> {
		val client = MinecraftClient.getInstance()
		val textRenderer = client.textRenderer
		val activeTab = if (example) IrcChatTabManager.ChatTab.IRC else IrcChatTabManager.activeTab()
		drawToggleButton(context, textRenderer, activeTab == IrcChatTabManager.ChatTab.IRC)
		return BUTTON_WIDTH.toFloat() to BUTTON_HEIGHT.toFloat()
	}

	private fun drawToggleButton(
		context: DrawContext,
		textRenderer: net.minecraft.client.font.TextRenderer,
		active: Boolean,
	) {
		val fill = if (active) ACTIVE_FILL else INACTIVE_FILL
		val border = if (active) ACTIVE_BORDER else INACTIVE_BORDER
		val textColor = if (active) ACTIVE_TEXT else INACTIVE_TEXT
		val glow = if (active) ACTIVE_GLOW else INACTIVE_GLOW
		val label = "IRC"
		val labelX = (BUTTON_WIDTH - textRenderer.getWidth(label)) / 2
		val labelY = (BUTTON_HEIGHT - textRenderer.fontHeight) / 2

		context.fill(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT, fill)
		context.fill(0, 0, BUTTON_WIDTH, 1, border)
		context.fill(0, BUTTON_HEIGHT - 1, BUTTON_WIDTH, BUTTON_HEIGHT, border)
		context.fill(0, 0, 1, BUTTON_HEIGHT, border)
		context.fill(BUTTON_WIDTH - 1, 0, BUTTON_WIDTH, BUTTON_HEIGHT, border)
		context.fill(2, 2, BUTTON_WIDTH - 2, BUTTON_HEIGHT - 2, glow)
		context.drawTextWithShadow(textRenderer, label, labelX, labelY, textColor)
	}

	private fun placement(client: MinecraftClient): HudElementPlacement {
		val window = client.window ?: return HudElementPlacement(6f, 6f, 1f)
		val config = XclipsenIrcBridgeClient.instance?.config()
		val current = config?.hudElements?.get(id)
		if (current != null && current.x.isFinite() && current.y.isFinite() && current.x >= 0f && current.y >= 0f) {
			current.scale = current.scale.takeIf { it.isFinite() }?.coerceIn(0.5f, 4f) ?: 1f
			return current
		}

		val fallback = HudElementPlacement(6f, (window.scaledHeight - 27f).coerceAtLeast(6f), 1f)
		config?.hudElements?.set(id, fallback)
		return fallback
	}

	private const val BUTTON_WIDTH = 24
	private const val BUTTON_HEIGHT = 20
	private const val ACTIVE_FILL = 0xB21B472F.toInt()
	private const val ACTIVE_BORDER = 0xFF4FCB7A.toInt()
	private const val ACTIVE_GLOW = 0x2E4FCB7A
	private const val ACTIVE_TEXT = 0xFFFFFFFF.toInt()
	private const val INACTIVE_FILL = 0x960A0A0A.toInt()
	private const val INACTIVE_BORDER = 0x70FFFFFF
	private const val INACTIVE_GLOW = 0x18202020
	private const val INACTIVE_TEXT = 0xFFB9B9B9.toInt()
}
