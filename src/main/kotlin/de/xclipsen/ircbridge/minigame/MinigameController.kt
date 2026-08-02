package de.xclipsen.ircbridge.minigame

import com.google.gson.Gson
import com.google.gson.JsonObject
import de.xclipsen.ircbridge.BridgeConfig
import de.xclipsen.ircbridge.BridgeConfigManager
import de.xclipsen.ircbridge.XclipsenIrcBridgeClient
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import org.slf4j.LoggerFactory

class MinigameInviteManager {
	private val pending = linkedMapOf<String, MinigameInvite>()

	fun all(): List<MinigameInvite> = pending.values.filter { it.status == "PENDING" && it.expiresAt > System.currentTimeMillis() }
	fun add(invite: MinigameInvite) {
		pending[invite.inviteId] = invite
	}
	fun remove(inviteId: String) {
		pending.remove(inviteId)
	}
	fun clear() {
		pending.clear()
	}
}

class MinigamePresenceManager {
	var registered: Boolean = false
		private set
	var serverId: String = ""
		private set

	fun registered(serverId: String) {
		this.registered = true
		this.serverId = serverId
	}

	fun disconnected() {
		registered = false
		serverId = ""
	}
}

class MinigameController(
	private val mod: XclipsenIrcBridgeClient,
) {
	private val inviteManager = MinigameInviteManager()
	private val presenceManager = MinigamePresenceManager()
	private val network = MinigameNetworkClient(LOGGER, ::handleNetworkEvent)
	private var activeGame: TicTacToeMatchController? = null
	private val cancelledMatchIds = mutableSetOf<String>()
	private var lastNetworkErrorAt = 0L
	private var lastReconnectAttemptAt = 0L
	private var configuredCredential = ""

	fun initialize() {
		MinigameRegistry.register(TicTacToeMinigame)
		configuredCredential = mod.modBackendCredential().orEmpty()
		network.configure(mod.config(), configuredCredential)
	}

	fun onConfigChanged() {
		val config = mod.config()
		val credential = mod.modBackendCredential().orEmpty()
		val connectionChanged = network.backendBaseUrl() != activeBackendUrl(config) || credential != configuredCredential
		if (connectionChanged) {
			network.disconnect()
			inviteManager.clear()
			if (activeGame?.mode == GameType.MULTIPLAYER) {
				activeGame = null
			}
		}

		configuredCredential = credential
		network.configure(config, credential)
		if (connectionChanged && presenceManager.registered) {
			registerCurrentSession(presenceManager.serverId)
		}
	}

	fun onJoin(serverAddress: String?) {
		val client = client()
		val username = client.user.name
		val serverId = serverAddress?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: "integrated:$username"
		presenceManager.registered(serverId)
		configuredCredential = mod.modBackendCredential().orEmpty()
		network.configure(mod.config(), configuredCredential)
		registerCurrentSession(serverId)
	}

	fun onDisconnect() {
		presenceManager.disconnected()
		inviteManager.clear()
		if (activeGame?.mode == GameType.MULTIPLAYER) {
			activeGame = null
		}
		cancelledMatchIds.clear()
		lastNetworkErrorAt = 0L
		lastReconnectAttemptAt = 0L
		network.disconnect()
	}

	fun shutdown() {
		network.shutdown()
	}

	fun openFromCommand() {
		if (client().screen is ContainerScreen && client().screen !is ChestLikeScreen) {
			feedback("Close the server container before opening minigames.", error = true)
			return
		}
		if (activeGame != null) openActiveMatch(null) else openMainMenu(null)
	}

	fun openMainMenu(parent: Screen? = client().screen) {
		client().execute { client().setScreen(MinigameMainMenuScreen(parent, this)) }
	}

	fun openActiveMatch(parent: Screen? = client().screen) {
		val game = activeGame
		if (game == null) {
			openMainMenu(parent)
		} else {
			val returnScreen = parent ?: MinigameMainMenuScreen(null, this)
			client().execute { client().setScreen(TicTacToeScreen(returnScreen, game)) }
		}
	}

	fun startAiTicTacToe() {
		activeGame = TicTacToeMatchController.ai(this)
		openActiveMatch()
	}

	fun invite(targetUsername: String, minigameId: String) {
		if (!USERNAME_PATTERN.matches(targetUsername) || MinigameRegistry.find(minigameId) == null) {
			feedback("Invalid player name or mini-game ID.", error = true)
			return
		}
		if (targetUsername.equals(client().user.name, ignoreCase = true)) {
			feedback("You cannot invite yourself.", error = true)
			return
		}
		if (configuredCredential.isBlank()) {
			feedback("Link this Minecraft profile with /xclipsen link before using multiplayer mini-games.", error = true)
			return
		}
		if (!presenceManager.registered || !network.connected) {
			requestReconnect()
			feedback("The mini-game backend is not connected yet.", error = true)
			return
		}
		network.invite(targetUsername, minigameId) { generation, result ->
			dispatch(generation) {
				if (result.ok) {
					feedback("Invite sent to $targetUsername.")
					client().setScreen(null)
				} else {
					feedback(result.error, error = true)
				}
			}
		}
	}

	fun acceptInvite(inviteId: String) {
		if (!isIdentifier(inviteId)) {
			feedback("Invalid invite ID.", error = true)
			return
		}
		network.acceptInvite(inviteId) { generation, result ->
			dispatch(generation) {
				if (!result.ok) feedback(result.error, error = true)
			}
		}
	}

	fun denyInvite(inviteId: String) {
		if (!isIdentifier(inviteId)) {
			feedback("Invalid invite ID.", error = true)
			return
		}
		network.denyInvite(inviteId) { generation, result ->
			dispatch(generation) {
				if (!result.ok) feedback(result.error, error = true)
			}
		}
	}

	fun submitMove(matchId: String, fieldIndex: Int, requestId: String, expectedRevision: Long) {
		if (!isIdentifier(matchId) || !REQUEST_ID_PATTERN.matches(requestId) || requestId.length !in 8..MAX_IDENTIFIER_LENGTH || fieldIndex !in 0..8 || expectedRevision < 0L) {
			feedback("Invalid move request.", error = true)
			return
		}
		network.submitTicTacToeMove(matchId, fieldIndex, requestId, expectedRevision) { generation, result ->
			dispatch(generation) {
				val authoritativeMatch = parseNestedMatch(result.data)
				if (result.ok) {
					authoritativeMatch?.let(::updateMultiplayerMatch)
				} else {
					val game = activeGame?.takeIf { it.multiplayerMatch?.matchId == matchId }
					val rolledBack = game?.rejectPendingMove(requestId, authoritativeMatch) == true
					if (rolledBack) {
						feedback(MOVE_REJECTED_MESSAGE, error = true)
					} else if (game != null && authoritativeMatch == null) {
						feedback(result.error, error = true)
					}
				}
			}
		}
	}

	fun leaveMatch(matchId: String) {
		if (!isIdentifier(matchId)) {
			feedback("Invalid match ID.", error = true)
			return
		}
		network.leaveMatch(matchId) { generation, result ->
			dispatch(generation) {
				if (result.ok) {
					parseNestedMatch(result.data)?.let(::handleCancelledMatch)
				} else {
					activeGame?.takeIf { it.multiplayerMatch?.matchId == matchId }?.onLeaveFailed()
					feedback(result.error, error = true)
				}
			}
		}
	}

	fun leaveActiveMatch() {
		val game = activeGame
		if (game == null) {
			feedback("You are not currently in a match.", error = true)
		} else {
			game.leave()
		}
	}

	fun requestRematch(matchId: String) {
		if (!isIdentifier(matchId)) {
			feedback("Invalid match ID.", error = true)
			return
		}
		network.requestRematch(matchId) { generation, result ->
			dispatch(generation) {
				if (result.ok) {
					parseNestedMatch(result.data)?.let(::updateMultiplayerMatch)
					feedback("Rematch request sent.")
				} else {
					feedback(result.error, error = true)
				}
			}
		}
	}

	fun clearActiveGame() {
		activeGame = null
	}

	fun activeMatch(): MinigameMatch? = activeGame?.multiplayerMatch
	fun hasActiveGame(): Boolean = activeGame != null
	fun invites(): List<MinigameInvite> = inviteManager.all()
	fun statusLine(): String {
		val match = activeMatch()
		return "registered=${presenceManager.registered}, connected=${network.connected}, invites=${invites().size}, " +
			"game=${if (activeGame == null) "none" else match?.let { "${it.minigameId}:${it.status}" } ?: "local"}"
	}
	fun localUuid(): String = client().user.profileId.toString()
	fun client(): Minecraft = Minecraft.getInstance()

	fun feedback(message: String, error: Boolean = false) {
		if (message.isBlank()) return
		if (client().isSameThread) {
			sendFeedback(message, error)
		} else {
			client().execute { sendFeedback(message, error) }
		}
	}

	private fun sendFeedback(message: String, error: Boolean) {
		client().player?.sendSystemMessage(
			Component.literal("[Minigames] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(message).withStyle(if (error) ChatFormatting.RED else ChatFormatting.WHITE)),
		)
	}

	private fun handleNetworkEvent(generation: Long, type: String, data: JsonObject) {
		dispatch(generation) {
			when (type) {
				"minigame_invite_received" -> handleInviteReceived(parseInvite(data))
				"minigame_invite_denied", "minigame_invite_expired" -> {
					parseInvite(data)?.let { inviteManager.remove(it.inviteId) }
					feedback(if (type.endsWith("expired")) "A game invite expired." else "A game invite was denied.")
				}
				"minigame_match_started", "minigame_rematch_started" -> parseMatch(data)?.let(::startMultiplayerMatch)
				"minigame_match_state_update", "minigame_match_finished", "minigame_rematch_request" -> parseMatch(data)?.let(::updateMultiplayerMatch)
				"minigame_match_cancelled" -> parseMatch(data)?.let(::handleCancelledMatch)
				"tictactoe_move_rejected" -> handleMoveRejected(data)
				"player_presence_update" -> {
					val username = data.get("username")?.asString ?: "Your opponent"
					if (data.get("online")?.asBoolean == false) {
						feedback("$username lost connection. Waiting for them to reconnect.")
					} else {
						feedback("$username reconnected.")
					}
				}
				"network_error" -> {
					requestReconnect()
					val now = System.currentTimeMillis()
					if (now - lastNetworkErrorAt > 15_000L) {
						lastNetworkErrorAt = now
						feedback(data.get("error")?.asString ?: "Mini-game network error.", error = true)
					}
				}
			}
		}
	}

	private fun dispatch(generation: Long, action: () -> Unit) {
		client().execute {
			if (!network.isCurrentGeneration(generation)) return@execute
			try {
				action()
			} catch (exception: Exception) {
				LOGGER.error("Minigame client callback failed.", exception)
			}
		}
	}

	private fun handleInviteReceived(invite: MinigameInvite?) {
		invite ?: return
		inviteManager.add(invite)
		val accept = Component.literal("[Accept]").withStyle(ChatFormatting.GREEN).withStyle {
			it.withClickEvent(ClickEvent.RunCommand("/xclipsen game accept ${invite.inviteId}"))
		}
		val deny = Component.literal("[Deny]").withStyle(ChatFormatting.RED).withStyle {
			it.withClickEvent(ClickEvent.RunCommand("/xclipsen game deny ${invite.inviteId}"))
		}
		client().player?.sendSystemMessage(
			Component.literal("${invite.senderUsername} challenged you to Tic-Tac-Toe.\n").withStyle(ChatFormatting.GOLD)
				.append(accept).append(Component.literal(" ")).append(deny),
		)
	}

	private fun startMultiplayerMatch(match: MinigameMatch) {
		if (match.status != "ACTIVE" || match.matchId in cancelledMatchIds) return
		inviteManager.clear()
		val current = activeGame
		if (current?.mode == GameType.MULTIPLAYER) {
			if (current.multiplayerMatch?.matchId == match.matchId) {
				current.updateMatch(match)
				return
			}
			if (!current.finished) return
		}
		activeGame = TicTacToeMatchController.multiplayer(this, match)
		openMatchFromNetworkEvent()
	}

	private fun updateMultiplayerMatch(match: MinigameMatch) {
		if (match.matchId in cancelledMatchIds) return
		val current = activeGame
		if (current?.mode == GameType.MULTIPLAYER && current.multiplayerMatch?.matchId == match.matchId) {
			current.updateMatch(match)
		} else if (match.status == "ACTIVE" && (current?.mode != GameType.MULTIPLAYER || current.finished)) {
			activeGame = TicTacToeMatchController.multiplayer(this, match)
			openMatchFromNetworkEvent()
		}
	}

	private fun handleMoveRejected(data: JsonObject) {
		val requestId = data.get("requestId")?.asString.orEmpty()
		if (!REQUEST_ID_PATTERN.matches(requestId) || requestId.length !in 8..MAX_IDENTIFIER_LENGTH) return
		val authoritativeMatch = parseNestedMatch(data)
		val matchId = data.get("matchId")?.asString ?: authoritativeMatch?.matchId
		val game = activeGame?.takeIf { it.mode == GameType.MULTIPLAYER && it.multiplayerMatch?.matchId == matchId }
		if (game?.rejectPendingMove(requestId, authoritativeMatch) == true) {
			feedback(MOVE_REJECTED_MESSAGE, error = true)
		}
	}

	private fun handleCancelledMatch(match: MinigameMatch) {
		if (match.status != "CANCELLED") return
		if (!cancelledMatchIds.add(match.matchId)) return
		val current = activeGame?.takeIf { it.mode == GameType.MULTIPLAYER && it.multiplayerMatch?.matchId == match.matchId }
		current?.updateMatch(match)
		if (current != null) {
			activeGame = null
			if ((client().screen as? TicTacToeScreen)?.belongsTo(match.matchId) == true) {
				client().setScreen((client().screen as TicTacToeScreen).parentScreen())
			}
		}

		val message = when {
			match.cancelReason == "PLAYER_LEFT" && match.cancelledByUuid == localUuid() ->
				"You left the match. The game was cancelled."
			match.cancelReason == "PLAYER_LEFT" ->
				"${match.cancelledByUsername ?: "The other player"} left the match. The game was cancelled."
			match.cancelReason == "PLAYER_DISCONNECTED" ->
				"The match was cancelled because a player disconnected."
			else -> "The match was cancelled."
		}
		feedback(message)
	}

	private fun openMatchFromNetworkEvent() {
		val currentScreen = client().screen
		if (currentScreen == null || currentScreen is ChestLikeScreen) {
			openActiveMatch(currentScreen)
		} else {
			feedback("A match started. Open it with /xclipsen game.")
		}
	}

	private fun parseInvite(data: JsonObject): MinigameInvite? =
		runCatching { GSON.fromJson(data, MinigameInvite::class.java) }.getOrNull()?.takeIf {
			isIdentifier(it.inviteId) && isIdentifier(it.minigameId, MAX_MINIGAME_ID_LENGTH) &&
				USERNAME_PATTERN.matches(it.senderUsername) && USERNAME_PATTERN.matches(it.receiverUsername)
		}

	private fun parseMatch(data: JsonObject): MinigameMatch? =
		runCatching { GSON.fromJson(data, MinigameMatch::class.java) }.getOrNull()?.takeIf {
			isIdentifier(it.matchId) && isIdentifier(it.minigameId, MAX_MINIGAME_ID_LENGTH) &&
				USERNAME_PATTERN.matches(it.playerOneUsername) && USERNAME_PATTERN.matches(it.playerTwoUsername)
		}
	private fun parseNestedMatch(data: JsonObject?): MinigameMatch? =
		data?.get("match")?.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseMatch)

	private fun registerCurrentSession(serverId: String) {
		if (configuredCredential.isBlank()) {
			return
		}
		network.register(serverId, modVersion())
	}

	private fun requestReconnect() {
		if (!presenceManager.registered) {
			return
		}
		val now = System.currentTimeMillis()
		if (now - lastReconnectAttemptAt < RECONNECT_COOLDOWN_MS) {
			return
		}
		lastReconnectAttemptAt = now
		registerCurrentSession(presenceManager.serverId)
	}

	private fun activeBackendUrl(config: BridgeConfig): String =
		if (config.devModeEnabled) config.devBackendBaseUrl else BridgeConfigManager.MOD_BACKEND_BASE_URL

	private fun modVersion(): String = FabricLoader.getInstance().getModContainer("xclipsen_mod")
		.map { it.metadata.version.friendlyString }
		.orElse("unknown")

	private fun isIdentifier(value: String, maxLength: Int = MAX_IDENTIFIER_LENGTH): Boolean =
		value.length in 1..maxLength && IDENTIFIER_PATTERN.matches(value)

	companion object {
		private val LOGGER = LoggerFactory.getLogger("xclipsen_minigames")
		private val GSON = Gson()
		private const val MOVE_REJECTED_MESSAGE = "Your move could not be confirmed. The board was updated."
		private const val RECONNECT_COOLDOWN_MS = 5_000L
		private const val MAX_IDENTIFIER_LENGTH = 128
		private const val MAX_MINIGAME_ID_LENGTH = 40
		private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9_-]+$")
		private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")
		private val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")
	}
}
