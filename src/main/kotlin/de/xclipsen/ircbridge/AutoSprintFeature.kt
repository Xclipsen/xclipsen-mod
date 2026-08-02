package de.xclipsen.ircbridge

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.KeyMapping
import net.minecraft.tags.FluidTags
import net.minecraft.core.BlockPos

object AutoSprintFeature {
	private var wasBlockingSprintKey = false

	fun onTick(client: Minecraft) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		val world = client.level ?: return
		val player = client.player ?: return
		if (!config.autoSprintModuleEnabled) {
			clearSprintKeyBlock()
			return
		}

		if (config.autoSprintDisableWhenFullySubmerged && isHeadAndFeetUnderwater(player, world)) {
			blockSprintKey(client, player)
			return
		}
		clearSprintKeyBlock()
	}

	fun overrideSprintInput(original: Boolean): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return original
		if (!config.autoSprintModuleEnabled) {
			return original
		}

		val client = Minecraft.getInstance()
		val world = client.level ?: return original
		val player = client.player ?: return original
		if (config.autoSprintDisableWhenFullySubmerged && isHeadAndFeetUnderwater(player, world)) {
			return false
		}

		return original || config.autoSprintModuleEnabled
	}

	fun onWorldChange() = clearSprintKeyBlock()

	private fun blockSprintKey(client: Minecraft, player: LocalPlayer) {
		client.options.keySprint.setDown(false)
		wasBlockingSprintKey = true
		if (player.isSprinting) {
			player.setSprinting(false)
		}
	}

	private fun clearSprintKeyBlock() {
		if (!wasBlockingSprintKey) {
			return
		}
		wasBlockingSprintKey = false
		KeyMapping.setAll()
	}

	private fun isHeadAndFeetUnderwater(player: LocalPlayer, world: net.minecraft.world.level.Level): Boolean {
		val feet = BlockPos.containing(player.x, player.y + 0.1, player.z)
		val head = BlockPos.containing(player.x, player.eyeY, player.z)
		return world.getFluidState(feet).`is`(FluidTags.WATER) &&
			world.getFluidState(head).`is`(FluidTags.WATER)
	}
}
