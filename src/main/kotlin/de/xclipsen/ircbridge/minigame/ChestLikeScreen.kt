package de.xclipsen.ircbridge.minigame

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.LoreComponent
import net.minecraft.inventory.SimpleInventory
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.screen.GenericContainerScreenHandler
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text
import net.minecraft.util.Formatting

abstract class ChestLikeScreen(title: Text) : GenericContainerScreen(createHandler(), playerInventory(), title) {
	protected data class MenuSlot(
		val item: Item = Items.GRAY_STAINED_GLASS_PANE,
		val name: String = "",
		val lore: List<String> = emptyList(),
		val enabled: Boolean = true,
		val highlighted: Boolean = false,
		val action: (Int) -> Unit = {},
	)

	private val menuInventory: SimpleInventory
		get() = screenHandler.inventory as SimpleInventory

	protected abstract fun slots(): Map<Int, MenuSlot>

	override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
		refreshMenuInventory()
		super.render(context, mouseX, mouseY, delta)
	}

	override fun onMouseClick(slot: Slot?, slotId: Int, button: Int, actionType: SlotActionType) {
		if (slotId !in 0 until MENU_SIZE || actionType != SlotActionType.PICKUP || button !in LEFT_BUTTON..RIGHT_BUTTON) {
			return
		}

		val entry = slots()[slotId] ?: return
		if (entry.enabled) {
			entry.action(button)
		}
	}

	override fun close() {
		client?.setScreen(null)
	}

	override fun removed() {
		// This is a local-only container. Never close or mutate a server-side screen handler.
	}

	private fun refreshMenuInventory() {
		val configured = slots()
		for (slot in 0 until MENU_SIZE) {
			val entry = configured[slot] ?: MenuSlot()
			val current = menuInventory.getStack(slot)
			val updated = stackFor(entry)
			if (!ItemStack.areEqual(current, updated)) {
				menuInventory.setStack(slot, updated)
			}
		}
	}

	private fun stackFor(entry: MenuSlot): ItemStack {
		val stack = ItemStack(entry.item)
		if (entry.name.isNotBlank()) {
			stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(entry.name).formatted(if (entry.enabled) Formatting.YELLOW else Formatting.GRAY))
		}
		if (entry.lore.isNotEmpty()) {
			stack.set(DataComponentTypes.LORE, LoreComponent(entry.lore.map { Text.literal(it).formatted(Formatting.GRAY) }))
		}
		if (entry.highlighted) {
			stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
		}
		return stack
	}

	companion object {
		const val LEFT_BUTTON = 0
		const val RIGHT_BUTTON = 1
		private const val MENU_SIZE = 27
		private const val LOCAL_SYNC_ID = -24761

		private fun playerInventory() = requireNotNull(MinecraftClient.getInstance().player).inventory

		private fun createHandler(): GenericContainerScreenHandler =
			GenericContainerScreenHandler.createGeneric9x3(LOCAL_SYNC_ID, playerInventory(), SimpleInventory(MENU_SIZE))
	}
}
