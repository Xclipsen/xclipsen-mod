package de.xclipsen.ircbridge

import de.xclipsen.ircbridge.mixin.HandledScreenAccessor
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import org.slf4j.LoggerFactory
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

object ExperimentationTableFeature {
	private const val MODE_SLOT = 49
	private val unknownSuperpairsClickPattern = Regex("""(?:§.)+(?:\?|(?:MouseButtonEvent a(?: seco)?n[dy]|Next) button(?: is instantly rewarded)?!?)""")
	private val LOGGER = LoggerFactory.getLogger("xclipsen_experimentation")

	private var handler: ExperimentHandler? = null
	private var lastClick = 0L
	private val superpairsVisibility = SuperpairsVisibility()

	fun init() {
		ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
			if (screen !is ContainerScreen) {
				reset()
				return@register
			}

			val title = screen.title.string
			handler = when {
				title.startsWith("Chronomatron (") -> ChronomatronHandler()
				title.startsWith("Ultrasequencer (") -> UltrasequencerHandler()
				else -> null
			}
			if (isSuperpairsRound(title)) {
				debug { "superpairs init title='$title'" }
				superpairsVisibility.reset()
			}

			ScreenMouseEvents.allowMouseClick(screen).register { currentScreen, _ ->
				if (!isEnabled()) {
					return@register true
				}
				currentScreen !is ContainerScreen || handler == null
			}

			ScreenMouseEvents.beforeMouseClick(screen).register { currentScreen, click ->
				if (currentScreen !is ContainerScreen || !isEnabled() || !isSuperpairsRound(currentScreen.title.string)) {
					return@register
				}

				val slotIndex = findSlotAt(currentScreen, click.x().toInt(), click.y().toInt()) ?: return@register
				superpairsVisibility.onSlotClick(currentScreen, slotIndex)
			}
		}
	}

	fun onTick(client: Minecraft) {
		if (!isEnabled()) {
			reset()
			return
		}

		val screen = client.screen as? ContainerScreen ?: run {
			handler = null
			superpairsVisibility.reset()
			return
		}

		if (!isSuperpairsRound(screen.title.string)) {
			superpairsVisibility.reset()
		}

		val handler = handler ?: return
		val now = System.currentTimeMillis()
		if (now - lastClick < delay()) {
			return
		}

		handler.nextClick()?.let { slotId ->
			guiClick(screen.menu.containerId, slotId, 2, ContainerInput.CLONE)
			lastClick = now
		}

		if (!handler.shouldClose(autoCloseEnabled())) {
			return
		}

		client.player?.closeContainer()
		this.handler = null
	}

	fun onSlotUpdate(screenHandler: AbstractContainerMenu) {
		if (!isEnabled()) {
			return
		}

		val screen = Minecraft.getInstance().screen as? ContainerScreen ?: return
		if (screen.menu !== screenHandler) {
			return
		}

		val title = screen.title.string

		when {
			handler != null -> handler?.onSlotUpdate(screenHandler)
			isSuperpairsRound(title) -> superpairsVisibility.onInventoryUpdate(screen)
		}
	}

	fun onSlotStackChanged(screenHandler: AbstractContainerMenu, slotIndex: Int, stack: ItemStack) {
		if (!isEnabled()) {
			return
		}

		val screen = Minecraft.getInstance().screen as? ContainerScreen ?: return
		if (screen.menu !== screenHandler || !isSuperpairsRound(screen.title.string)) {
			return
		}

		superpairsVisibility.onSlotStackChanged(screen, slotIndex, stack)
	}

	@JvmStatic
	fun replaceSuperpairsItem(screen: AbstractContainerScreen<*>, slot: Slot, original: ItemStack): ItemStack {
		val genericScreen = screen as? ContainerScreen ?: return original
		if (slot.container is Inventory) {
			return original
		}
		return superpairsVisibility.replacementFor(genericScreen, slot.index, original) ?: original
	}

	private fun reset() {
		handler = null
		lastClick = 0L
		superpairsVisibility.reset()
	}

	private fun superpairsDebugEnabled(): Boolean = true

	private fun debug(message: () -> String) {
		if (superpairsDebugEnabled()) {
			LOGGER.info("[superpairs-debug] {}", message())
		}
	}

	private fun isEnabled(): Boolean {
		return XclipsenIrcBridgeClient.instance?.config()?.experimentationTableModuleEnabled == true
	}

	private fun clickDelayMs(): Int {
		return XclipsenIrcBridgeClient.instance?.config()?.autoExperimentsClickDelayMs ?: 200
	}

	private fun delayVarietyMs(): Int {
		return XclipsenIrcBridgeClient.instance?.config()?.autoExperimentsDelayVarietyMs ?: 50
	}

	private fun autoCloseEnabled(): Boolean {
		return XclipsenIrcBridgeClient.instance?.config()?.autoExperimentsAutoClose == true
	}

	private fun keepItemsVisibleEnabled(): Boolean {
		return XclipsenIrcBridgeClient.instance?.config()?.autoExperimentsAutoPairs == true
	}

	private fun serumCount(): Int {
		return XclipsenIrcBridgeClient.instance?.config()?.autoExperimentsSerumCount ?: 0
	}

	private fun getMaxXp(): Boolean {
		return XclipsenIrcBridgeClient.instance?.config()?.autoExperimentsGetMaxXp == true
	}

	private fun delay(): Long =
		(clickDelayMs() + (0..delayVarietyMs()).random()).toLong()

	private fun isChronomatronRound(title: String): Boolean = title.startsWith("Chronomatron (")

	private fun isUltrasequencerRound(title: String): Boolean = title.startsWith("Ultrasequencer (")

	private fun isSuperpairsRound(title: String): Boolean = title.startsWith("Superpairs (")

	private fun guiClick(syncId: Int, slotIndex: Int, button: Int = 2, clickType: ContainerInput = ContainerInput.PICKUP) {
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		client.gameMode?.handleContainerInput(syncId, slotIndex, button, clickType, player)
	}

	private fun findSlotAt(screen: ContainerScreen, mouseX: Int, mouseY: Int): Int? {
		val handledScreen = screen as? HandledScreenAccessor ?: return null
		val originX = handledScreen.`xclipsen$getX`()
		val originY = handledScreen.`xclipsen$getY`()

		val slotIndex = screen.menu.slots.indexOfFirst { slot ->
			val left = originX + slot.x
			val top = originY + slot.y
			mouseX in left until (left + 16) && mouseY in top until (top + 16)
		}
		return slotIndex.takeIf { it >= 0 }
	}

	private fun slotStack(screen: ContainerScreen, slotIndex: Int): ItemStack = screen.menu.slots[slotIndex].item

	private fun slotStack(screenHandler: AbstractContainerMenu, slotIndex: Int): ItemStack = screenHandler.slots[slotIndex].item

	private fun describeStack(stack: ItemStack): String {
		return if (stack.isEmpty) {
			"EMPTY"
		} else {
			"${stack.item} x${stack.count} name='${stack.hoverName.string}' formatted='${stack.styledHoverName.formattedTextCompatLeadingWhiteLessResets()}'"
		}
	}

	private fun String.noControlCodes(): String {
		return replace('§', '&').replace(Regex("&."), "")
	}

	private class ChronomatronHandler : ExperimentHandler() {
		private val order = mutableListOf<Int>()
		private var lastAddedSlot = -1
		private var close = false

		override fun onSlotUpdate(screenHandler: AbstractContainerMenu) {
			val slots = screenHandler.slots
			val center = slotStack(screenHandler, MODE_SLOT)

			if (
				lastAddedSlot != -1 &&
				center.item == Items.GLOWSTONE &&
				!slotStack(screenHandler, lastAddedSlot).hasFoil()
			) {
				close = order.size > if (getMaxXp()) 15 else 11 - serumCount()
				hasData = false
				return
			}

			if (hasData || center.item != Items.CLOCK) {
				return
			}

			val slot = slots.firstOrNull { it.index in 10..43 && it.item.hasFoil() } ?: return
			order.add(slot.index)
			lastAddedSlot = slot.index
			hasData = true
			clicks = 0
		}

		override fun nextClick(): Int? = if (hasData && clicks < order.size) order[clicks++] else null

		override fun shouldClose(autoClose: Boolean): Boolean {
			if (!autoClose || !close) {
				return false
			}
			if (clicks < order.size) {
				return false
			}

			close = false
			return true
		}
	}

	private class UltrasequencerHandler : ExperimentHandler() {
		private val order = ConcurrentHashMap<Int, Int>()

		override fun onSlotUpdate(screenHandler: AbstractContainerMenu) {
			val slots = screenHandler.slots
			val center = slotStack(screenHandler, MODE_SLOT)

			if (center.item == Items.CLOCK) {
				hasData = false
				return
			}

			if (hasData || center.item != Items.GLOWSTONE) {
				return
			}

			order.clear()
			for (slot in slots) {
				val stack = slot.item
				if (slot.index in 9..44 && stack.hoverName.string.noControlCodes().matches(Regex("\\d+"))) {
					order[stack.count - 1] = slot.index
				}
			}

			hasData = true
			clicks = 0
		}

		override fun nextClick(): Int? = if (!hasData && clicks < order.size) order[clicks++] else null

		override fun shouldClose(autoClose: Boolean): Boolean {
			return autoClose && !hasData && clicks >= order.size && order.size > if (getMaxXp()) 20 else 9 - serumCount()
		}
	}

	private abstract class ExperimentHandler {
		protected var clicks = 0
		protected var hasData = false

		abstract fun onSlotUpdate(screenHandler: AbstractContainerMenu)

		abstract fun nextClick(): Int?

		abstract fun shouldClose(autoClose: Boolean): Boolean
	}

	private class SuperpairsVisibility {
		private val superpairsSlotMap = mutableMapOf<Int, ItemStack>()
		private val superpairsSlotsToRead = mutableSetOf<Int>()
		private val replacementLogState = mutableMapOf<Int, String>()

		fun onSlotClick(screen: ContainerScreen, slotIndex: Int) {
			if (!keepItemsVisibleEnabled()) {
				return
			}
			if (slotIndex in superpairsSlotMap.keys) {
				debug { "click slot=$slotIndex ignored already-known=${describeStack(superpairsSlotMap.getValue(slotIndex))}" }
				return
			}

			val clickedItem = slotStack(screen, slotIndex)
			if (clickedItem.isEmpty) {
				return
			}
			val unknown = isUnknownSuperpairsClick(clickedItem)
			debug {
				"click slot=$slotIndex unknown=$unknown stack=${describeStack(clickedItem)} " +
					"queuedBefore=${superpairsSlotsToRead.sorted()} rememberedBefore=${superpairsSlotMap.keys.sorted()}"
			}
			if (unknown) {
				superpairsSlotsToRead.add(slotIndex)
				debug { "queued slot=$slotIndex queuedNow=${superpairsSlotsToRead.sorted()}" }
			} else {
				superpairsSlotMap[slotIndex] = clickedItem.copy()
				debug { "stored-immediate slot=$slotIndex stored=${describeStack(clickedItem)} rememberedNow=${superpairsSlotMap.keys.sorted()}" }
			}
		}

		fun onInventoryUpdate(screen: ContainerScreen) {
			if (!keepItemsVisibleEnabled()) {
				reset()
				return
			}

			if (superpairsSlotsToRead.isEmpty()) {
				return
			}

			screen.menu.slots
				.map { it.index }
				.filter { it in superpairsSlotsToRead }
				.forEach { slotIndex ->
					val stack = slotStack(screen, slotIndex)
					debug {
						"inventory-update slot=$slotIndex hidden=${isUnknownSuperpairsClick(stack)} stack=${describeStack(stack)} queued=${superpairsSlotsToRead.sorted()}"
					}
					if (!stack.isEmpty && !isUnknownSuperpairsClick(stack)) {
						superpairsSlotMap[slotIndex] = stack.copy()
						superpairsSlotsToRead.remove(slotIndex)
						debug {
							"stored-from-inventory slot=$slotIndex stored=${describeStack(stack)} queuedNow=${superpairsSlotsToRead.sorted()} rememberedNow=${superpairsSlotMap.keys.sorted()}"
						}
					}
				}
		}

		fun onSlotStackChanged(screen: ContainerScreen, slotIndex: Int, stack: ItemStack) {
			if (!keepItemsVisibleEnabled()) {
				reset()
				return
			}
			if (slotIndex !in superpairsSlotsToRead) {
				return
			}

			debug {
				"slot-update slot=$slotIndex hidden=${isUnknownSuperpairsClick(stack)} stack=${describeStack(stack)} queued=${superpairsSlotsToRead.sorted()}"
			}
			if (!stack.isEmpty && !isUnknownSuperpairsClick(stack)) {
				superpairsSlotMap[slotIndex] = stack.copy()
				superpairsSlotsToRead.remove(slotIndex)
				debug {
					"stored-from-slot-update slot=$slotIndex stored=${describeStack(stack)} queuedNow=${superpairsSlotsToRead.sorted()} rememberedNow=${superpairsSlotMap.keys.sorted()}"
				}
			}
		}

		fun replacementFor(screen: ContainerScreen, slotIndex: Int, original: ItemStack): ItemStack? {
			if (!keepItemsVisibleEnabled() || !isSuperpairsRound(screen.title.string)) {
				return null
			}
			if (superpairsSlotMap.isEmpty() || slotIndex !in superpairsSlotMap.keys) {
				return null
			}
			if (!isUnknownSuperpairsClick(original)) {
				return null
			}

			val replacement = superpairsSlotMap[slotIndex]?.takeUnless { it.isEmpty }?.copy()
			val signature = "${describeStack(original)} -> ${replacement?.let(::describeStack) ?: "null"}"
			if (replacement != null && replacementLogState[slotIndex] != signature) {
				replacementLogState[slotIndex] = signature
				debug { "render-replace slot=$slotIndex $signature" }
			}
			return replacement
		}

		fun reset() {
			if (superpairsSlotMap.isNotEmpty() || superpairsSlotsToRead.isNotEmpty()) {
				debug {
					"reset remembered=${superpairsSlotMap.keys.sorted()} queued=${superpairsSlotsToRead.sorted()}"
				}
			}
			superpairsSlotMap.clear()
			superpairsSlotsToRead.clear()
			replacementLogState.clear()
		}

		private fun isUnknownSuperpairsClick(stack: ItemStack): Boolean {
			return unknownSuperpairsClickPattern.matches(stack.styledHoverName.formattedTextCompatLeadingWhiteLessResets())
		}
	}

	private fun Component.formattedTextCompatLeadingWhiteLessResets(): String {
		val sb = StringBuilder(32)
		var wasFormatted = false
		visit({ style, text ->
			val chatStyle = style.chatStyle()
			if (chatStyle.isNotEmpty() && (wasFormatted || chatStyle != "§f")) {
				sb.append(chatStyle)
				wasFormatted = true
			}
			sb.append(text)
			Optional.empty<Unit>()
		}, Style.EMPTY)
		return sb.toString().removeSuffix("§r").removePrefix("§r")
	}

	private fun Style.chatStyle(): String = buildString {
		color?.let { textColor ->
			ChatFormatting.entries.firstOrNull { formatting ->
				formatting.color != null && textColor.value == formatting.color
			}?.let { append(it.toString()) }
		}
		if (isBold) append("§l")
		if (isItalic) append("§o")
		if (isUnderlined) append("§n")
		if (isStrikethrough) append("§m")
		if (isObfuscated) append("§k")
	}
}
