package de.xclipsen.ircbridge.minigame

import com.google.gson.JsonObject
import net.minecraft.world.item.Item
import net.minecraft.client.gui.screens.Screen

enum class GameType {
	AI,
	MULTIPLAYER,
}

data class MinigameInvite(
	val inviteId: String,
	val minigameId: String,
	val senderUuid: String,
	val receiverUuid: String,
	val senderUsername: String,
	val receiverUsername: String,
	val createdAt: Long,
	val expiresAt: Long,
	val status: String,
)

data class TicTacToeGameState(
	val board: List<String>,
	val playerXUuid: String,
	val playerOUuid: String,
	val currentTurnUuid: String?,
	val winnerUuid: String?,
	val winningCombination: List<Int>,
	val isDraw: Boolean,
	val rematchVotes: List<String>,
)

data class MinigameMatch(
	val matchId: String,
	val minigameId: String,
	val playerOneUuid: String,
	val playerTwoUuid: String,
	val playerOneUsername: String,
	val playerTwoUsername: String,
	val status: String,
	val startingPlayerUuid: String,
	val cancelReason: String?,
	val cancelledByUuid: String? = null,
	val cancelledByUsername: String? = null,
	val revision: Long = 0L,
	val acceptedRequestId: String? = null,
	val state: TicTacToeGameState,
)

data class NetworkResult(
	val ok: Boolean,
	val error: String = "",
	val data: JsonObject? = null,
)

data class PendingMove(
	val fieldIndex: Int,
	val requestId: String,
	val expectedRevision: Long,
)

interface Minigame {
	val id: String
	val displayName: String
	val icon: Item
	val description: List<String>
	val supportedModes: Set<GameType>

	fun openModeMenu(parent: Screen, controller: MinigameController)
	fun startAiGame(controller: MinigameController)
	fun startMultiplayerGame(controller: MinigameController, targetUsername: String)
}

object MinigameRegistry {
	private val games = linkedMapOf<String, Minigame>()

	fun register(minigame: Minigame) {
		games[minigame.id] = minigame
	}

	fun all(): List<Minigame> = games.values.toList()

	fun find(id: String): Minigame? = games[id]
}
