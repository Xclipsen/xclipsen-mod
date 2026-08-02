package de.xclipsen.ircbridge.minigame

import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.SignEditScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.world.item.Items
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

class MinigameMainMenuScreen(
	parent: Screen?,
	private val controller: MinigameController,
) : ChestLikeScreen(parent, Component.literal("Minigames")) {
	override fun slots(): Map<Int, MenuSlot> {
		val entries = mutableMapOf<Int, MenuSlot>()
		MinigameRegistry.all().forEachIndexed { index, game ->
			val slot = 13 + index
			entries[slot] = MenuSlot(
				item = game.icon,
				name = game.displayName,
				lore = game.description + "Click to open",
				action = { game.openModeMenu(this, controller) },
			)
		}
		if (controller.hasActiveGame()) {
			entries[4] = MenuSlot(
				item = Items.LIME_DYE,
				name = "Continue current match",
				lore = listOf("Click to open"),
				action = { controller.openActiveMatch(this) },
			)
		}
		if (controller.invites().isNotEmpty()) {
			entries[22] = MenuSlot(
				item = Items.PAPER,
				name = "Pending game invites",
				lore = listOf("${controller.invites().size} invite(s)"),
				action = { minecraft.setScreen(InviteListScreen(this, controller)) },
			)
		}
		entries[26] = MenuSlot(item = Items.BARRIER, name = "Close", action = { onClose() })
		return entries
	}
}

class GameModeSelectionScreen(
	private val parent: Screen,
	private val controller: MinigameController,
	private val minigame: Minigame,
) : ChestLikeScreen(parent, Component.literal("${minigame.displayName}: Choose mode")) {
	override fun slots(): Map<Int, MenuSlot> = mapOf(
		11 to MenuSlot(
			item = Items.REDSTONE,
			name = "Play against AI",
			lore = listOf("Start a local game immediately.", "Click to play"),
			enabled = GameType.AI in minigame.supportedModes,
			action = { minigame.startAiGame(controller) },
		),
		15 to MenuSlot(
			item = Items.PLAYER_HEAD,
			name = "Play against a player",
			lore = listOf("Challenge a player who has the mod installed.", "Click to enter a name"),
			enabled = GameType.MULTIPLAYER in minigame.supportedModes,
			action = { minecraft.setScreen(PlayerNameInputSignScreen(this, controller, minigame)) },
		),
		18 to MenuSlot(item = Items.ARROW, name = "Back", action = { minecraft.setScreen(parent) }),
		26 to MenuSlot(item = Items.BARRIER, name = "Close", action = { onClose() }),
	)
}

class InviteListScreen(
	private val parent: Screen,
	private val controller: MinigameController,
) : ChestLikeScreen(parent, Component.literal("Pending game invites")) {
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
		entries[18] = MenuSlot(item = Items.ARROW, name = "Back", action = { minecraft.setScreen(parent) })
		entries[26] = MenuSlot(item = Items.BARRIER, name = "Close", action = { onClose() })
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
		super.keyPressed(KeyEvent(GLFW.GLFW_KEY_DOWN, 0, 0))
	}

	override fun removed() {
		try {
			if (handledRemoval) return
			handledRemoval = true
			if (cancelled) return
			val username = sign.frontText.getMessage(1, false).string.trim()
			val error = when {
				!USERNAME_PATTERN.matches(username) -> "Invalid player name."
				username.equals(minecraft.user.name, ignoreCase = true) -> "You cannot challenge yourself."
				else -> ""
			}
			minecraft.execute {
				minecraft.setScreen(parent)
				if (error.isBlank()) {
					minigame.startMultiplayerGame(controller, username)
				} else {
					controller.feedback(error, error = true)
				}
			}
		} finally {
			minecraft.textInputManager().stopTextInput()
		}
		// Deliberately do not call super.removed(): Vanilla sends ServerboundSignUpdatePacket there.
	}

	override fun onClose() {
		cancelled = true
		minecraft.setScreen(parent)
	}

	private companion object {
		private val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")

		private fun createSign(): SignBlockEntity {
			val client = net.minecraft.client.Minecraft.getInstance()
			val player = requireNotNull(client.player)
			return LocalSignBlockEntity(
				player.blockPosition(),
				SignText()
					.setMessage(0, Component.literal("Player name"))
					.setMessage(1, Component.empty())
					.setMessage(2, Component.literal("enter"))
					.setMessage(3, Component.empty()),
			)
		}
	}

	private class LocalSignBlockEntity(
		pos: net.minecraft.core.BlockPos,
		private var localFrontText: SignText,
	) : SignBlockEntity(pos, Blocks.OAK_SIGN.defaultBlockState()) {
		private var localBackText = SignText()

		override fun getText(front: Boolean): SignText = if (front) localFrontText else localBackText

		override fun getFrontText(): SignText = localFrontText

		override fun getBackText(): SignText = localBackText

		override fun setText(text: SignText, front: Boolean): Boolean {
			if (front) localFrontText = text else localBackText = text
			return true
		}

		override fun playerIsTooFarAwayToEdit(uuid: java.util.UUID): Boolean = false
	}
}
