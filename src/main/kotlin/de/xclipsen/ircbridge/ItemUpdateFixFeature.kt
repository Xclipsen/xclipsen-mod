package de.xclipsen.ircbridge

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

object ItemUpdateFixFeature {
	fun isEnabled(): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		return config.itemUpdateFixModuleEnabled && LocationTracker.isOnHypixelSkyBlock
	}

	fun representsSameItem(first: ItemStack, second: ItemStack): Boolean {
		if (!isEnabled() || first.isEmpty || second.isEmpty || !ItemStack.isSameItem(first, second)) return false

		val firstUuid = itemUuid(first)
		if (firstUuid.isEmpty() || firstUuid != itemUuid(second)) return false

		val stableFirst = first.copy()
		val stableSecond = second.copy()
		for (component in IGNORED_COMPONENTS) {
			stableFirst.remove(component)
			stableSecond.remove(component)
		}
		return ItemStack.isSameItemSameComponents(stableFirst, stableSecond)
	}

	fun statusLine(): String =
		"enabled=${XclipsenIrcBridgeClient.instance?.config()?.itemUpdateFixModuleEnabled == true}, " +
			"active=${isEnabled()}, skyblock=${LocationTracker.isOnHypixelSkyBlock}, packets=none"

	private fun itemUuid(stack: ItemStack): String =
		stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getString("uuid")?.orElse("")?.trim().orEmpty()

	private val IGNORED_COMPONENTS = listOf(
		DataComponents.DAMAGE,
		DataComponents.LORE,
		DataComponents.CUSTOM_DATA,
	)
}
