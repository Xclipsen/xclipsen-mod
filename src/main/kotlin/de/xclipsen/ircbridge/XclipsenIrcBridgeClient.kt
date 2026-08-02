package de.xclipsen.ircbridge

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import de.xclipsen.ircbridge.minigame.MinigameController
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommands as ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
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
	private val credentialManager = ModBackendCredentialManager(LOGGER, configManager.credentialPath())
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
		config = configManager.load(Minecraft.getInstance().user.profileId)
		LocationTracker.init()
		minigameController.initialize()
		HideonleafShardTracker.init()
		applyBackendBridgeConfig()
		HighClassDiceTrackerFeature.init()
		ModUpdateChecker.onStartup()
		MobModelFeature.onStartup()
		ExperimentationTableFeature.init()
		DeploybleFeature.init()

		// Register HUD click handler via Fabric ScreenEvents
		ScreenMouseClickHandler.register()

		ClientLifecycleEvents.CLIENT_STOPPING.register {
			ClientSessionLifecycle.invalidate()
			HideonleafShardTracker.shutdown()
			HighClassDiceTrackerFeature.shutdown()
			MortDoorBarrierFeature.onWorldChange()
			backendBridge.shutdown()
			minigameController.shutdown()
			PartyFinderFeature.shutdown()
			DungeonAutoKickFeature.shutdown()
			MobModelFeature.shutdown()
			ImagePreviewManager.shutdown()
			SlayerFeature.shutdown()
			AuctionHouseUnderbidFeature.shutdown()
			ModUpdateChecker.shutdown()
			ircSendExecutor.shutdownNow()
		}
		ClientTickEvents.END_CLIENT_TICK.register(::handleEndTick)
		ClientPlayConnectionEvents.JOIN.register { handler, _, client ->
			ClientSessionLifecycle.invalidate()
			SilentDisconnectFeature.onJoin(handler, client)
			minigameController.onJoin(client.currentServer?.ip)
		}
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
			ClientSessionLifecycle.invalidate()
			resetPlayerRuntimeState(playDisconnect = true)
			minigameController.onDisconnect()
		}
		ClientSendMessageEvents.ALLOW_CHAT.register(::handleOutgoingChatMessage)
		ClientSendMessageEvents.ALLOW_COMMAND.register(::handleOutgoingCommand)
		ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ -> !shouldSuppressIncomingMessage(message) }
		ClientReceiveMessageEvents.ALLOW_CHAT.register { message, _, _, _, _ -> !shouldSuppressIncomingMessage(message) }
		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			ChimeraBookDropEffectsFeature.onIncomingGameMessage(message, overlay)
			handleIncomingMessage(message)
		}
		ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
			ChimeraBookDropEffectsFeature.onIncomingChatMessage(message)
			handleIncomingMessage(message)
		}
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("xclipsen", "hud")) { context, _ ->
			XclipsenHudManager.render(context)
		}
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath("xclipsen", "custom_crosshair")) { context, _ ->
			CustomCrosshairFeature.render(context)
		}
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> ShulkerTracerRenderer.render(context) }
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> MortDoorBarrierFeature.onRender(context) }
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> PurpleTerracottaHighlightFeature.render(context) }
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> FloorDropEspFeature.render(context) }
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> DuplicoEspFeature.render(context) }
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> HideyhoEspFeature.render(context) }
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> WormholeFinderFeature.render(context) }
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> PestEspFeature.render(context) }
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> CorpseEspFeature.render(context) }
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> M5Feature.render(context) }
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> PickobulusHelperFeature.render(context) }
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> FireFreezeFeature.render(context) }
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register { context -> BlazeSlayerFeature.render(context) }

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
						ClientCommandManager.argument("message", StringArgumentType.greedyString())
							.executes(::sendIrcMessage),
					),
			)
			dispatcher.register(
				ClientCommandManager.literal("i")
					.then(
						ClientCommandManager.argument("message", StringArgumentType.greedyString())
							.executes(::sendIrcMessage),
					),
			)
			dispatcher.register(
				ClientCommandManager.literal("xclipsen")
					.executes(::openConfigScreen)
					.then(ClientCommandManager.literal("config").executes(::openConfigScreen))
					.then(ClientCommandManager.literal("settings").executes(::openConfigScreen))
					.then(ClientCommandManager.literal("hud").executes(::openHudEditor))
					.then(
						ClientCommandManager.literal("game")
							.executes {
								minigameController.openFromCommand()
								1
							}
							.then(
								ClientCommandManager.literal("accept")
									.then(ClientCommandManager.argument("inviteId", StringArgumentType.word()).executes(::acceptGameInvite)),
							)
							.then(
								ClientCommandManager.literal("deny")
									.then(ClientCommandManager.argument("inviteId", StringArgumentType.word()).executes(::denyGameInvite)),
							)
							.then(ClientCommandManager.literal("leave").executes(::leaveActiveGame)),
					)
					.then(
						ClientCommandManager.literal("link")
							.executes(::showLinkedStatus)
							.then(ClientCommandManager.argument("code", StringArgumentType.word()).executes(::completeLink)),
					)
					.then(
						ClientCommandManager.literal("cata")
							.executes(::showOwnCataStats)
							.then(
								ClientCommandManager.argument("player", StringArgumentType.word())
									.executes(::showCataStats),
							),
					)
					.then(
						ClientCommandManager.literal("shulkerglow")
							.executes(::showShulkerGlowStatus)
							.then(ClientCommandManager.literal("on").executes { setShulkerGlow(it, true) })
							.then(ClientCommandManager.literal("off").executes { setShulkerGlow(it, false) })
							.then(ClientCommandManager.literal("toggle").executes(::toggleShulkerGlow)),
					)
					.then(
						ClientCommandManager.literal("tracker")
							.then(
								ClientCommandManager.literal("highclass")
									.executes(::toggleHighClassDiceTracker)
									.then(ClientCommandManager.literal("on").executes { setHighClassDiceTracker(it, true) })
									.then(ClientCommandManager.literal("off").executes { setHighClassDiceTracker(it, false) })
									.then(ClientCommandManager.literal("status").executes(::showHighClassDiceTrackerStatus))
									.then(ClientCommandManager.literal("check").executes(::showHighClassDiceTrackerStatus)),
							)
							.then(
								ClientCommandManager.literal("shard")
									.executes(::showShardTrackerStatus)
									.then(ClientCommandManager.literal("status").executes(::showShardTrackerStatus))
									.then(ClientCommandManager.literal("toggle").executes(::toggleShardTrackerView))
									.then(ClientCommandManager.literal("on").executes { setShardTracker(it, true) })
									.then(ClientCommandManager.literal("off").executes { setShardTracker(it, false) })
									.then(
										ClientCommandManager.literal("reset")
											.then(
												ClientCommandManager.literal("session")
													.executes(::warnShardTrackerSessionReset)
													.then(ClientCommandManager.literal("confirm").executes(::resetShardTrackerSession)),
											)
											.then(
												ClientCommandManager.literal("total")
													.executes(::warnShardTrackerTotalReset)
													.then(ClientCommandManager.literal("confirm").executes(::resetShardTrackerTotal)),
											),
									),
							),
					)
					.then(
						ClientCommandManager.literal("dev")
							.executes(::toggleDevMode)
							.then(ClientCommandManager.literal("on").executes { setDevMode(it, true) })
							.then(ClientCommandManager.literal("off").executes { setDevMode(it, false) })
							.then(ClientCommandManager.literal("status").executes(::showDevModeStatus))
							.then(ClientCommandManager.literal("entities").executes(::logNearbyEntities))
							.then(ClientCommandManager.literal("chim").executes(::runDevTest))
							.then(ClientCommandManager.literal("test").executes(::runDevTest))
							.then(devStatusCommand("location", ::locationStatusLine))
							.then(devStatusCommand("backend") { formatStatus(backendBridge.status()) })
							.then(devStatusCommand("linking") { "credential=${if (modBackendCredential().isNullOrBlank()) "missing" else "present"}" })
							.then(devStatusCommand("minigame", minigameController::statusLine))
							.then(devStatusCommand("highclass") { "enabled=${config.highClassDiceTrackerEnabled}" })
							.then(devStatusCommand("m5", M5Feature::statusLine))
							.then(devStatusCommand("dungeonautokick", DungeonAutoKickFeature::statusLine))
							.then(devStatusCommand("pickaxecd", PickaxeAbilityCooldownFeature::statusLine))
							.then(devStatusCommand("pickobulus", PickobulusHelperFeature::statusLine))
							.then(devStatusCommand("mineshaftautowarp", MineshaftAutoWarpFeature::statusLine))
							.then(devStatusCommand("deployable", DeploybleFeature::statusLine))
							.then(devStatusCommand("mobmodel", MobModelFeature::statusLine))
							.then(devStatusCommand("silentdisconnect") { SilentDisconnectFeature.statusLine(config) })
							.then(devStatusCommand("updater", ModUpdateChecker::statusLine))
							.then(devStatusCommand("slayer") { SlayerFeature.statusLine(config) })
							.then(devStatusCommand("chimera", ChimeraBookDropEffectsFeature::statusLine))
							.then(devStatusCommand("shulkerglow") { if (config.shulkerGlowEnabled) "Enabled" else "Disabled" })
							.then(devStatusCommand("shardtracker", ::shardTrackerDiagnosticLine))
							.then(devStatusCommand("auctionunderbid", AuctionHouseUnderbidFeature::statusLine))
							.then(devStatusCommand("mortdoor", MortDoorBarrierFeature::statusLine))
							.then(devStatusCommand("terracotta", PurpleTerracottaHighlightFeature::statusLine))
							.then(devStatusCommand("wormhole", WormholeFinderFeature::statusLine))
							.then(devStatusCommand("partyfinder", PartyFinderFeature::statusLine))
							.then(devStatusCommand("blaze", BlazeSlayerFeature::statusLine))
							.then(devStatusCommand("firefreeze", FireFreezeFeature::statusLine))
							.then(devStatusCommand("corpse", CorpseEspFeature::statusLine))
							.then(devStatusCommand("floordrop", FloorDropEspFeature::statusLine))
							.then(devStatusCommand("duplico", DuplicoEspFeature::statusLine))
							.then(devStatusCommand("hideyho", HideyhoEspFeature::statusLine))
							.then(devStatusCommand("pestesp", PestEspFeature::statusLine))
							.then(devStatusCommand("experimentation", ExperimentationTableFeature::statusLine))
							.then(devStatusCommand("itemupdatefix", ItemUpdateFixFeature::statusLine)),
					)
			)
		}
	}

	fun config(): BridgeConfig = config

	fun backendBridge(): ClientBackendBridgeService = backendBridge

	fun modBackendCredential(): String? =
		credentialManager.credential(activeModBackendBaseUrl(config), Minecraft.getInstance().user.profileId)

	fun backendStatus(): BackendStatusSnapshot = backendBridge.status()

	fun testBackendConnection(config: BridgeConfig, callback: (BackendStatusSnapshot) -> Unit) {
		val candidate = configManager.normalize(config.copy())
		val generation = ClientSessionLifecycle.snapshot()
		ircSendExecutor.execute {
			val status = backendBridge.testConnection(candidate)
			Minecraft.getInstance().execute {
				if (ClientSessionLifecycle.isCurrent(generation)) callback(status)
			}
		}
	}

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
		HighClassDiceTrackerFeature.onConfigChanged()
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

	private fun acceptGameInvite(context: CommandContext<FabricClientCommandSource>): Int =
		handleGameInvite(context, accept = true)

	private fun denyGameInvite(context: CommandContext<FabricClientCommandSource>): Int =
		handleGameInvite(context, accept = false)

	private fun handleGameInvite(context: CommandContext<FabricClientCommandSource>, accept: Boolean): Int {
		val inviteId = StringArgumentType.getString(context, "inviteId")
		if (!MINIGAME_ID_PATTERN.matches(inviteId) || inviteId.length > MAX_MINIGAME_ID_LENGTH) {
			context.source.sendError(Component.literal("Invite ID is invalid."))
			return 0
		}
		if (minigameController.invites().none { it.inviteId == inviteId }) {
			context.source.sendError(Component.literal("No pending invite has that ID."))
			return 0
		}

		if (accept) minigameController.acceptInvite(inviteId) else minigameController.denyInvite(inviteId)
		return 1
	}

	private fun leaveActiveGame(context: CommandContext<FabricClientCommandSource>): Int {
		minigameController.leaveActiveMatch()
		return 1
	}

	private fun showOwnCataStats(context: CommandContext<FabricClientCommandSource>): Int {
		DungeonAutoKickFeature.showCataStats(Minecraft.getInstance().user.name)
		context.source.sendFeedback(Component.literal("Fetching dungeon stats..."))
		return 1
	}

	private fun showCataStats(context: CommandContext<FabricClientCommandSource>): Int {
		val player = StringArgumentType.getString(context, "player")
		if (!USERNAME_PATTERN.matches(player)) {
			context.source.sendError(Component.literal("Player name must be 3-16 letters, numbers, or underscores."))
			return 0
		}
		DungeonAutoKickFeature.showCataStats(player)
		context.source.sendFeedback(Component.literal("Fetching dungeon stats..."))
		return 1
	}

	private fun toggleHighClassDiceTracker(context: CommandContext<FabricClientCommandSource>): Int =
		setHighClassDiceTracker(context, !config.highClassDiceTrackerEnabled)

	private fun setHighClassDiceTracker(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
		val previous = config.highClassDiceTrackerEnabled
		config.highClassDiceTrackerEnabled = enabled

		return try {
			configManager.save(config)
			HighClassDiceTrackerFeature.onConfigChanged()
			context.source.sendFeedback(Component.literal("High Class Dice tracker ${if (enabled) "enabled" else "disabled"}."))
			1
		} catch (_: IOException) {
			config.highClassDiceTrackerEnabled = previous
			context.source.sendError(Component.literal("Failed to save High Class Dice tracker setting."))
			0
		}
	}

	private fun showHighClassDiceTrackerStatus(context: CommandContext<FabricClientCommandSource>): Int {
		context.source.sendFeedback(Component.literal("Fetching High Class Dice tracker status..."))
		HighClassDiceTrackerFeature.requestStatus { message ->
			context.source.sendFeedback(message)
		}
		return 1
	}

	private fun runDevTest(context: CommandContext<FabricClientCommandSource>): Int {
		if (!requireDevMode(context)) return 0
		return if (ChimeraBookDropEffectsFeature.runTest()) {
			context.source.sendFeedback(Component.literal("Triggered Chimera book drop effect test."))
			1
		} else {
			context.source.sendError(Component.literal("Chimera book drop effects module is disabled."))
			0
		}
	}

	private fun logNearbyEntities(context: CommandContext<FabricClientCommandSource>): Int {
		if (!requireDevMode(context)) return 0
		val result = EntityDiagnostics.logNearby(Minecraft.getInstance())
		if (result == null) {
			context.source.sendError(Component.literal("Entity scan requires an active world."))
			return 0
		}

		context.source.sendFeedback(
			Component.literal(
				"Logged ${result.entityCount} nearby entities, including ${result.itemDisplayCount} item displays, to latest.log.",
			),
		)
		return 1
	}

	private fun requireDevMode(context: CommandContext<FabricClientCommandSource>): Boolean {
		if (config.devModeEnabled) return true
		context.source.sendError(Component.literal("Enable developer mode with /xclipsen dev on first."))
		return false
	}

	private fun devStatusCommand(name: String, status: () -> String): LiteralArgumentBuilder<FabricClientCommandSource> =
		ClientCommandManager.literal(name).then(
			ClientCommandManager.literal("status").executes { context ->
				if (!requireDevMode(context)) {
					0
				} else {
					context.source.sendFeedback(Component.literal("$name: ${status()}"))
					1
				}
			},
		)

	private fun locationStatusLine(): String =
		"island=${LocationTracker.currentIsland}, mode=${LocationTracker.currentModeIdentifier.ifBlank { "unknown" }}, " +
			"area=${LocationTracker.currentArea.ifBlank { "unknown" }}, hypixel=${LocationTracker.isOnHypixel}, " +
			"skyblock=${LocationTracker.isOnHypixelSkyBlock}"

	private fun shardTrackerDiagnosticLine(): String {
		val data = HideonleafShardTracker.displayData()
		return "enabled=${config.shardTrackerEnabled}, view=${if (HideonleafShardTracker.showingSession) "session" else "total"}, " +
			"items=${data.items.size}, kills=${data.kills}"
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
				HighClassDiceTrackerFeature.onConfigChanged()
			}
			showDevModeStatus(context)
		} catch (_: IOException) {
			config.devModeEnabled = previous
			context.source.sendError(Component.literal("Dev-Modus konnte nicht gespeichert werden."))
			0
		}
	}

	private fun showDevModeStatus(context: CommandContext<FabricClientCommandSource>): Int {
		val mode = if (config.devModeEnabled) "Lokal" else "Produktion"
		context.source.sendFeedback(Component.literal("Xclipsen Backend: $mode (${activeModBackendBaseUrl(config)})"))
		return 1
	}

	private fun handleEndTick(client: Minecraft) {
		if (!ensureActiveProfile(client)) {
			return
		}
		if (ClientSessionLifecycle.updateLevel(client)) {
			resetPlayerRuntimeState(playDisconnect = false)
		}

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
		PickobulusHelperFeature.onTick(client)
		SlayerFeature.onTick(client)
		BlazeSlayerFeature.onTick(client)
		FireFreezeFeature.onTick(client)
		MineshaftAutoWarpFeature.onTick(client)
		DungeonAutoKickFeature.onTick(client)
		PartyFinderFeature.onTick(client)
		CorpseEspFeature.onTick(client)
		MobModelFeature.onTick(client)
		ModUpdateChecker.onTick(client)
		ExperimentationTableFeature.onTick(client)
		DeploybleFeature.onTick(client)

		if (client.screen !is ChatScreen) {
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
			openHudEditor(client, client.screen)
		}
	}

	private fun ensureActiveProfile(client: Minecraft): Boolean {
		val profileId = client.user.profileId
		if (configManager.activeProfileId() == profileId) {
			return true
		}

		return try {
			configManager.activeProfileId()?.let { configManager.save(config) }
			ClientSessionLifecycle.invalidate()
			resetPlayerRuntimeState(playDisconnect = true)
			minigameController.onDisconnect()
			backendBridge.stop()
			config = configManager.load(profileId)
			incomingBridgeMessagesEnabled = true
			ircChatModeExpiresAt = 0L
			recentCoopRelays.clear()
			applyBackendBridgeConfig()
			minigameController.onConfigChanged()
			ModUpdateChecker.onConfigChanged()
			MobModelFeature.onConfigChanged()
			HighClassDiceTrackerFeature.onConfigChanged()
			LOGGER.info("Loaded player-scoped config for profile {}", profileId)
			true
		} catch (exception: IOException) {
			LOGGER.error("Failed to switch player-scoped config to profile {}", profileId, exception)
			false
		}
	}

	private fun resetPlayerRuntimeState(playDisconnect: Boolean) {
		ServerTickTracker.reset()
		LocationTracker.reset()
		CorpseEspFeature.onDisconnect()
		M5Feature.onWorldChange()
		MobModelFeature.onDisconnect()
		MortDoorBarrierFeature.onWorldChange()
		PurpleTerracottaHighlightFeature.onWorldChange()
		WormholeFinderFeature.onWorldChange()
		PickaxeAbilityCooldownFeature.onWorldChange()
		PickobulusHelperFeature.onWorldChange()
		FireFreezeFeature.onWorldChange()
		MineshaftAutoWarpFeature.onDisconnect()
		HideonleafShardTracker.onWorldChange()
		DungeonAutoKickFeature.onDisconnect()
		PartyFinderFeature.onDisconnect()
		DeploybleFeature.onWorldChange()
		SlayerFeature.onWorldChange()
		AuctionHouseUnderbidFeature.onWorldChange()
		HighClassDiceTrackerFeature.onWorldChange()
		AutoSprintFeature.onWorldChange()
		ExperimentationTableFeature.onWorldChange()
		BlazeSlayerFeature.onWorldChange()
		ImagePreviewManager.reset()
		if (playDisconnect) {
			SilentDisconnectFeature.onPlayDisconnect()
		}
	}

	private fun openConfigScreen(client: Minecraft?) {
		if (client == null) {
			return
		}

		client.execute {
			client.setScreen(XclipsenConfigScreen(client.screen, this))
		}
	}

	fun openHudEditorScreen(parent: Screen?) {
		openHudEditor(Minecraft.getInstance(), parent)
	}

	private fun openHudEditor(client: Minecraft?, parent: Screen?) {
		if (client == null) {
			return
		}

		client.execute {
			try {
				client.setScreen(XclipsenHudEditorScreen(parent, this))
			} catch (error: LinkageError) {
				LOGGER.error("Failed to load the HUD editor classes", error)
				client.player?.sendSystemMessage(Component.literal("Xclipsen HUD editor could not be loaded. Restart Minecraft after updating the mod."))
			}
		}
	}

	private fun reloadConfig(context: CommandContext<FabricClientCommandSource>): Int {
		config = configManager.load(Minecraft.getInstance().user.profileId)
		applyBackendBridgeConfig()
		HighClassDiceTrackerFeature.onConfigChanged()
		context.source.sendFeedback(Component.literal("IRC bridge config reloaded: ${configManager.path()}"))
		return 1
	}

	private fun enableIncomingBridgeMessages(context: CommandContext<FabricClientCommandSource>): Int {
		incomingBridgeMessagesEnabled = true
		applyIncomingBridgeState()
		context.source.sendFeedback(Component.literal("IRC incoming messages enabled."))
		return 1
	}

	private fun disableIncomingBridgeMessages(context: CommandContext<FabricClientCommandSource>): Int {
		incomingBridgeMessagesEnabled = false
		applyIncomingBridgeState()
		context.source.sendFeedback(Component.literal("IRC incoming messages disabled."))
		return 1
	}

	private fun applyIncomingBridgeState() {
		backendBridge.setIncomingMessagesEnabled(config.ircBridgeEnabled && incomingBridgeMessagesEnabled)
	}

	private fun applyBackendBridgeConfig() {
		backendBridge.configure(config)
		val profileId = Minecraft.getInstance().user.profileId
		backendBridge.configureModCredential(credentialManager.credential(activeModBackendBaseUrl(config), profileId))
		if (!config.ircBridgeEnabled) {
			IrcChatTabManager.setActiveTab(IrcChatTabManager.ChatTab.MAIN)
			backendBridge.stop()
			return
		}

		backendBridge.start(config)
		applyIncomingBridgeState()
	}

	private fun showStatus(context: CommandContext<FabricClientCommandSource>): Int {
		context.source.sendFeedback(Component.literal("Testing IRC backend connection..."))
		val generation = ClientSessionLifecycle.snapshot()
		ircSendExecutor.execute {
			val status = backendBridge.testConnection()
			Minecraft.getInstance().execute {
				if (ClientSessionLifecycle.isCurrent(generation)) {
					Minecraft.getInstance().player?.sendSystemMessage(Component.literal(formatStatus(status)))
				}
			}
		}
		return 1
	}

	private fun showShulkerGlowStatus(context: CommandContext<FabricClientCommandSource>): Int {
		context.source.sendFeedback(Component.literal("Shulker glow is ${if (config.shulkerGlowEnabled) "enabled" else "disabled"}."))
		return 1
	}

	private fun toggleShulkerGlow(context: CommandContext<FabricClientCommandSource>): Int =
		setShulkerGlow(context, !config.shulkerGlowEnabled)

	private fun setShulkerGlow(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
		config.shulkerGlowEnabled = enabled
		return try {
			configManager.save(config)
			context.source.sendFeedback(Component.literal("Shulker glow ${if (enabled) "enabled" else "disabled"}."))
			1
		} catch (_: IOException) {
			context.source.sendError(Component.literal("Failed to save shulker glow setting."))
			0
		}
	}

	private fun sendIrcMessage(context: CommandContext<FabricClientCommandSource>): Int {
		val message = validateIrcMessage(StringArgumentType.getString(context, "message")) {
			context.source.sendError(Component.literal(it))
		} ?: return 0

		return if (sendIrcMessageInternal(message, ::sendCommandError)) 1 else 0
	}

	private fun showLinkedStatus(context: CommandContext<FabricClientCommandSource>): Int {
		context.source.sendFeedback(Component.literal("Checking linked profile..."))
		val generation = ClientSessionLifecycle.snapshot()
		ircSendExecutor.execute {
			val status = backendBridge.getLinkStatus()
			Minecraft.getInstance().execute {
				if (!ClientSessionLifecycle.isCurrent(generation)) return@execute
				if (!status.linked) {
					sendClientFeedback(if (status.error.isBlank()) {
						"Not linked. Use /link username:<ign> on Discord, then /xclipsen link <code> here."
					} else status.error)
					return@execute
				}
				ircLinkWarningShown = false
				sendClientFeedback("Linked profile: ${status.account.name} (${status.account.uuid})")
			}
		}
		return 1
	}

	private fun completeLink(context: CommandContext<FabricClientCommandSource>): Int {
		val code = StringArgumentType.getString(context, "code")
		if (!LINK_CODE_PATTERN.matches(code)) {
			context.source.sendError(Component.literal("Link code must be exactly 22 URL-safe characters."))
			return 0
		}
		val generation = ClientSessionLifecycle.snapshot()
		val profileId = Minecraft.getInstance().user.profileId
		val backendOrigin = activeModBackendBaseUrl(config)
		context.source.sendFeedback(Component.literal("Completing account link..."))
		ircSendExecutor.execute {
			val status = backendBridge.completeLink(code)
			Minecraft.getInstance().execute {
				if (!ClientSessionLifecycle.isCurrent(generation) || Minecraft.getInstance().user.profileId != profileId || activeModBackendBaseUrl(config) != backendOrigin) {
					return@execute
				}
				if (status.error.isNotBlank() || !status.linked) {
					sendClientError(if (status.error.isBlank()) "Link failed." else status.error)
					return@execute
				}
				try {
					credentialManager.store(backendOrigin, profileId, status.account, status.credential, status.expiresAt)
					backendBridge.configureModCredential(status.credential)
					minigameController.onConfigChanged()
					ircLinkWarningShown = false
					backendBridge.discardBacklogOnNextPoll()
					sendClientFeedback("Linked successfully: ${status.account.name}")
				} catch (exception: IllegalArgumentException) {
					sendClientError(exception.message ?: "Credential response was invalid.")
				} catch (_: IOException) {
					sendClientError("Linked, but the credential could not be stored.")
				}
			}
		}
		return 1
	}

	private fun enableIrcChatMode(context: CommandContext<FabricClientCommandSource>): Int {
		ircChatModeExpiresAt = System.currentTimeMillis() + IRC_CHAT_MODE_WINDOW_MS
		context.source.sendFeedback(
			Component.literal("IRC chat mode enabled for 2.5 minutes. Normal chat messages will go to IRC."),
		)
		return 1
	}

	private fun disableIrcChatMode(context: CommandContext<FabricClientCommandSource>): Int {
		ircChatModeExpiresAt = 0L
		context.source.sendFeedback(Component.literal("IRC chat mode disabled."))
		return 1
	}

	private fun handleOutgoingChatMessage(message: String): Boolean {
		if (!isIrcChatModeActive()) {
			return true
		}

		val trimmedMessage = validateIrcMessage(message, ::sendClientError) ?: return false

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

		if (!isIrcChatModeActive()) {
			return true
		}

		if (shouldDisableIrcChatMode(normalized)) {
			disableIrcChatMode()
		}

		return true
	}

	private fun handleIncomingMessage(message: Component?) {
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

	private fun shouldSuppressIncomingMessage(message: Component?): Boolean {
		return SilentDisconnectFeature.shouldSuppressStatusMessage(message) || ChatFeature.shouldSuppressMessage(message)
	}

	private fun handleHideonleafLostFightAlert(message: Component?) {
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

		val client = Minecraft.getInstance()
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
		Minecraft.getInstance().soundManager.play(
			SoundCatalog.masterSound(
				config.hideonleafLostFightAlertSoundId,
				config.hideonleafLostFightAlertSoundPitch.coerceIn(0.1f, 2.0f),
				config.hideonleafLostFightAlertSoundVolume.coerceIn(0.0f, 2.0f),
			),
		)
	}

	private fun handleIncomingCoopChat(message: Component?) {
		if (!config.ircBridgeEnabled) {
			return
		}

		if (!config.coopChatRelayEnabled) {
			return
		}

		val parsed = parseCoopChatMessage(message) ?: return
		val localPlayer = Minecraft.getInstance().user.name
		if (localPlayer.isBlank()) {
			return
		}

		val dedupeKey = "${parsed.playerName.lowercase(Locale.ROOT)}|${parsed.message.lowercase(Locale.ROOT)}"
		if (!recordCoopRelayKey(dedupeKey)) {
			return
		}

		backendBridge.relayCoopChat(localPlayer, parsed.playerName, parsed.message)
	}

	private fun parseCoopChatMessage(message: Component?): CoopChatMessage? {
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

		val playerName = Minecraft.getInstance().user.name
		if (playerName.isBlank()) {
			errorHandler("Minecraft username missing.")
			return false
		}
		val generation = ClientSessionLifecycle.snapshot()
		val profileId = Minecraft.getInstance().user.profileId
		val backendOrigin = activeModBackendBaseUrl(config)

		ircSendExecutor.execute {
			val linkStatus = backendBridge.getLinkStatus()
			Minecraft.getInstance().execute {
				if (!ClientSessionLifecycle.isCurrent(generation) || Minecraft.getInstance().user.profileId != profileId || activeModBackendBaseUrl(config) != backendOrigin || !config.ircBridgeEnabled) {
					return@execute
				}
				if (!linkStatus.linked) {
					if (!ircLinkWarningShown) {
						errorHandler(if (linkStatus.error.isBlank()) {
							"You are not linked yet. Use /link username:<ign> on Discord and /xclipsen link <code> in Minecraft."
						} else linkStatus.error)
						ircLinkWarningShown = true
					}
					return@execute
				}

				ircLinkWarningShown = false
				cacheLinkedDisplayName(linkStatus)
				backendBridge.sendIrcMessage(playerName, message)
			}
		}
		return true
	}

	private fun sendCommandError(message: String) {
		val client = Minecraft.getInstance()
		client.execute {
			client.player?.sendSystemMessage(Component.literal(message))
		}
	}

	private fun enableIrcChatMode() {
		ircChatModeExpiresAt = System.currentTimeMillis() + IRC_CHAT_MODE_WINDOW_MS
		sendClientFeedback("IRC chat mode enabled for 2.5 minutes. Normal chat messages will go to IRC.")
	}

	private fun sendClientFeedback(message: String) {
		Minecraft.getInstance().player?.sendSystemMessage(Component.literal(message))
	}

	private fun disableIrcChatMode() {
		ircChatModeExpiresAt = 0L
		sendClientFeedback("IRC chat mode disabled.")
	}

	private fun sendClientError(message: String) {
		val client = Minecraft.getInstance()
		client.execute {
			client.player?.sendSystemMessage(Component.literal(message))
		}
	}

	private fun showShardTrackerStatus(context: CommandContext<FabricClientCommandSource>): Int {
		val data = HideonleafShardTracker.displayData()
		val duration = HideonleafShardTracker.selectedDurationMs()
		val profit = HideonleafShardTracker.totalProfit(data)
		val perHour = HideonleafShardTracker.displayProfitPerHour(data, duration)
		val view = if (HideonleafShardTracker.showingSession) "User" else "Total"
		val durationAvailable = HideonleafShardTracker.selectedDurationAvailable()

		context.source.sendFeedback(Component.literal(buildString {
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
		context.source.sendFeedback(Component.literal("§aShard tracker session reset."))
		return 1
	}

	private fun warnShardTrackerSessionReset(context: CommandContext<FabricClientCommandSource>): Int {
		context.source.sendError(Component.literal("This clears the current shard session. Confirm with /xclipsen tracker shard reset session confirm."))
		return 0
	}

	private fun resetShardTrackerTotal(context: CommandContext<FabricClientCommandSource>): Int {
		HideonleafShardTracker.resetTotal()
		context.source.sendFeedback(Component.literal("§aShard tracker fully reset (session + total)."))
		return 1
	}

	private fun warnShardTrackerTotalReset(context: CommandContext<FabricClientCommandSource>): Int {
		context.source.sendError(Component.literal("This permanently clears session and total shard history, including synced totals. Confirm with /xclipsen tracker shard reset total confirm."))
		return 0
	}

	private fun validateIrcMessage(raw: String, errorHandler: (String) -> Unit): String? {
		val message = raw.trim()
		when {
			message.isBlank() -> errorHandler("Message must not be empty.")
			message.any(Char::isISOControl) -> errorHandler("Message must not contain control characters.")
			message.length > MAX_IRC_MESSAGE_LENGTH -> errorHandler("Message must not exceed $MAX_IRC_MESSAGE_LENGTH characters.")
			else -> return message
		}
		return null
	}

	private fun toggleShardTrackerView(context: CommandContext<FabricClientCommandSource>): Int {
		HideonleafShardTracker.toggleView()
		val view = if (HideonleafShardTracker.showingSession) "User" else "Total"
		context.source.sendFeedback(Component.literal("§aShard tracker now showing: §e$view"))
		return 1
	}

	private fun setShardTracker(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
		config.shardTrackerEnabled = enabled
		return try {
			configManager.save(config)
			context.source.sendFeedback(Component.literal("§aShard tracker ${if (enabled) "enabled" else "disabled"}."))
			1
		} catch (_: IOException) {
			context.source.sendError(Component.literal("Failed to save shard tracker setting."))
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
		private const val MAX_IRC_MESSAGE_LENGTH = 280
		private const val MAX_MINIGAME_ID_LENGTH = 128
		private val AMPERSAND_COLOR_PATTERN = Regex("(?i)&[0-9A-FK-OR]")
		private val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")
		private val LINK_CODE_PATTERN = Regex("^[A-Za-z0-9_-]{22}$")
		private val MINIGAME_ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")

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
