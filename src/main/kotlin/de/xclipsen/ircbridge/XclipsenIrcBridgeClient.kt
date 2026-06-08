package de.xclipsen.ircbridge

import com.autocroesus.AutoCroesus
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import de.xclipsen.ircbridge.minigame.MinigameController
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayDeque
import kotlin.math.max

class XclipsenIrcBridgeClient : ClientModInitializer {
	private val configManager = BridgeConfigManager(LOGGER)
	private val backendBridge = ClientBackendBridgeService(LOGGER)
	private val minigameController = MinigameController(this)
	private val ircSendExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "xclipsen-irc-send").apply { isDaemon = true }
	}

	private var config = BridgeConfig()
	private var incomingBridgeMessagesEnabled = true
	@Volatile
	private var ircLinkWarningShown = false
	private var pendingConfigScreenOpen = false
	private var pendingHudEditorOpen = false
	private var ircChatModeExpiresAt = 0L
	private var lastHideonleafLostFightAlertAt = 0L
	private val recentCoopRelays: ArrayDeque<CoopRelayDedupEntry> = ArrayDeque()

	override fun onInitializeClient() {
		instance = this
		config = configManager.load()
		minigameController.initialize()
		HideonleafShardTracker.init()
		applyBackendBridgeConfig()
		ModUpdateChecker.onStartup()
		MobModelFeature.onStartup()
		AutoCroesus.initialize()
		ExperimentationTableFeature.init()
		DeploybleFeature.init()

		// Register HUD click handler via Fabric ScreenEvents
		ScreenMouseClickHandler.register()

		ClientLifecycleEvents.CLIENT_STOPPING.register {
			HideonleafShardTracker.shutdown()
			MortDoorBarrierFeature.onWorldChange()
			backendBridge.stop()
			minigameController.shutdown()
			ircSendExecutor.shutdownNow()
		}
		ClientTickEvents.END_CLIENT_TICK.register(::handleEndTick)
		ClientPlayConnectionEvents.JOIN.register { handler, _, client ->
			SilentDisconnectFeature.onJoin(handler, client)
			minigameController.onJoin(handler.serverInfo?.address ?: client.currentServerEntry?.address)
		}
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
			ServerTickTracker.reset()
			CorpseEspFeature.onDisconnect()
			M5Feature.onWorldChange()
			MobModelFeature.onDisconnect()
			MortDoorBarrierFeature.onWorldChange()
			PurpleTerracottaHighlightFeature.onWorldChange()
			WormholeFinderFeature.onWorldChange()
			PickaxeAbilityCooldownFeature.onWorldChange()
			FireFreezeFeature.onWorldChange()
			MineshaftAutoWarpFeature.onDisconnect()
			DungeonAutoKickFeature.onDisconnect()
			DeploybleFeature.onWorldChange()
			SilentDisconnectFeature.onPlayDisconnect()
			minigameController.onDisconnect()
		}
		ClientSendMessageEvents.ALLOW_CHAT.register(::handleOutgoingChatMessage)
		ClientSendMessageEvents.ALLOW_COMMAND.register(::handleOutgoingCommand)
		ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ -> !shouldSuppressIncomingMessage(message) }
		ClientReceiveMessageEvents.ALLOW_CHAT.register { message, _, _, _, _ -> !shouldSuppressIncomingMessage(message) }
		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			ChimeraBookDropEffectsFeature.onIncomingGameMessage(message, overlay)
			BlazeSlayerFeature.onIncomingGameMessage(message, overlay)
			handleIncomingMessage(message)
		}
		ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
			ChimeraBookDropEffectsFeature.onIncomingChatMessage(message)
			handleIncomingMessage(message)
		}
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.of("xclipsen", "hud")) { context, _ ->
			XclipsenHudManager.render(context)
		}
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.of("xclipsen", "custom_crosshair")) { context, _ ->
			CustomCrosshairFeature.render(context)
		}
		WorldRenderEvents.AFTER_ENTITIES.register { context -> ShulkerTracerRenderer.render(context) }
		WorldRenderEvents.AFTER_ENTITIES.register { context -> MortDoorBarrierFeature.onRender(context) }
		WorldRenderEvents.AFTER_ENTITIES.register { context -> PurpleTerracottaHighlightFeature.render(context) }
		WorldRenderEvents.AFTER_ENTITIES.register { context -> WormholeFinderFeature.render(context) }
		WorldRenderEvents.AFTER_ENTITIES.register { context -> PestEspFeature.render(context) }
		WorldRenderEvents.AFTER_ENTITIES.register { context -> CorpseEspFeature.render(context) }
		WorldRenderEvents.AFTER_ENTITIES.register { context -> M5Feature.render(context) }
		WorldRenderEvents.AFTER_ENTITIES.register { context -> FireFreezeFeature.render(context) }
		WorldRenderEvents.AFTER_ENTITIES.register { context -> BlazeSlayerFeature.render(context) }

		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				ClientCommandManager.literal("game")
					.executes {
						minigameController.openFromCommand()
						1
					}
					.then(
						ClientCommandManager.literal("accept")
							.then(
								ClientCommandManager.argument("player", StringArgumentType.word()).executes {
									minigameController.acceptInvite("", StringArgumentType.getString(it, "player"))
									1
								},
							),
					)
					.then(
						ClientCommandManager.literal("deny")
							.then(
								ClientCommandManager.argument("player", StringArgumentType.word()).executes {
									minigameController.denyInvite("", StringArgumentType.getString(it, "player"))
									1
								},
							),
					)
					.then(
						ClientCommandManager.literal("leave").executes {
							minigameController.leaveActiveMatch()
							1
						},
					),
			)
		}

		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				ClientCommandManager.literal("irc")
					.then(ClientCommandManager.literal("config").executes(::openConfigScreen))
					.then(ClientCommandManager.literal("hud").executes(::openHudEditor))
					.then(ClientCommandManager.literal("on").executes(::enableIncomingBridgeMessages))
					.then(ClientCommandManager.literal("off").executes(::disableIncomingBridgeMessages))
					.then(ClientCommandManager.literal("status").executes(::showStatus))
					.then(ClientCommandManager.literal("reload").executes(::reloadConfig))
					.then(
						ClientCommandManager.literal("shulkerglow")
							.executes(::showShulkerGlowStatus)
							.then(ClientCommandManager.literal("on").executes { setShulkerGlow(it, true) })
							.then(ClientCommandManager.literal("off").executes { setShulkerGlow(it, false) })
							.then(ClientCommandManager.literal("toggle").executes(::toggleShulkerGlow)),
					)
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

		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				ClientCommandManager.literal("xclipsen")
					.executes(::openConfigScreen)
					.then(ClientCommandManager.literal("config").executes(::openConfigScreen))
					.then(ClientCommandManager.literal("settings").executes(::openConfigScreen))
					.then(ClientCommandManager.literal("hud").executes(::openHudEditor))
					.then(
						ClientCommandManager.literal("dev")
							.executes(::toggleDevMode)
							.then(ClientCommandManager.literal("on").executes { setDevMode(it, true) })
							.then(ClientCommandManager.literal("off").executes { setDevMode(it, false) })
							.then(ClientCommandManager.literal("status").executes(::showDevModeStatus))
							.then(ClientCommandManager.literal("chim").executes(::runDevTest))
							.then(ClientCommandManager.literal("test").executes(::runDevTest)),
					)
			)

			dispatcher.register(
				ClientCommandManager.literal("shulkerglow")
					.executes(::showShulkerGlowStatus)
					.then(ClientCommandManager.literal("on").executes { setShulkerGlow(it, true) })
					.then(ClientCommandManager.literal("off").executes { setShulkerGlow(it, false) })
					.then(ClientCommandManager.literal("toggle").executes(::toggleShulkerGlow)),
			)

			dispatcher.register(
				ClientCommandManager.literal("shardtracker")
					.executes(::showShardTrackerStatus)
					.then(ClientCommandManager.literal("reset").executes(::resetShardTrackerSession))
					.then(ClientCommandManager.literal("resetall").executes(::resetShardTrackerTotal))
					.then(ClientCommandManager.literal("toggle").executes(::toggleShardTrackerView))
					.then(ClientCommandManager.literal("on").executes { setShardTracker(it, true) })
					.then(ClientCommandManager.literal("off").executes { setShardTracker(it, false) }),
			)

			dispatcher.register(
				ClientCommandManager.literal("st")
					.executes(::showShardTrackerStatus)
					.then(ClientCommandManager.literal("reset").executes(::resetShardTrackerSession))
					.then(ClientCommandManager.literal("resetall").executes(::resetShardTrackerTotal))
					.then(ClientCommandManager.literal("toggle").executes(::toggleShardTrackerView)),
			)
		}
	}

	fun config(): BridgeConfig = config

	fun backendBridge(): ClientBackendBridgeService = backendBridge

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
		applyBackendBridgeConfig()
		minigameController.onConfigChanged()
		ModUpdateChecker.onConfigChanged()
		MobModelFeature.onConfigChanged()
	}

	@Throws(IOException::class)
	fun saveCurrentConfig() {
		configManager.save(config)
	}

	private fun openConfigScreen(context: CommandContext<FabricClientCommandSource>): Int {
		pendingConfigScreenOpen = true
		return 1
	}

	private fun openHudEditor(context: CommandContext<FabricClientCommandSource>): Int {
		pendingHudEditorOpen = true
		return 1
	}

	private fun runDevTest(context: CommandContext<FabricClientCommandSource>): Int {
		return if (ChimeraBookDropEffectsFeature.runTest()) {
			context.source.sendFeedback(Text.literal("Triggered Chimera book drop effect test."))
			1
		} else {
			context.source.sendError(Text.literal("Chimera book drop effects module is disabled."))
			0
		}
	}

	private fun toggleDevMode(context: CommandContext<FabricClientCommandSource>): Int =
		setDevMode(context, !config.devModeEnabled)

	private fun setDevMode(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
		val previous = config.devModeEnabled
		config.devModeEnabled = enabled

		return try {
			configManager.save(config)
			backendBridge.configure(config)
			if (previous != enabled) {
				minigameController.onConfigChanged()
				MobModelFeature.onConfigChanged()
			}
			showDevModeStatus(context)
		} catch (_: IOException) {
			config.devModeEnabled = previous
			context.source.sendError(Text.literal("Dev-Modus konnte nicht gespeichert werden."))
			0
		}
	}

	private fun showDevModeStatus(context: CommandContext<FabricClientCommandSource>): Int {
		val mode = if (config.devModeEnabled) "Lokal" else "Produktion"
		context.source.sendFeedback(Text.literal("Xclipsen Backend: $mode (${activeModBackendBaseUrl(config)})"))
		return 1
	}

	private fun handleEndTick(client: MinecraftClient) {
		ChimeraBookDropEffectsFeature.onTick()
		LocationTracker.onTick(client)
		HideonleafShardTracker.onTick()
		AuctionHouseUnderbidFeature.onTick(client)
		MortDoorBarrierFeature.onTick(client)
		PurpleTerracottaHighlightFeature.onTick(client)
		WormholeFinderFeature.onTick(client)
		AutoSprintFeature.onTick(client)
		M5Feature.onTick(client)
		PickaxeAbilityCooldownFeature.onTick(client)
		BlazeSlayerFeature.onTick(client)
		FireFreezeFeature.onTick(client)
		MineshaftAutoWarpFeature.onTick(client)
		DungeonAutoKickFeature.onTick(client)
		CorpseEspFeature.onTick(client)
		MobModelFeature.onTick(client)
		ModUpdateChecker.onTick(client)
		ExperimentationTableFeature.onTick(client)
		DeploybleFeature.onTick(client)

		if (client.currentScreen !is ChatScreen) {
			ImagePreviewManager.setHoverPreviewActive(false)
			backendBridge.setPreviewHoverPaused(false)
		}

		if (ircChatModeExpiresAt > 0L && System.currentTimeMillis() > ircChatModeExpiresAt) {
			ircChatModeExpiresAt = 0L
			sendClientFeedback("IRC chat mode expired.")
		}

		if (pendingConfigScreenOpen) {
			pendingConfigScreenOpen = false
			openConfigScreen(client)
		}

		if (pendingHudEditorOpen) {
			pendingHudEditorOpen = false
			openHudEditor(client, client.currentScreen)
		}
	}

	private fun openConfigScreen(client: MinecraftClient?) {
		if (client == null) {
			return
		}

		client.execute {
			client.setScreen(XclipsenConfigScreen(client.currentScreen, this))
		}
	}

	fun openHudEditorScreen(parent: Screen?) {
		openHudEditor(MinecraftClient.getInstance(), parent)
	}

	private fun openHudEditor(client: MinecraftClient?, parent: Screen?) {
		if (client == null) {
			return
		}

		client.execute {
			client.setScreen(XclipsenHudEditorScreen(parent, this))
		}
	}

	private fun reloadConfig(context: CommandContext<FabricClientCommandSource>): Int {
		config = configManager.load()
		applyBackendBridgeConfig()
		context.source.sendFeedback(Text.literal("IRC bridge config reloaded: ${configManager.path()}"))
		return 1
	}

	private fun enableIncomingBridgeMessages(context: CommandContext<FabricClientCommandSource>): Int {
		incomingBridgeMessagesEnabled = true
		applyIncomingBridgeState()
		context.source.sendFeedback(Text.literal("IRC incoming messages enabled."))
		return 1
	}

	private fun disableIncomingBridgeMessages(context: CommandContext<FabricClientCommandSource>): Int {
		incomingBridgeMessagesEnabled = false
		applyIncomingBridgeState()
		context.source.sendFeedback(Text.literal("IRC incoming messages disabled."))
		return 1
	}

	private fun applyIncomingBridgeState() {
		backendBridge.setIncomingMessagesEnabled(config.ircBridgeEnabled && incomingBridgeMessagesEnabled)
	}

	private fun applyBackendBridgeConfig() {
		backendBridge.configure(config)
		if (!config.ircBridgeEnabled) {
			IrcChatTabManager.setActiveTab(IrcChatTabManager.ChatTab.MAIN)
			backendBridge.stop()
			return
		}

		backendBridge.start(config)
		applyIncomingBridgeState()
	}

	private fun showStatus(context: CommandContext<FabricClientCommandSource>): Int {
		val status = backendBridge.testConnection()
		context.source.sendFeedback(Text.literal(formatStatus(status)))
		return 1
	}

	private fun showShulkerGlowStatus(context: CommandContext<FabricClientCommandSource>): Int {
		context.source.sendFeedback(Text.literal("Shulker glow is ${if (config.shulkerGlowEnabled) "enabled" else "disabled"}."))
		return 1
	}

	private fun toggleShulkerGlow(context: CommandContext<FabricClientCommandSource>): Int =
		setShulkerGlow(context, !config.shulkerGlowEnabled)

	private fun setShulkerGlow(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
		config.shulkerGlowEnabled = enabled
		return try {
			configManager.save(config)
			context.source.sendFeedback(Text.literal("Shulker glow ${if (enabled) "enabled" else "disabled"}."))
			1
		} catch (_: IOException) {
			context.source.sendError(Text.literal("Failed to save shulker glow setting."))
			0
		}
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
		backendBridge.discardBacklogOnNextPoll()
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
		SilentDisconnectFeature.onOutgoingCommand(command)

		val trimmedCommand = command.trim()
		val normalized = trimmedCommand.lowercase()
		if (normalized == "chat i") {
			enableIrcChatMode()
			return false
		}

		if (normalized == "chat off") {
			disableIrcChatMode()
			return false
		}

		if (handleIrcCommandFallback(trimmedCommand)) {
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

	private fun handleIrcCommandFallback(command: String): Boolean {
		val parts = command.split(Regex("\\s+"), limit = 2)
		if (parts.isEmpty()) {
			return false
		}

		return when (parts[0].lowercase(Locale.ROOT)) {
			"irc" -> handleIrcFallback(parts.getOrNull(1).orEmpty())
			"i" -> handleIrcMessageFallback(parts.getOrNull(1).orEmpty())
			else -> false
		}
	}

	private fun handleIrcFallback(argument: String): Boolean {
		val value = argument.trim()
		if (value.isBlank()) {
			sendClientError("Usage: /irc <message>")
			return true
		}

		when (value.lowercase(Locale.ROOT)) {
			"config" -> {
				pendingConfigScreenOpen = true
				return true
			}
			"hud" -> {
				pendingHudEditorOpen = true
				return true
			}
			"on" -> {
				incomingBridgeMessagesEnabled = true
				applyIncomingBridgeState()
				sendClientFeedback("IRC incoming messages enabled.")
				return true
			}
			"off" -> {
				incomingBridgeMessagesEnabled = false
				applyIncomingBridgeState()
				sendClientFeedback("IRC incoming messages disabled.")
				return true
			}
		}

		return handleIrcMessageFallback(value)
	}

	private fun handleIrcMessageFallback(message: String): Boolean {
		val value = message.trim()
		if (value.isBlank()) {
			sendClientError("Message must not be empty.")
			return true
		}

		sendIrcMessageInternal(value, ::sendClientError)
		return true
	}

	private fun handleIncomingMessage(message: Text?) {
		M5Feature.onIncomingMessage(message)
		MineshaftAutoWarpFeature.onIncomingMessage(message)
		DungeonAutoKickFeature.onIncomingMessage(message)
		DeploybleFeature.onIncomingMessage(message)
		SlayerFeature.onIncomingMessage(message)
		WormholeFinderFeature.onIncomingMessage(message)
		handleHideonleafLostFightAlert(message)
		HideonleafShardTracker.processChat(message)
		handleIncomingCoopChat(message)
	}

	private fun shouldSuppressIncomingMessage(message: Text?): Boolean {
		return SilentDisconnectFeature.shouldSuppressStatusMessage(message) || ChatFeature.shouldSuppressMessage(message)
	}

	private fun handleHideonleafLostFightAlert(message: Text?) {
		if (!config.hideonleafHelperEnabled) {
			return
		}

		if (!LocationTracker.isOnGalatea) {
			return
		}

		val normalized = normalizeCoopLine(message?.string ?: return)
		if (!normalized.contains(HIDEONLEAF_LOST_FIGHT_MESSAGE)) {
			return
		}

		val now = System.currentTimeMillis()
		if (now - lastHideonleafLostFightAlertAt < HIDEONLEAF_ALERT_DEDUPE_MS) {
			return
		}
		lastHideonleafLostFightAlertAt = now

		val client = MinecraftClient.getInstance()
		client.execute {
			ShulkerTracerRenderer.markCurrentTargetCompleted()
			HideonleafShardTracker.recordKill()
			if (!config.hideonleafLostFightAlertEnabled) {
				return@execute
			}

			XclipsenHudManager.showHideonleafLostFightAlert()
			playHideonleafLostFightSound()
		}
	}

	fun playHideonleafLostFightSound(config: BridgeConfig = this.config) {
		MinecraftClient.getInstance().soundManager.play(
			SoundCatalog.masterSound(
				config.hideonleafLostFightAlertSoundId,
				config.hideonleafLostFightAlertSoundPitch.coerceIn(0.1f, 2.0f),
				config.hideonleafLostFightAlertSoundVolume.coerceIn(0.0f, 2.0f),
			),
		)
	}

	private fun handleIncomingCoopChat(message: Text?) {
		if (!config.ircBridgeEnabled) {
			return
		}

		if (!config.coopChatRelayEnabled) {
			return
		}

		val parsed = parseCoopChatMessage(message) ?: return
		val localPlayer = MinecraftClient.getInstance().session?.username.orEmpty()
		if (localPlayer.isBlank()) {
			return
		}

		val dedupeKey = "${parsed.playerName.lowercase(Locale.ROOT)}|${parsed.message.lowercase(Locale.ROOT)}"
		if (!recordCoopRelayKey(dedupeKey)) {
			return
		}

		backendBridge.relayCoopChat(localPlayer, parsed.playerName, parsed.message)
	}

	private fun parseCoopChatMessage(message: Text?): CoopChatMessage? {
		val raw = message?.string ?: return null
		val normalized = normalizeCoopLine(raw)
		if (!normalized.startsWith("Co-op >")) {
			return null
		}

		val withoutPrefix = normalized.removePrefix("Co-op >").trim()
		val colonIndex = withoutPrefix.indexOf(':')
		if (colonIndex <= 0) {
			return null
		}

		val namePart = stripRankPrefixes(withoutPrefix.substring(0, colonIndex).trim())
		val content = withoutPrefix.substring(colonIndex + 1).trim()
		if (namePart.isEmpty() || content.isEmpty()) {
			return null
		}

		if (!USERNAME_PATTERN.matches(namePart)) {
			return null
		}

		return CoopChatMessage(namePart, content)
	}

	private fun normalizeCoopLine(raw: String): String {
		var clean = stripMinecraftFormatting(raw)
		clean = AMPERSAND_COLOR_PATTERN.replace(clean, "")
		return clean.replace('\r', ' ').replace('\n', ' ').replace("\\s+".toRegex(), " ").trim()
	}

	private fun stripMinecraftFormatting(input: String): String {
		if (!input.contains('§')) {
			return input
		}

		val builder = StringBuilder(input.length)
		var skip = false
		for (character in input) {
			if (skip) {
				skip = false
				continue
			}

			if (character == '§') {
				skip = true
				continue
			}

			builder.append(character)
		}

		return builder.toString()
	}

	private fun stripRankPrefixes(value: String): String {
		var current = value.trim()
		while (current.startsWith("[")) {
			val closing = current.indexOf(']')
			if (closing <= 0) {
				break
			}
			current = current.substring(closing + 1).trimStart()
		}
		return current
	}

	private fun recordCoopRelayKey(key: String): Boolean {
		synchronized(recentCoopRelays) {
			pruneExpiredCoopRelays()
			if (recentCoopRelays.any { it.key == key }) {
				return false
			}

			recentCoopRelays.addLast(CoopRelayDedupEntry(key, System.currentTimeMillis() + COOP_RELAY_TTL_MS))
			while (recentCoopRelays.size > MAX_COOP_RELAY_HISTORY) {
				recentCoopRelays.removeFirst()
			}
			return true
		}
	}

	private fun pruneExpiredCoopRelays() {
		val now = System.currentTimeMillis()
		while (recentCoopRelays.isNotEmpty() && recentCoopRelays.first().expiresAt < now) {
			recentCoopRelays.removeFirst()
		}
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
		if (!config.ircBridgeEnabled) {
			errorHandler("IRC Bridge module is disabled.")
			return false
		}

		val playerName = MinecraftClient.getInstance().session.username
		if (playerName.isBlank()) {
			errorHandler("Minecraft username missing.")
			return false
		}

		ircSendExecutor.execute {
			if (!config.ircBridgeEnabled) {
				return@execute
			}

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
				return@execute
			}

			ircLinkWarningShown = false
			cacheLinkedDisplayName(linkStatus)
			backendBridge.sendIrcMessage(playerName, message)
		}
		return true
	}

	private fun sendCommandError(message: String) {
		val client = MinecraftClient.getInstance()
		client.execute {
			client.player?.sendMessage(Text.literal(message), false)
		}
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
		val client = MinecraftClient.getInstance()
		client.execute {
			client.player?.sendMessage(Text.literal(message), false)
		}
	}

	private fun showShardTrackerStatus(context: CommandContext<FabricClientCommandSource>): Int {
		val data = HideonleafShardTracker.displayData()
		val duration = HideonleafShardTracker.selectedDurationMs()
		val profit = HideonleafShardTracker.totalProfit(data)
		val perHour = HideonleafShardTracker.displayProfitPerHour(data, duration)
		val view = if (HideonleafShardTracker.showingSession) "Session" else "Total"
		val durationAvailable = HideonleafShardTracker.selectedDurationAvailable()

		context.source.sendFeedback(Text.literal(buildString {
			append("§b§lShard Tracker ($view)§r\n")
			for ((name, item) in data.items) {
				append("  §e${item.amount}x §f$name")
				val value = item.amount * item.pricePerUnit
				if (value > 0) append(" §a(${HideonleafShardTracker.formatCoins(value)})")
				append("\n")
			}
			if (data.items.isEmpty()) append("  §7No drops yet.\n")
			append("§aProfit: ${HideonleafShardTracker.formatCoins(profit)} §7| ")
			append(if (durationAvailable) "§aPer Hour: ${HideonleafShardTracker.formatCoins(perHour)}/h §7| " else "§7Per Hour: Legacy unknown §7| ")
			append(if (durationAvailable) "§fTime: ${HideonleafShardTracker.formatDuration(duration)}" else "§7Time: Legacy unknown")
			if (data.kills > 0) append(" §7| §eKills: ${data.kills}")
		}))
		return 1
	}

	private fun resetShardTrackerSession(context: CommandContext<FabricClientCommandSource>): Int {
		HideonleafShardTracker.resetSession()
		context.source.sendFeedback(Text.literal("§aShard tracker session reset."))
		return 1
	}

	private fun resetShardTrackerTotal(context: CommandContext<FabricClientCommandSource>): Int {
		HideonleafShardTracker.resetTotal()
		context.source.sendFeedback(Text.literal("§aShard tracker fully reset (session + total)."))
		return 1
	}

	private fun toggleShardTrackerView(context: CommandContext<FabricClientCommandSource>): Int {
		HideonleafShardTracker.toggleView()
		val view = if (HideonleafShardTracker.showingSession) "Session" else "Total"
		context.source.sendFeedback(Text.literal("§aShard tracker now showing: §e$view"))
		return 1
	}

	private fun setShardTracker(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
		config.shardTrackerEnabled = enabled
		return try {
			configManager.save(config)
			context.source.sendFeedback(Text.literal("§aShard tracker ${if (enabled) "enabled" else "disabled"}."))
			1
		} catch (_: IOException) {
			context.source.sendError(Text.literal("Failed to save shard tracker setting."))
			0
		}
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
		private val LOGGER: Logger = LoggerFactory.getLogger("xclipsen_mod")
		private const val IRC_CHAT_MODE_WINDOW_MS = 150 * 1000L
		private const val COOP_RELAY_TTL_MS = 10_000L
		private const val MAX_COOP_RELAY_HISTORY = 64
		private const val HIDEONLEAF_LOST_FIGHT_MESSAGE = "Hideonleaf lost the fight..."
		private const val HIDEONLEAF_ALERT_DEDUPE_MS = 1_500L
		private val AMPERSAND_COLOR_PATTERN = Regex("(?i)&[0-9A-FK-OR]")
		private val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")

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

	private data class CoopChatMessage(val playerName: String, val message: String)

	private data class CoopRelayDedupEntry(val key: String, val expiresAt: Long)
}
