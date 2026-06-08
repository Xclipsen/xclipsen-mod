package de.xclipsen.ircbridge.minigame

import com.google.gson.Gson
import com.google.gson.JsonObject
import de.xclipsen.ircbridge.XclipsenIrcBridgeClient
import de.xclipsen.ircbridge.activeModBackendBaseUrl
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.text.ClickEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.UUID

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

	fun initialize() {
		MinigameRegistry.register(TicTacToeMinigame)
		network.configure(mod.config())
	}

	fun onConfigChanged() {
		val config = mod.config()
		val backendChanged = network.backendBaseUrl() != activeModBackendBaseUrl(config)
		if (backendChanged) {
			network.disconnect()
			inviteManager.clear()
			if (activeGame?.mode == GameMode.MULTIPLAYER) {
				activeGame = null
			}
		}

		network.configure(config)
		if (backendChanged && presenceManager.registered) {
			registerCurrentSession(presenceManager.serverId)
		}
	}

	fun onJoin(serverAddress: String?) {
		val client = client()
		val username = client.session.username
		val serverId = serverAddress?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: "integrated:$username"
		presenceManager.registered(serverId)
		network.configure(mod.config())
		registerCurrentSession(serverId)
	}

	fun onDisconnect() {
		presenceManager.disconnected()
		if (activeGame?.mode == GameMode.MULTIPLAYER) {
			activeGame = null
		}
		network.disconnect()
	}

	fun shutdown() {
		network.shutdown()
	}

	fun openFromCommand() {
		if (activeGame != null) openActiveMatch() else openMainMenu()
	}

	fun openMainMenu() {
		client().execute { client().setScreen(MinigameMainMenuScreen(this)) }
	}

	fun openActiveMatch() {
		val game = activeGame
		if (game == null) {
			openMainMenu()
		} else {
			client().execute { client().setScreen(TicTacToeScreen(game)) }
		}
	}

	fun startAiTicTacToe() {
		activeGame = TicTacToeMatchController.ai(this)
		openActiveMatch()
	}

	fun invite(targetUsername: String, minigameId: String) {
		if (!presenceManager.registered || !network.connected) {
			feedback("The mini-game backend is not connected yet.", error = true)
			return
		}
		network.invite(targetUsername, minigameId) { result ->
			if (result.ok) {
				feedback("Invite sent to $targetUsername.")
				client().execute { client().setScreen(null) }
			} else {
				feedback(result.error, error = true)
			}
		}
	}

	fun acceptInvite(inviteId: String, senderUsername: String = "") {
		network.acceptInvite(inviteId, senderUsername) { result ->
			if (!result.ok) feedback(result.error, error = true)
		}
	}

	fun denyInvite(inviteId: String, senderUsername: String = "") {
		network.denyInvite(inviteId, senderUsername) { result ->
			if (!result.ok) feedback(result.error, error = true)
		}
	}

	fun submitMove(matchId: String, fieldIndex: Int, requestId: String, expectedRevision: Long) {
		network.submitTicTacToeMove(matchId, fieldIndex, requestId, expectedRevision) { result ->
			client().execute {
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
		network.leaveMatch(matchId) { result ->
			client().execute {
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
		network.requestRematch(matchId) { result ->
			client().execute {
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
	fun localUuid(): String = (client().session.uuidOrNull ?: UUID.nameUUIDFromBytes("OfflinePlayer:${client().session.username}".toByteArray(StandardCharsets.UTF_8))).toString()
	fun client(): MinecraftClient = MinecraftClient.getInstance()

	fun feedback(message: String, error: Boolean = false) {
		if (message.isBlank()) return
		client().execute {
			client().player?.sendMessage(
				Text.literal("[Minigames] ").formatted(Formatting.GOLD)
					.append(Text.literal(message).formatted(if (error) Formatting.RED else Formatting.WHITE)),
				false,
			)
		}
	}

	private fun handleNetworkEvent(type: String, data: JsonObject) {
		client().execute {
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
					val now = System.currentTimeMillis()
					if (now - lastNetworkErrorAt > 15_000L) {
						lastNetworkErrorAt = now
						feedback(data.get("error")?.asString ?: "Mini-game network error.", error = true)
					}
				}
			}
		}
	}

	private fun handleInviteReceived(invite: MinigameInvite?) {
		invite ?: return
		inviteManager.add(invite)
		val accept = Text.literal("[Accept]").formatted(Formatting.GREEN).styled {
			it.withClickEvent(ClickEvent.RunCommand("/game accept ${invite.senderUsername}"))
		}
		val deny = Text.literal("[Deny]").formatted(Formatting.RED).styled {
			it.withClickEvent(ClickEvent.RunCommand("/game deny ${invite.senderUsername}"))
		}
		client().player?.sendMessage(
			Text.literal("${invite.senderUsername} challenged you to Tic-Tac-Toe.\n").formatted(Formatting.GOLD)
				.append(accept).append(Text.literal(" ")).append(deny),
			false,
		)
	}

	private fun startMultiplayerMatch(match: MinigameMatch) {
		if (match.status != "ACTIVE" || match.matchId in cancelledMatchIds) return
		inviteManager.clear()
		val current = activeGame
		if (current?.mode == GameMode.MULTIPLAYER) {
			if (current.multiplayerMatch?.matchId == match.matchId) {
				current.updateMatch(match)
				return
			}
			if (!current.finished) return
		}
		activeGame = TicTacToeMatchController.multiplayer(this, match)
		openActiveMatch()
	}

	private fun updateMultiplayerMatch(match: MinigameMatch) {
		if (match.matchId in cancelledMatchIds) return
		val current = activeGame
		if (current?.mode == GameMode.MULTIPLAYER && current.multiplayerMatch?.matchId == match.matchId) {
			current.updateMatch(match)
		} else if (match.status == "ACTIVE" && (current?.mode != GameMode.MULTIPLAYER || current.finished)) {
			activeGame = TicTacToeMatchController.multiplayer(this, match)
			openActiveMatch()
		}
	}

	private fun handleMoveRejected(data: JsonObject) {
		val requestId = data.get("requestId")?.asString.orEmpty()
		if (requestId.isBlank()) return
		val authoritativeMatch = parseNestedMatch(data)
		val matchId = data.get("matchId")?.asString ?: authoritativeMatch?.matchId
		val game = activeGame?.takeIf { it.mode == GameMode.MULTIPLAYER && it.multiplayerMatch?.matchId == matchId }
		if (game?.rejectPendingMove(requestId, authoritativeMatch) == true) {
			feedback(MOVE_REJECTED_MESSAGE, error = true)
		}
	}

	private fun handleCancelledMatch(match: MinigameMatch) {
		if (match.status != "CANCELLED") return
		if (!cancelledMatchIds.add(match.matchId)) return
		val current = activeGame?.takeIf { it.mode == GameMode.MULTIPLAYER && it.multiplayerMatch?.matchId == match.matchId }
		current?.updateMatch(match)
		if (current != null) {
			activeGame = null
			if ((client().currentScreen as? TicTacToeScreen)?.belongsTo(match.matchId) == true) {
				client().setScreen(null)
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

	private fun parseInvite(data: JsonObject): MinigameInvite? = runCatching { GSON.fromJson(data, MinigameInvite::class.java) }.getOrNull()
	private fun parseMatch(data: JsonObject): MinigameMatch? = runCatching { GSON.fromJson(data, MinigameMatch::class.java) }.getOrNull()
	private fun parseNestedMatch(data: JsonObject?): MinigameMatch? =
		data?.get("match")?.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseMatch)

	private fun registerCurrentSession(serverId: String) {
		val client = client()
		val username = client.session.username
		val uuid = client.session.uuidOrNull ?: UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(StandardCharsets.UTF_8))
		network.register(uuid.toString(), username, serverId, modVersion())
	}

	private fun modVersion(): String = FabricLoader.getInstance().getModContainer("xclipsen_mod")
		.map { it.metadata.version.friendlyString }
		.orElse("unknown")

	companion object {
		private val LOGGER = LoggerFactory.getLogger("xclipsen_minigames")
		private val GSON = Gson()
		private const val MOVE_REJECTED_MESSAGE = "Your move could not be confirmed. The board was updated."
	}
}
