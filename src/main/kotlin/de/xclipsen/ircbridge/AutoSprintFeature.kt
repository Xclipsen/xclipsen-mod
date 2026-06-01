package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.option.KeyBinding
import net.minecraft.registry.tag.FluidTags
import net.minecraft.util.math.BlockPos

object AutoSprintFeature {
	private var wasBlockingSprintKey = false

	fun onTick(client: MinecraftClient) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		val world = client.world ?: return
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

		val client = MinecraftClient.getInstance()
		val world = client.world ?: return original
		val player = client.player ?: return original
		if (config.autoSprintDisableWhenFullySubmerged && isHeadAndFeetUnderwater(player, world)) {
			return false
		}

		return original || config.autoSprintModuleEnabled
	}

	private fun blockSprintKey(client: MinecraftClient, player: ClientPlayerEntity) {
		client.options.sprintKey.setPressed(false)
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
		KeyBinding.updatePressedStates()
	}

	private fun isHeadAndFeetUnderwater(player: ClientPlayerEntity, world: net.minecraft.world.World): Boolean {
		val feet = BlockPos.ofFloored(player.x, player.y + 0.1, player.z)
		val head = BlockPos.ofFloored(player.x, player.eyeY, player.z)
		return world.getFluidState(feet).isIn(FluidTags.WATER) &&
			world.getFluidState(head).isIn(FluidTags.WATER)
	}
}
