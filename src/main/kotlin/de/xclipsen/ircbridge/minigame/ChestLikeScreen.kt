package de.xclipsen.ircbridge.minigame

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting

abstract class ChestLikeScreen(
	private val parent: Screen?,
	title: Component,
) : ContainerScreen(createHandler(), playerInventory(), title) {
	protected data class MenuSlot(
		val item: Item = Items.GRAY_STAINED_GLASS_PANE,
		val name: String = "",
		val lore: List<String> = emptyList(),
		val enabled: Boolean = true,
		val highlighted: Boolean = false,
		val action: (Int) -> Unit = {},
	)

	private val menuInventory: SimpleContainer
		get() = menu.container as SimpleContainer

	protected abstract fun slots(): Map<Int, MenuSlot>

	override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		refreshMenuInventory()
		super.extractRenderState(context, mouseX, mouseY, delta)
	}

	override fun slotClicked(slot: Slot, slotId: Int, button: Int, actionType: ContainerInput) {
		if (slotId !in 0 until MENU_SIZE || actionType != ContainerInput.PICKUP || button !in LEFT_BUTTON..RIGHT_BUTTON) {
			return
		}

		val entry = slots()[slotId] ?: return
		if (entry.enabled) {
			entry.action(button)
		}
	}

	override fun onClose() {
		minecraft.setScreen(parent)
	}

	fun parentScreen(): Screen? = parent

	override fun removed() {
		// This is a local-only container. Never close or mutate a server-side screen handler.
	}

	private fun refreshMenuInventory() {
		val configured = slots()
		for (slot in 0 until MENU_SIZE) {
			val entry = configured[slot] ?: MenuSlot()
			val current = menuInventory.getItem(slot)
			val updated = stackFor(entry)
			if (!ItemStack.matches(current, updated)) {
				menuInventory.setItem(slot, updated)
			}
		}
	}

	private fun stackFor(entry: MenuSlot): ItemStack {
		val stack = ItemStack(entry.item)
		if (entry.name.isNotBlank()) {
			stack.set(DataComponents.CUSTOM_NAME, Component.literal(entry.name).withStyle(if (entry.enabled) ChatFormatting.YELLOW else ChatFormatting.GRAY))
		}
		if (entry.lore.isNotEmpty()) {
			stack.set(DataComponents.LORE, ItemLore(entry.lore.map { Component.literal(it).withStyle(ChatFormatting.GRAY) }))
		}
		if (entry.highlighted) {
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
		}
		return stack
	}

	companion object {
		const val LEFT_BUTTON = 0
		const val RIGHT_BUTTON = 1
		private const val MENU_SIZE = 27
		private const val LOCAL_SYNC_ID = -24761

		private fun playerInventory() = requireNotNull(Minecraft.getInstance().player).inventory

		private fun createHandler(): ChestMenu =
			ChestMenu.threeRows(LOCAL_SYNC_ID, playerInventory(), SimpleContainer(MENU_SIZE))
	}
}
