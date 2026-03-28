package de.xclipsen.ircbridge

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.text.Text
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import kotlin.math.max

class XclipsenIrcBridgeClient : ClientModInitializer {
	private val configManager = BridgeConfigManager(LOGGER)
	private val backendBridge = ClientBackendBridgeService(LOGGER)

	private var config = BridgeConfig()
	private var incomingBridgeMessagesEnabled = true
	private var ircLinkWarningShown = false
	private var pendingConfigScreenOpen = false
	private var ircChatModeExpiresAt = 0L

	override fun onInitializeClient() {
		instance = this
		config = configManager.load()
		backendBridge.start(config)
		backendBridge.setIncomingMessagesEnabled(incomingBridgeMessagesEnabled)

		ClientLifecycleEvents.CLIENT_STOPPING.register {
			backendBridge.stop()
		}
		ClientTickEvents.END_CLIENT_TICK.register(::handleEndTick)
		ClientSendMessageEvents.ALLOW_CHAT.register(::handleOutgoingChatMessage)
		ClientSendMessageEvents.ALLOW_COMMAND.register(::handleOutgoingCommand)

		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				ClientCommandManager.literal("irc")
					.then(ClientCommandManager.literal("config").executes(::openConfigScreen))
					.then(ClientCommandManager.literal("on").executes(::enableIncomingBridgeMessages))
					.then(ClientCommandManager.literal("off").executes(::disableIncomingBridgeMessages))
					.then(ClientCommandManager.literal("status").executes(::showStatus))
					.then(ClientCommandManager.literal("reload").executes(::reloadConfig))
					.then(
						ClientCommandManager.argument("message", StringArgumentType.greedyString())
							.executes(::sendIrcMessage),
					),
			)
		}

		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				ClientCommandManager.literal("i")
					.then(
						ClientCommandManager.argument("message", StringArgumentType.greedyString())
							.executes(::sendIrcMessage),
					),
			)
		}

		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				ClientCommandManager.literal("link")
					.executes(::showLinkedStatus)
					.then(
						ClientCommandManager.argument("code", StringArgumentType.word())
							.executes(::completeLink),
					),
			)
		}
	}

	fun config(): BridgeConfig = config

	fun backendStatus(): BackendStatusSnapshot = backendBridge.status()

	fun testBackendConnection(): BackendStatusSnapshot = backendBridge.testConnection()

	fun testBackendConnection(config: BridgeConfig): BackendStatusSnapshot =
		backendBridge.testConnection(configManager.normalize(config.copy()))

	fun configPath(): Path = configManager.path()

	fun setPreviewHoverPaused(paused: Boolean) {
		backendBridge.setPreviewHoverPaused(paused)
	}

	@Throws(IOException::class)
	fun saveAndApplyConfig(config: BridgeConfig) {
		configManager.save(config)
		this.config = config
		backendBridge.start(config)
	}

	private fun openConfigScreen(context: CommandContext<FabricClientCommandSource>): Int {
		pendingConfigScreenOpen = true
		return 1
	}

	private fun handleEndTick(client: MinecraftClient) {
		if (client.currentScreen !is ChatScreen) {
			ImagePreviewManager.setHoverPreviewActive(false)
			backendBridge.setPreviewHoverPaused(false)
		}

		if (ircChatModeExpiresAt > 0L && System.currentTimeMillis() > ircChatModeExpiresAt) {
			ircChatModeExpiresAt = 0L
			sendClientFeedback("IRC chat mode expired.")
		}

		if (!pendingConfigScreenOpen) {
			return
		}

		pendingConfigScreenOpen = false
		openConfigScreen(client)
	}

	private fun openConfigScreen(client: MinecraftClient?) {
		if (client == null) {
			return
		}

		client.execute {
			client.setScreen(XclipsenConfigScreen(client.currentScreen, this))
		}
	}

	private fun reloadConfig(context: CommandContext<FabricClientCommandSource>): Int {
		config = configManager.load()
		backendBridge.start(config)
		backendBridge.setIncomingMessagesEnabled(incomingBridgeMessagesEnabled)
		context.source.sendFeedback(Text.literal("IRC bridge config reloaded: ${configManager.path()}"))
		return 1
	}

	private fun enableIncomingBridgeMessages(context: CommandContext<FabricClientCommandSource>): Int {
		incomingBridgeMessagesEnabled = true
		backendBridge.setIncomingMessagesEnabled(true)
		context.source.sendFeedback(Text.literal("IRC incoming messages enabled."))
		return 1
	}

	private fun disableIncomingBridgeMessages(context: CommandContext<FabricClientCommandSource>): Int {
		incomingBridgeMessagesEnabled = false
		backendBridge.setIncomingMessagesEnabled(false)
		context.source.sendFeedback(Text.literal("IRC incoming messages disabled."))
		return 1
	}

	private fun showStatus(context: CommandContext<FabricClientCommandSource>): Int {
		val status = backendBridge.testConnection()
		context.source.sendFeedback(Text.literal(formatStatus(status)))
		return 1
	}

	private fun sendIrcMessage(context: CommandContext<FabricClientCommandSource>): Int {
		val message = StringArgumentType.getString(context, "message").trim()
		if (message.isBlank()) {
			context.source.sendError(Text.literal("Message must not be empty."))
			return 0
		}

		return if (sendIrcMessageInternal(message, ::sendCommandError)) 1 else 0
	}

	private fun showLinkedStatus(context: CommandContext<FabricClientCommandSource>): Int {
		val playerName = MinecraftClient.getInstance().session.username
		val status = backendBridge.getLinkStatus(playerName)

		if (!status.linked) {
			context.source.sendFeedback(
				Text.literal(
					if (status.error.isBlank()) {
						"Not linked. Use /link start on Discord, then /link CODE here."
					} else {
						status.error
					},
				),
			)
			return 1
		}

		ircLinkWarningShown = false
		cacheLinkedDisplayName(status)
		context.source.sendFeedback(Text.literal("Linked usernames: ${status.minecraftUsernames.joinToString(", ")}"))
		return 1
	}

	private fun completeLink(context: CommandContext<FabricClientCommandSource>): Int {
		val playerName = MinecraftClient.getInstance().session.username
		val code = StringArgumentType.getString(context, "code")
		val status = backendBridge.completeLink(playerName, code)

		if (status.error.isNotBlank() || !status.linked) {
			context.source.sendError(Text.literal(if (status.error.isBlank()) "Link failed." else status.error))
			return 0
		}

		ircLinkWarningShown = false
		cacheLinkedDisplayName(status)
		context.source.sendFeedback(Text.literal("Linked successfully: ${status.minecraftUsernames.joinToString(", ")}"))
		return 1
	}

	private fun enableIrcChatMode(context: CommandContext<FabricClientCommandSource>): Int {
		ircChatModeExpiresAt = System.currentTimeMillis() + IRC_CHAT_MODE_WINDOW_MS
		context.source.sendFeedback(
			Text.literal("IRC chat mode enabled for 2.5 minutes. Normal chat messages will go to IRC."),
		)
		return 1
	}

	private fun disableIrcChatMode(context: CommandContext<FabricClientCommandSource>): Int {
		ircChatModeExpiresAt = 0L
		context.source.sendFeedback(Text.literal("IRC chat mode disabled."))
		return 1
	}

	private fun handleOutgoingChatMessage(message: String): Boolean {
		if (!isIrcChatModeActive()) {
			return true
		}

		val trimmedMessage = message.trim()
		if (trimmedMessage.isBlank()) {
			return false
		}

		if (!sendIrcMessageInternal(trimmedMessage, ::sendClientError)) {
			return false
		}

		ircChatModeExpiresAt = System.currentTimeMillis() + IRC_CHAT_MODE_WINDOW_MS
		return false
	}

	private fun isIrcChatModeActive(): Boolean =
		ircChatModeExpiresAt > 0L && System.currentTimeMillis() <= ircChatModeExpiresAt

	private fun handleOutgoingCommand(command: String): Boolean {
		val normalized = command.trim().lowercase()
		if (normalized == "chat i") {
			enableIrcChatMode()
			return false
		}

		if (normalized == "chat off") {
			disableIrcChatMode()
			return false
		}

		if (!isIrcChatModeActive()) {
			return true
		}

		if (shouldDisableIrcChatMode(normalized)) {
			disableIrcChatMode()
		}

		return true
	}

	private fun shouldDisableIrcChatMode(command: String): Boolean {
		val parts = command.split(Regex("\\s+")).filter { it.isNotBlank() }
		if (parts.size < 2 || parts[0] != "chat") {
			return false
		}

		return when (parts[1]) {
			"a", "all", "p", "party", "g", "guild", "o", "officer", "sc", "skyblock-coop" -> true
			else -> false
		}
	}

	private fun sendIrcMessageInternal(message: String, errorHandler: (String) -> Unit): Boolean {
		val playerName = MinecraftClient.getInstance().session.username
		val linkStatus = backendBridge.getLinkStatus(playerName)
		if (!linkStatus.linked) {
			if (!ircLinkWarningShown) {
				errorHandler(
					if (linkStatus.error.isBlank()) {
						"You are not linked yet. Use /link start on Discord and /link CODE in Minecraft."
					} else {
						linkStatus.error
					},
				)
				ircLinkWarningShown = true
			}
			return false
		}

		ircLinkWarningShown = false
		cacheLinkedDisplayName(linkStatus)
		backendBridge.sendIrcMessage(playerName, message)
		return true
	}

	private fun sendCommandError(message: String) {
		MinecraftClient.getInstance().player?.sendMessage(Text.literal(message), false)
	}

	private fun enableIrcChatMode() {
		ircChatModeExpiresAt = System.currentTimeMillis() + IRC_CHAT_MODE_WINDOW_MS
		sendClientFeedback("IRC chat mode enabled for 2.5 minutes. Normal chat messages will go to IRC.")
	}

	private fun sendClientFeedback(message: String) {
		MinecraftClient.getInstance().player?.sendMessage(Text.literal(message), false)
	}

	private fun disableIrcChatMode() {
		ircChatModeExpiresAt = 0L
		sendClientFeedback("IRC chat mode disabled.")
	}

	private fun sendClientError(message: String) {
		MinecraftClient.getInstance().player?.sendMessage(Text.literal(message), false)
	}

	private fun cacheLinkedDisplayName(status: BackendLinkStatusResponse?) {
		val displayName = status?.discordDisplayName.orEmpty()
		if (displayName.isBlank() || displayName == config.linkedDiscordDisplayName) {
			return
		}

		config.linkedDiscordDisplayName = displayName
		try {
			configManager.save(config)
		} catch (exception: IOException) {
			LOGGER.warn("Failed to persist linked Discord display name.", exception)
		}
	}

	companion object {
		private val LOGGER: Logger = LoggerFactory.getLogger("xclipsen_irc_bridge")
		private const val IRC_CHAT_MODE_WINDOW_MS = 150 * 1000L

		@JvmStatic
		var instance: XclipsenIrcBridgeClient? = null
			private set

		@JvmStatic
		fun formatStatus(status: BackendStatusSnapshot): String {
			return buildString {
				append("IRC backend status: ").append(status.state)
				if (status.lastHttpStatus >= 0) {
					append(" | HTTP ").append(status.lastHttpStatus)
				}
				if (status.lastSuccessAt > 0L) {
					append(" | last success ").append(secondsAgo(status.lastSuccessAt)).append("s ago")
				}
				if (status.lastMessageAt > 0L) {
					append(" | last msg ").append(secondsAgo(status.lastMessageAt)).append("s ago")
				}
				if (status.lastError.isNotBlank()) {
					append(" | ").append(status.lastError)
				}
			}
		}

		private fun secondsAgo(timestamp: Long): Long = max(0L, (System.currentTimeMillis() - timestamp) / 1000L)
	}
}
