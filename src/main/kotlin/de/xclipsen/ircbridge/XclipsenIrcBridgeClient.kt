package de.xclipsen.ircbridge

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
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
	private var ircLinkWarningShown = false
	private var pendingConfigScreenOpen = false

	override fun onInitializeClient() {
		instance = this
		config = configManager.load()
		backendBridge.start(config)

		ClientLifecycleEvents.CLIENT_STOPPING.register {
			backendBridge.stop()
		}
		ClientTickEvents.END_CLIENT_TICK.register(::handleEndTick)

		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				ClientCommandManager.literal("irc")
					.then(ClientCommandManager.literal("config").executes(::openConfigScreen))
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
		context.source.sendFeedback(Text.literal("IRC bridge config reloaded: ${configManager.path()}"))
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

		val playerName = MinecraftClient.getInstance().session.username
		val linkStatus = backendBridge.getLinkStatus(playerName)
		if (!linkStatus.linked) {
			if (!ircLinkWarningShown) {
				context.source.sendError(
					Text.literal(
						if (linkStatus.error.isBlank()) {
							"You are not linked yet. Use /link start on Discord and /link CODE in Minecraft."
						} else {
							linkStatus.error
						},
					),
				)
				ircLinkWarningShown = true
			}
			return 0
		}

		ircLinkWarningShown = false
		cacheLinkedDisplayName(linkStatus)
		backendBridge.sendIrcMessage(playerName, message)
		return 1
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
