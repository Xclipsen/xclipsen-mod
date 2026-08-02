package de.xclipsen.ircbridge

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack

object InventoryPreviewHudElement : XclipsenHudElement(
	id = "inventory_preview",
	displayName = "Container Preview",
) {
	override fun isEnabled(config: BridgeConfig): Boolean = config.inventoryPreviewModuleEnabled

	override fun defaultX(context: GuiGraphicsExtractor): Float {
		return (context.guiWidth() - preferredWidth() - 20f).coerceAtLeast(20f)
	}

	override fun defaultY(context: GuiGraphicsExtractor): Float = 20f

	override fun draw(context: GuiGraphicsExtractor, example: Boolean): Tuple<Float, Float> {
		val client = Minecraft.getInstance()
		val config = XclipsenIrcBridgeClient.instance?.config() ?: BridgeConfig()
		val player = client.player as? AbstractClientPlayer
		val showArmor = config.inventoryPreviewShowArmor
		val panelWidth = preferredWidth(showArmor)
		val panelHeight = preferredHeight()
		val gridLeft = PADDING + if (showArmor) ARMOR_BLOCK_WIDTH + SECTION_GAP else 0
		val gridTop = PADDING

		drawInventorySlots(context, player, gridLeft, gridTop)
		if (showArmor) {
			drawArmorSlots(context, player, panelHeight.toInt())
		}

		if (example && player == null) {
			context.text(client.font, "Preview", PADDING, panelHeight.toInt() - 10, XclipsenUiTokens.TEXT_MUTED, true)
		}

		return panelWidth to panelHeight
	}

	private fun drawInventorySlots(context: GuiGraphicsExtractor, player: AbstractClientPlayer?, left: Int, top: Int) {
		for (row in 0 until 3) {
			for (column in 0 until 9) {
				val slotIndex = 9 + row * 9 + column
				drawSlot(context, left + column * SLOT_SIZE, top + row * SLOT_SIZE, player?.inventory?.getItem(slotIndex) ?: ItemStack.EMPTY)
			}
		}

		val hotbarTop = top + (3 * SLOT_SIZE) + HOTBAR_GAP
		for (column in 0 until 9) {
			drawSlot(context, left + column * SLOT_SIZE, hotbarTop, player?.inventory?.getItem(column) ?: ItemStack.EMPTY)
		}
	}

	private fun drawArmorSlots(context: GuiGraphicsExtractor, player: AbstractClientPlayer?, panelHeight: Int) {
		val armorTop = PADDING + ((panelHeight - (PADDING * 2) - ARMOR_HEIGHT) / 2).coerceAtLeast(0)
		val stacks = listOf(
			player?.getItemBySlot(EquipmentSlot.HEAD) ?: ItemStack.EMPTY,
			player?.getItemBySlot(EquipmentSlot.CHEST) ?: ItemStack.EMPTY,
			player?.getItemBySlot(EquipmentSlot.LEGS) ?: ItemStack.EMPTY,
			player?.getItemBySlot(EquipmentSlot.FEET) ?: ItemStack.EMPTY,
		)

		for ((index, stack) in stacks.withIndex()) {
			drawSlot(context, PADDING, armorTop + index * SLOT_SIZE, stack)
		}
	}

	private fun drawSlot(context: GuiGraphicsExtractor, left: Int, top: Int, stack: ItemStack) {
		context.fill(left, top, left + SLOT_SIZE, top + SLOT_SIZE, XclipsenUiTokens.SURFACE_PANEL_BODY)
		context.fill(left, top, left + SLOT_SIZE, top + 1, XclipsenUiTokens.BORDER_SUBTLE)
		context.fill(left, top + SLOT_SIZE - 1, left + SLOT_SIZE, top + SLOT_SIZE, XclipsenUiTokens.BORDER_SUBTLE)
		context.fill(left, top, left + 1, top + SLOT_SIZE, XclipsenUiTokens.BORDER_SUBTLE)
		context.fill(left + SLOT_SIZE - 1, top, left + SLOT_SIZE, top + SLOT_SIZE, XclipsenUiTokens.BORDER_SUBTLE)

		if (!stack.isEmpty) {
			context.item(stack, left + 1, top + 1)
			context.itemDecorations(Minecraft.getInstance().font, stack, left + 1, top + 1)
		}
	}

	private fun preferredWidth(showArmor: Boolean = true): Float {
		val armorWidth = if (showArmor) ARMOR_BLOCK_WIDTH + SECTION_GAP else 0
		return (PADDING + armorWidth + GRID_WIDTH + PADDING).toFloat()
	}

	private fun preferredHeight(): Float =
		(PADDING + (3 * SLOT_SIZE) + HOTBAR_GAP + SLOT_SIZE + PADDING).toFloat()

	private const val SLOT_SIZE = 18
	private const val GRID_WIDTH = SLOT_SIZE * 9
	private const val HOTBAR_GAP = 4
	private const val PADDING = 8
	private const val SECTION_GAP = 8
	private const val ARMOR_BLOCK_WIDTH = SLOT_SIZE
	private const val ARMOR_HEIGHT = SLOT_SIZE * 4
}
