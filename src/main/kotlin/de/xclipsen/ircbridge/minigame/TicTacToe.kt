package de.xclipsen.ircbridge.minigame

import net.minecraft.item.Items
import net.minecraft.text.Text
import java.util.UUID
import kotlin.random.Random

object TicTacToeMinigame : Minigame {
	override val id: String = "tictactoe"
	override val displayName: String = "Tic-Tac-Toe"
	override val icon = Items.IRON_SWORD
	override val description: List<String> = listOf("Get three marks in a row.", "Play against AI or another player.")
	override val supportedModes: Set<GameMode> = setOf(GameMode.AI, GameMode.MULTIPLAYER)

	override fun openModeMenu(controller: MinigameController) {
		controller.client().setScreen(GameModeSelectionScreen(controller, this))
	}

	override fun startAiGame(controller: MinigameController) {
		controller.startAiTicTacToe()
	}

	override fun startMultiplayerGame(controller: MinigameController, targetUsername: String) {
		controller.invite(targetUsername, id)
	}
}

class TicTacToeBoard {
	val cells: MutableList<String> = MutableList(9) { "" }

	fun reset() {
		cells.indices.forEach { cells[it] = "" }
	}

	fun winningCombination(): List<Int> =
		WINNING_COMBINATIONS.firstOrNull { (a, b, c) -> cells[a].isNotBlank() && cells[a] == cells[b] && cells[a] == cells[c] }.orEmpty()

	fun isDraw(): Boolean = cells.all(String::isNotBlank) && winningCombination().isEmpty()

	companion object {
		val WINNING_COMBINATIONS = listOf(
			listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
			listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
			listOf(0, 4, 8), listOf(2, 4, 6),
		)
	}
}

object TicTacToeAI {
	fun chooseMove(board: List<String>): Int? {
		val free = board.indices.filter { board[it].isBlank() }
		return winningMove(board, "O")
			?: winningMove(board, "X")
			?: 4.takeIf(free::contains)
			?: listOf(0, 2, 6, 8).filter(free::contains).randomOrNull()
			?: free.randomOrNull(Random.Default)
	}

	private fun winningMove(board: List<String>, symbol: String): Int? {
		for (field in board.indices.filter { board[it].isBlank() }) {
			val candidate = board.toMutableList().apply { this[field] = symbol }
			if (TicTacToeBoard.WINNING_COMBINATIONS.any { (a, b, c) -> candidate[a] == symbol && candidate[b] == symbol && candidate[c] == symbol }) {
				return field
			}
		}
		return null
	}
}

class TicTacToeMatchController private constructor(
	val owner: MinigameController,
	val mode: GameMode,
) {
	private val localBoard = TicTacToeBoard()
	var multiplayerMatch: MinigameMatch? = null
		private set
	var pendingMove: PendingMove? = null
		private set
	var leaveRequested: Boolean = false
		private set
	var aiThinkingUntil: Long = 0L
		private set
	var localWinner: String? = null
		private set
	var localDraw: Boolean = false
		private set

	val board: List<String>
		get() {
			val confirmedBoard = multiplayerMatch?.state?.board ?: return localBoard.cells
			val pending = pendingMove ?: return confirmedBoard
			return confirmedBoard.toMutableList().apply {
				if (pending.fieldIndex in indices && this[pending.fieldIndex].isBlank()) {
					this[pending.fieldIndex] = localSymbol()
				}
			}
		}
	val winningCombination: List<Int>
		get() = multiplayerMatch?.state?.winningCombination ?: localBoard.winningCombination()
	val finished: Boolean
		get() = if (mode == GameMode.MULTIPLAYER) multiplayerMatch?.status?.let { it != "ACTIVE" } ?: false else localWinner != null || localDraw

	fun updateMatch(match: MinigameMatch) {
		val current = multiplayerMatch
		if (current != null && (current.matchId != match.matchId || match.revision < current.revision)) return
		multiplayerMatch = match
		val pending = pendingMove
		if (pending != null && (
				match.acceptedRequestId == pending.requestId ||
					match.status != "ACTIVE"
				)
		) {
			pendingMove = null
		}
	}

	fun clickField(index: Int) {
		if (!canClickField(index)) return
		if (mode == GameMode.MULTIPLAYER) {
			val match = multiplayerMatch ?: return
			val pending = PendingMove(index, UUID.randomUUID().toString(), match.revision)
			pendingMove = pending
			owner.submitMove(match.matchId, index, pending.requestId, pending.expectedRevision)
			return
		}
		localBoard.cells[index] = "X"
		evaluateLocal()
		if (!finished) aiThinkingUntil = System.currentTimeMillis() + 450L
	}

	fun canClickField(index: Int): Boolean {
		if (index !in 0..8 || finished || leaveRequested || board[index].isNotBlank()) return false
		if (mode == GameMode.AI) return aiThinkingUntil == 0L
		val match = multiplayerMatch ?: return false
		return pendingMove == null && match.status == "ACTIVE" && match.state.currentTurnUuid == owner.localUuid()
	}

	fun rejectPendingMove(requestId: String, authoritativeMatch: MinigameMatch?): Boolean {
		val matchesPending = pendingMove?.requestId == requestId
		if (authoritativeMatch != null) updateMatch(authoritativeMatch)
		if (matchesPending) pendingMove = null
		return matchesPending
	}

	fun tick() {
		if (mode != GameMode.AI || aiThinkingUntil == 0L || System.currentTimeMillis() < aiThinkingUntil || finished) return
		aiThinkingUntil = 0L
		TicTacToeAI.chooseMove(localBoard.cells)?.let { localBoard.cells[it] = "O" }
		evaluateLocal()
	}

	fun rematch() {
		if (mode == GameMode.AI) {
			localBoard.reset()
			localWinner = null
			localDraw = false
			aiThinkingUntil = 0L
		} else {
			multiplayerMatch?.let { owner.requestRematch(it.matchId) }
		}
	}

	fun hasRequestedRematch(): Boolean =
		mode == GameMode.MULTIPLAYER && owner.localUuid() in (multiplayerMatch?.state?.rematchVotes ?: emptyList())

	fun opponentRequestedRematch(): Boolean {
		val match = multiplayerMatch ?: return false
		if (mode != GameMode.MULTIPLAYER || hasRequestedRematch()) return false
		val opponentUuid = if (match.playerOneUuid == owner.localUuid()) match.playerTwoUuid else match.playerOneUuid
		return opponentUuid in match.state.rematchVotes
	}

	fun leave() {
		if (finished) {
			owner.clearActiveGame()
			owner.openMainMenu()
			return
		}
		if (mode == GameMode.MULTIPLAYER) {
			if (leaveRequested) return
			leaveRequested = true
			multiplayerMatch?.let { owner.leaveMatch(it.matchId) }
		} else {
			owner.clearActiveGame()
			owner.openMainMenu()
		}
	}

	fun onLeaveFailed() {
		leaveRequested = false
	}

	fun visualState(): TicTacToeVisualState {
		if (mode == GameMode.AI) {
			return when {
				localWinner == "X" -> TicTacToeVisualState.won()
				localWinner == "O" -> TicTacToeVisualState.lost()
				localDraw -> TicTacToeVisualState.draw()
				aiThinkingUntil > 0L -> TicTacToeVisualState.aiTurn()
				else -> TicTacToeVisualState.localTurn()
			}
		}
		val match = multiplayerMatch ?: return TicTacToeVisualState.waiting("Connecting...")
		return when {
			match.state.winnerUuid == owner.localUuid() -> TicTacToeVisualState.won()
			match.state.winnerUuid != null -> TicTacToeVisualState.lost()
			match.state.isDraw -> TicTacToeVisualState.draw()
			match.status == "CANCELLED" -> TicTacToeVisualState.waiting("Match cancelled")
			pendingMove != null -> TicTacToeVisualState.pending()
			match.state.currentTurnUuid == owner.localUuid() -> TicTacToeVisualState.localTurn()
			else -> TicTacToeVisualState.opponentTurn(opponentUsername(match))
		}
	}

	private fun localSymbol(): String {
		val match = multiplayerMatch ?: return ""
		return if (match.state.playerXUuid == owner.localUuid()) "X" else "O"
	}

	private fun opponentUsername(match: MinigameMatch): String =
		if (match.playerOneUuid == owner.localUuid()) match.playerTwoUsername else match.playerOneUsername

	private fun evaluateLocal() {
		val winning = localBoard.winningCombination()
		if (winning.isNotEmpty()) {
			localWinner = localBoard.cells[winning.first()]
		} else if (localBoard.isDraw()) {
			localDraw = true
		}
	}

	companion object {
		fun ai(owner: MinigameController): TicTacToeMatchController = TicTacToeMatchController(owner, GameMode.AI)
		fun multiplayer(owner: MinigameController, match: MinigameMatch): TicTacToeMatchController =
			TicTacToeMatchController(owner, GameMode.MULTIPLAYER).apply { updateMatch(match) }
	}
}

data class TicTacToeVisualState(
	val backgroundItem: net.minecraft.item.Item,
	val backgroundName: String,
	val statusItem: net.minecraft.item.Item,
	val statusName: String,
	val statusLore: List<String>,
) {
	companion object {
		fun localTurn() = TicTacToeVisualState(
			Items.LIME_STAINED_GLASS_PANE,
			"Your turn",
			Items.LIME_DYE,
			"Your turn",
			listOf("Choose an empty field."),
		)

		fun opponentTurn(username: String) = TicTacToeVisualState(
			Items.RED_STAINED_GLASS_PANE,
			"$username's turn",
			Items.RED_DYE,
			"$username's turn",
			listOf("Waiting for your opponent's move."),
		)

		fun pending() = TicTacToeVisualState(
			Items.YELLOW_STAINED_GLASS_PANE,
			"Confirming move",
			Items.CLOCK,
			"Confirming move",
			listOf("Please wait a moment."),
		)

		fun aiTurn() = TicTacToeVisualState(
			Items.YELLOW_STAINED_GLASS_PANE,
			"AI's turn",
			Items.CLOCK,
			"AI's turn",
			listOf("Please wait a moment."),
		)

		fun won() = finished(Items.GREEN_STAINED_GLASS_PANE, Items.LIME_DYE, "You won!")
		fun lost() = finished(Items.RED_STAINED_GLASS_PANE, Items.RED_DYE, "You lost!")
		fun draw() = finished(Items.YELLOW_STAINED_GLASS_PANE, Items.GRAY_DYE, "Draw!")
		fun waiting(status: String) = finished(Items.GRAY_STAINED_GLASS_PANE, Items.CLOCK, status)

		private fun finished(background: net.minecraft.item.Item, statusItem: net.minecraft.item.Item, status: String) =
			TicTacToeVisualState(background, status, statusItem, status, emptyList())
	}
}

class TicTacToeScreen(
	private val game: TicTacToeMatchController,
) : ChestLikeScreen(Text.literal(titleFor(game))) {
	override fun slots(): Map<Int, MenuSlot> {
		val entries = mutableMapOf<Int, MenuSlot>()
		val visualState = game.visualState()
		DECORATIVE_SLOTS.forEach { slot ->
			entries[slot] = MenuSlot(item = visualState.backgroundItem, name = visualState.backgroundName)
		}
		BOARD_SLOTS.forEachIndexed { index, slot ->
			val symbol = game.board[index]
			val winning = index in game.winningCombination
			entries[slot] = MenuSlot(
				item = when {
					winning -> Items.LIME_CONCRETE
					symbol == "X" -> Items.RED_CONCRETE
					symbol == "O" -> Items.BLUE_CONCRETE
					else -> Items.LIGHT_GRAY_STAINED_GLASS_PANE
				},
				name = if (symbol.isBlank()) "Empty field" else symbol,
				lore = if (winning) listOf("Winning field") else emptyList(),
				enabled = game.canClickField(index),
				highlighted = winning,
				action = { game.clickField(index) },
			)
		}
		entries[8] = MenuSlot(item = visualState.statusItem, name = visualState.statusName, lore = visualState.statusLore)
		entries[18] = MenuSlot(
			item = if (game.finished) Items.ARROW else Items.BARRIER,
			name = if (game.finished) "Back to mini-game menu" else "Leave match",
			enabled = !game.leaveRequested,
			action = { if (game.finished) { game.owner.clearActiveGame(); game.owner.openMainMenu() } else game.leave() },
		)
		entries[26] = rematchSlot()
		return entries
	}

	override fun handledScreenTick() {
		game.tick()
	}

	override fun close() {
		if (!game.finished) {
			game.owner.feedback("The match is still running. Open it again with /game.")
		}
		super.close()
	}

	fun belongsTo(matchId: String): Boolean = game.multiplayerMatch?.matchId == matchId

	companion object {
		private val BOARD_SLOTS = listOf(3, 4, 5, 12, 13, 14, 21, 22, 23)
		private val DECORATIVE_SLOTS = (0 until 27).filter { it !in BOARD_SLOTS && it !in setOf(8, 18, 26) }

		private fun titleFor(game: TicTacToeMatchController): String {
			val match = game.multiplayerMatch
			return if (match == null) "Tic-Tac-Toe: vs AI" else "Tic-Tac-Toe: ${match.playerOneUsername} vs ${match.playerTwoUsername}"
		}
	}

	private fun rematchSlot(): MenuSlot =
		when {
			!game.finished -> MenuSlot(
				item = Items.GRAY_DYE,
				name = "Not available yet",
				enabled = false,
			)
			game.mode == GameMode.AI -> MenuSlot(
				item = Items.SLIME_BALL,
				name = "Play again",
				enabled = true,
				action = { game.rematch() },
			)
			game.hasRequestedRematch() -> MenuSlot(
				item = Items.CLOCK,
				name = "Rematch request sent",
				lore = listOf("Waiting for your opponent to accept."),
				enabled = false,
			)
			else -> MenuSlot(
				item = Items.SLIME_BALL,
				name = "Request rematch",
				lore = if (game.opponentRequestedRematch()) {
					listOf("Your opponent wants a rematch.")
				} else {
					listOf("Ask your opponent to play again.")
				},
				enabled = true,
				action = { game.rematch() },
			)
		}
}
