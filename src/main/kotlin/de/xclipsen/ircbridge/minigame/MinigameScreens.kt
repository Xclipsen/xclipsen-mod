package de.xclipsen.ircbridge.minigame

import net.minecraft.block.Blocks
import net.minecraft.block.entity.SignBlockEntity
import net.minecraft.block.entity.SignText
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.ingame.SignEditScreen
import net.minecraft.client.input.KeyInput
import net.minecraft.item.Items
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

class MinigameMainMenuScreen(
	private val controller: MinigameController,
) : ChestLikeScreen(Text.literal("Minigames")) {
	override fun slots(): Map<Int, MenuSlot> {
		val entries = mutableMapOf<Int, MenuSlot>()
		MinigameRegistry.all().forEachIndexed { index, game ->
			val slot = 13 + index
			entries[slot] = MenuSlot(
				item = game.icon,
				name = game.displayName,
				lore = game.description + "Click to open",
				action = { game.openModeMenu(controller) },
			)
		}
		if (controller.hasActiveGame()) {
			entries[4] = MenuSlot(
				item = Items.LIME_DYE,
				name = "Continue current match",
				lore = listOf("Click to open"),
				action = { controller.openActiveMatch() },
			)
		}
		if (controller.invites().isNotEmpty()) {
			entries[22] = MenuSlot(
				item = Items.PAPER,
				name = "Pending game invites",
				lore = listOf("${controller.invites().size} invite(s)"),
				action = { client?.setScreen(InviteListScreen(controller)) },
			)
		}
		entries[26] = MenuSlot(item = Items.BARRIER, name = "Close", action = { close() })
		return entries
	}
}

class GameModeSelectionScreen(
	private val controller: MinigameController,
	private val minigame: Minigame,
) : ChestLikeScreen(Text.literal("${minigame.displayName}: Choose mode")) {
	override fun slots(): Map<Int, MenuSlot> = mapOf(
		11 to MenuSlot(
			item = Items.REDSTONE,
			name = "Play against AI",
			lore = listOf("Start a local game immediately.", "Click to play"),
			enabled = GameMode.AI in minigame.supportedModes,
			action = { minigame.startAiGame(controller) },
		),
		15 to MenuSlot(
			item = Items.PLAYER_HEAD,
			name = "Play against a player",
			lore = listOf("Challenge a player who has the mod installed.", "Click to enter a name"),
			enabled = GameMode.MULTIPLAYER in minigame.supportedModes,
			action = { client?.setScreen(PlayerNameInputSignScreen(this, controller, minigame)) },
		),
		18 to MenuSlot(item = Items.ARROW, name = "Back", action = { controller.openMainMenu() }),
		26 to MenuSlot(item = Items.BARRIER, name = "Close", action = { close() }),
	)
}

class InviteListScreen(
	private val controller: MinigameController,
) : ChestLikeScreen(Text.literal("Pending game invites")) {
	override fun slots(): Map<Int, MenuSlot> {
		val entries = mutableMapOf<Int, MenuSlot>()
		controller.invites().take(18).forEachIndexed { index, invite ->
			val seconds = ((invite.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)
			entries[index] = MenuSlot(
				item = Items.PAPER,
				name = "${invite.senderUsername}: ${invite.minigameId}",
				lore = listOf("${seconds}s left", "Left click: accept", "Right click: deny"),
				action = { button ->
					if (button == LEFT_BUTTON) controller.acceptInvite(invite.inviteId) else if (button == RIGHT_BUTTON) controller.denyInvite(invite.inviteId)
				},
			)
		}
		entries[18] = MenuSlot(item = Items.ARROW, name = "Back", action = { controller.openMainMenu() })
		entries[26] = MenuSlot(item = Items.BARRIER, name = "Close", action = { close() })
		return entries
	}
}

class PlayerNameInputSignScreen(
	private val parent: Screen,
	private val controller: MinigameController,
	private val minigame: Minigame,
) : SignEditScreen(createSign(), true, false) {
	private var handledRemoval = false
	private var cancelled = false

	override fun init() {
		super.init()
		super.keyPressed(KeyInput(GLFW.GLFW_KEY_DOWN, 0, 0))
	}

	override fun removed() {
		if (handledRemoval) return
		handledRemoval = true
		if (cancelled) return
		val username = blockEntity.frontText.getMessage(1, false).string.trim()
		val error = when {
			!USERNAME_PATTERN.matches(username) -> "Invalid player name."
			username.equals(client?.session?.username, ignoreCase = true) -> "You cannot challenge yourself."
			else -> ""
		}
		client?.execute {
			client?.setScreen(parent)
			if (error.isBlank()) {
				minigame.startMultiplayerGame(controller, username)
			} else {
				controller.feedback(error, error = true)
			}
		}
		// Deliberately do not call super.removed(): Vanilla sends UpdateSignC2SPacket there.
	}

	override fun close() {
		cancelled = true
		client?.setScreen(parent)
	}

	private companion object {
		private val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")

		private fun createSign(): SignBlockEntity {
			val client = net.minecraft.client.MinecraftClient.getInstance()
			val player = requireNotNull(client.player)
			return LocalSignBlockEntity(
				player.blockPos,
				SignText()
					.withMessage(0, Text.literal("Player name"))
					.withMessage(1, Text.empty())
					.withMessage(2, Text.literal("enter"))
					.withMessage(3, Text.empty()),
			)
		}
	}

	private class LocalSignBlockEntity(
		pos: net.minecraft.util.math.BlockPos,
		private var localFrontText: SignText,
	) : SignBlockEntity(pos, Blocks.OAK_SIGN.defaultState) {
		private var localBackText = SignText()

		override fun getText(front: Boolean): SignText = if (front) localFrontText else localBackText

		override fun getFrontText(): SignText = localFrontText

		override fun getBackText(): SignText = localBackText

		override fun setText(text: SignText, front: Boolean): Boolean {
			if (front) localFrontText = text else localBackText = text
			return true
		}

		override fun isPlayerTooFarToEdit(uuid: java.util.UUID): Boolean = false
	}
}
