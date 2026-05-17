package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.ItemStack

object InventoryPreviewHudElement : XclipsenHudElement(
	id = "inventory_preview",
	displayName = "Inventory Preview",
) {
	override fun isEnabled(config: BridgeConfig): Boolean = config.inventoryPreviewModuleEnabled

	override fun defaultX(context: DrawContext): Float {
		return (context.scaledWindowWidth - preferredWidth() - 20f).coerceAtLeast(20f)
	}

	override fun defaultY(context: DrawContext): Float = 20f

	override fun draw(context: DrawContext, example: Boolean): Pair<Float, Float> {
		val client = MinecraftClient.getInstance()
		val config = XclipsenIrcBridgeClient.instance?.config() ?: BridgeConfig()
		val player = client.player as? AbstractClientPlayerEntity
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
			context.drawTextWithShadow(client.textRenderer, "Preview", PADDING, panelHeight.toInt() - 10, TEXT_MUTED)
		}

		return panelWidth to panelHeight
	}

	private fun drawInventorySlots(context: DrawContext, player: AbstractClientPlayerEntity?, left: Int, top: Int) {
		for (row in 0 until 3) {
			for (column in 0 until 9) {
				val slotIndex = 9 + row * 9 + column
				drawSlot(context, left + column * SLOT_SIZE, top + row * SLOT_SIZE, player?.inventory?.getStack(slotIndex) ?: ItemStack.EMPTY)
			}
		}

		val hotbarTop = top + (3 * SLOT_SIZE) + HOTBAR_GAP
		for (column in 0 until 9) {
			drawSlot(context, left + column * SLOT_SIZE, hotbarTop, player?.inventory?.getStack(column) ?: ItemStack.EMPTY)
		}
	}

	private fun drawArmorSlots(context: DrawContext, player: AbstractClientPlayerEntity?, panelHeight: Int) {
		val armorTop = PADDING + ((panelHeight - (PADDING * 2) - ARMOR_HEIGHT) / 2).coerceAtLeast(0)
		val left = PADDING
		val stacks = listOf(
			player?.getEquippedStack(EquipmentSlot.HEAD) ?: ItemStack.EMPTY,
			player?.getEquippedStack(EquipmentSlot.CHEST) ?: ItemStack.EMPTY,
			player?.getEquippedStack(EquipmentSlot.LEGS) ?: ItemStack.EMPTY,
			player?.getEquippedStack(EquipmentSlot.FEET) ?: ItemStack.EMPTY,
		)

		for ((index, stack) in stacks.withIndex()) {
			drawSlot(context, left, armorTop + index * SLOT_SIZE, stack)
		}
	}

	private fun drawSlot(context: DrawContext, left: Int, top: Int, stack: ItemStack) {
		context.fill(left, top, left + SLOT_SIZE, top + SLOT_SIZE, SLOT_BACKGROUND)
		context.fill(left, top, left + SLOT_SIZE, top + 1, SLOT_BORDER)
		context.fill(left, top + SLOT_SIZE - 1, left + SLOT_SIZE, top + SLOT_SIZE, SLOT_BORDER)
		context.fill(left, top, left + 1, top + SLOT_SIZE, SLOT_BORDER)
		context.fill(left + SLOT_SIZE - 1, top, left + SLOT_SIZE, top + SLOT_SIZE, SLOT_BORDER)

		if (!stack.isEmpty) {
			context.drawItem(stack, left + 1, top + 1)
			context.drawStackOverlay(MinecraftClient.getInstance().textRenderer, stack, left + 1, top + 1)
		}
	}

	private fun preferredWidth(showArmor: Boolean = true): Float {
		val armorWidth = if (showArmor) ARMOR_BLOCK_WIDTH + SECTION_GAP else 0
		return (PADDING + armorWidth + GRID_WIDTH + PADDING).toFloat()
	}

	private fun preferredHeight(): Float {
		return (PADDING + (3 * SLOT_SIZE) + HOTBAR_GAP + SLOT_SIZE + PADDING).toFloat()
	}

	private const val SLOT_SIZE = 18
	private const val GRID_WIDTH = SLOT_SIZE * 9
	private const val HOTBAR_GAP = 4
	private const val PADDING = 8
	private const val SECTION_GAP = 8
	private const val ARMOR_BLOCK_WIDTH = SLOT_SIZE
	private const val ARMOR_HEIGHT = SLOT_SIZE * 4
	private const val SLOT_BACKGROUND = 0xAA121212.toInt()
	private const val SLOT_BORDER = 0x50FFFFFF
	private const val TEXT_MUTED = 0xFFA0A0A0.toInt()
}
