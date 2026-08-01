package de.xclipsen.ircbridge

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component

object IrcChatTabManager {
	enum class ChatTab {
		MAIN,
		IRC,
	}

	@Volatile
	private var activeTab: ChatTab = ChatTab.MAIN

	@Volatile
	private var ircChatHud: ChatComponent? = null

	fun activeTab(): ChatTab = activeTab

	fun isIrcTabActive(): Boolean = activeTab == ChatTab.IRC

	fun isSecondaryChatHud(chatHud: ChatComponent): Boolean = ircChatHud === chatHud

	fun shouldProxy(mainChatHud: ChatComponent, client: Minecraft?): Boolean {
		return client?.gui?.chat === mainChatHud && isIrcTabActive()
	}

	fun ircChatHud(client: Minecraft): ChatComponent {
		val current = ircChatHud
		if (current != null) {
			return current
		}

		return synchronized(this) {
			ircChatHud ?: ChatComponent(client).also { ircChatHud = it }
		}
	}

	fun activeChatHud(client: Minecraft): ChatComponent {
		return if (isIrcTabActive()) ircChatHud(client) else client.gui.chat
	}

	fun addIrcMessage(message: Component) {
		val client = Minecraft.getInstance()
		client.execute {
			ircChatHud(client).addClientSystemMessage(message)
		}
	}

	fun clearIrcChat(clearHistory: Boolean) {
		ircChatHud?.clearMessages(clearHistory)
	}

	fun resetIrcChat() {
		ircChatHud?.rescaleChat()
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
		config.ircBridgeEnabled && Minecraft.getInstance().screen is ChatScreen

	override fun defaultX(context: GuiGraphicsExtractor): Float = 6f

	override fun defaultY(context: GuiGraphicsExtractor): Float =
		(context.guiHeight() - 27f).coerceAtLeast(6f)

	fun handleClick(mouseX: Int, mouseY: Int): Boolean {
		val client = Minecraft.getInstance()
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

	override fun draw(context: GuiGraphicsExtractor, example: Boolean): Tuple<Float, Float> {
		val client = Minecraft.getInstance()
		val textRenderer = client.font
		val activeTab = if (example) IrcChatTabManager.ChatTab.IRC else IrcChatTabManager.activeTab()
		drawToggleButton(context, textRenderer, activeTab == IrcChatTabManager.ChatTab.IRC)
		return BUTTON_WIDTH.toFloat() to BUTTON_HEIGHT.toFloat()
	}

	private fun drawToggleButton(
		context: GuiGraphicsExtractor,
		textRenderer: net.minecraft.client.gui.Font,
		active: Boolean,
	) {
		val fill = if (active) ACTIVE_FILL else INACTIVE_FILL
		val border = if (active) ACTIVE_BORDER else INACTIVE_BORDER
		val textColor = if (active) ACTIVE_TEXT else INACTIVE_TEXT
		val glow = if (active) ACTIVE_GLOW else INACTIVE_GLOW
		val label = "IRC"
		val labelX = (BUTTON_WIDTH - textRenderer.width(label)) / 2
		val labelY = (BUTTON_HEIGHT - textRenderer.lineHeight) / 2

		context.fill(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT, fill)
		context.fill(0, 0, BUTTON_WIDTH, 1, border)
		context.fill(0, BUTTON_HEIGHT - 1, BUTTON_WIDTH, BUTTON_HEIGHT, border)
		context.fill(0, 0, 1, BUTTON_HEIGHT, border)
		context.fill(BUTTON_WIDTH - 1, 0, BUTTON_WIDTH, BUTTON_HEIGHT, border)
		context.fill(2, 2, BUTTON_WIDTH - 2, BUTTON_HEIGHT - 2, glow)
		context.text(textRenderer, label, labelX, labelY, textColor, true)
	}

	private fun placement(client: Minecraft): HudElementPlacement {
		val window = client.window
		val config = XclipsenIrcBridgeClient.instance?.config()
		val current = config?.hudElements?.get(id)
		if (current != null && current.x.isFinite() && current.y.isFinite() && current.x >= 0f && current.y >= 0f) {
			current.scale = current.scale.takeIf { it.isFinite() }?.coerceIn(0.5f, 4f) ?: 1f
			return current
		}

		val fallback = HudElementPlacement(6f, (window.guiScaledHeight - 27f).coerceAtLeast(6f), 1f)
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
