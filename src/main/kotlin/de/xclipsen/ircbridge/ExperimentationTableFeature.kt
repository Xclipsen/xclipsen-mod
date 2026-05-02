package de.xclipsen.ircbridge

import de.xclipsen.ircbridge.mixin.HandledScreenAccessor
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.slf4j.LoggerFactory
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

object ExperimentationTableFeature {
	private const val MODE_SLOT = 49
	private val unknownSuperpairsClickPattern = Regex("""(?:§.)+(?:\?|(?:Click a(?: seco)?n[dy]|Next) button(?: is instantly rewarded)?!?)""")
	private val LOGGER = LoggerFactory.getLogger("xclipsen_experimentation")

	private var handler: ExperimentHandler? = null
	private var lastClick = 0L
	private val superpairsVisibility = SuperpairsVisibility()

	fun init() {
		ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
			if (screen !is GenericContainerScreen) {
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
				currentScreen !is GenericContainerScreen || handler == null
			}

			ScreenMouseEvents.beforeMouseClick(screen).register { currentScreen, click ->
				if (currentScreen !is GenericContainerScreen || !isEnabled() || !isSuperpairsRound(currentScreen.title.string)) {
					return@register
				}

				val slotIndex = findSlotAt(currentScreen, click.x().toInt(), click.y().toInt()) ?: return@register
				superpairsVisibility.onSlotClick(currentScreen, slotIndex)
			}
		}
	}

	fun onTick(client: MinecraftClient) {
		if (!isEnabled()) {
			reset()
			return
		}

		val screen = client.currentScreen as? GenericContainerScreen ?: run {
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
			guiClick(screen.screenHandler.syncId, slotId, 2, SlotActionType.CLONE)
			lastClick = now
		}

		if (!handler.shouldClose(autoCloseEnabled())) {
			return
		}

		client.player?.closeHandledScreen()
		this.handler = null
	}

	fun onSlotUpdate(screenHandler: ScreenHandler) {
		if (!isEnabled()) {
			return
		}

		val screen = MinecraftClient.getInstance().currentScreen as? GenericContainerScreen ?: return
		if (screen.screenHandler !== screenHandler) {
			return
		}

		val title = screen.title.string

		when {
			handler != null -> handler?.onSlotUpdate(screenHandler)
			isSuperpairsRound(title) -> superpairsVisibility.onInventoryUpdate(screen)
		}
	}

	fun onSlotStackChanged(screenHandler: ScreenHandler, slotIndex: Int, stack: ItemStack) {
		if (!isEnabled()) {
			return
		}

		val screen = MinecraftClient.getInstance().currentScreen as? GenericContainerScreen ?: return
		if (screen.screenHandler !== screenHandler || !isSuperpairsRound(screen.title.string)) {
			return
		}

		superpairsVisibility.onSlotStackChanged(screen, slotIndex, stack)
	}

	@JvmStatic
	fun replaceSuperpairsItem(screen: HandledScreen<*>, slot: Slot, original: ItemStack): ItemStack {
		val genericScreen = screen as? GenericContainerScreen ?: return original
		if (slot.inventory is PlayerInventory) {
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

	private fun guiClick(syncId: Int, slotIndex: Int, button: Int = 2, clickType: SlotActionType = SlotActionType.PICKUP) {
		val client = MinecraftClient.getInstance()
		val player = client.player ?: return
		client.interactionManager?.clickSlot(syncId, slotIndex, button, clickType, player)
	}

	private fun findSlotAt(screen: GenericContainerScreen, mouseX: Int, mouseY: Int): Int? {
		val handledScreen = screen as? HandledScreenAccessor ?: return null
		val originX = handledScreen.`xclipsen$getX`()
		val originY = handledScreen.`xclipsen$getY`()

		val slotIndex = screen.screenHandler.slots.indexOfFirst { slot ->
			val left = originX + slot.x
			val top = originY + slot.y
			mouseX in left until (left + 16) && mouseY in top until (top + 16)
		}
		return slotIndex.takeIf { it >= 0 }
	}

	private fun slotStack(screen: GenericContainerScreen, slotIndex: Int): ItemStack = screen.screenHandler.slots[slotIndex].stack

	private fun slotStack(screenHandler: ScreenHandler, slotIndex: Int): ItemStack = screenHandler.slots[slotIndex].stack

	private fun describeStack(stack: ItemStack): String {
		return if (stack.isEmpty) {
			"EMPTY"
		} else {
			"${stack.item} x${stack.count} name='${stack.name.string}' formatted='${stack.formattedName.formattedTextCompatLeadingWhiteLessResets()}'"
		}
	}

	private fun String.noControlCodes(): String {
		return replace('§', '&').replace(Regex("&."), "")
	}

	private class ChronomatronHandler : ExperimentHandler() {
		private val order = mutableListOf<Int>()
		private var lastAddedSlot = -1
		private var close = false

		override fun onSlotUpdate(screenHandler: ScreenHandler) {
			val slots = screenHandler.slots
			val center = slotStack(screenHandler, MODE_SLOT)

			if (
				lastAddedSlot != -1 &&
				center.item == Items.GLOWSTONE &&
				!slotStack(screenHandler, lastAddedSlot).hasGlint()
			) {
				close = order.size > if (getMaxXp()) 15 else 11 - serumCount()
				hasData = false
				return
			}

			if (hasData || center.item != Items.CLOCK) {
				return
			}

			val slot = slots.firstOrNull { it.index in 10..43 && it.stack.hasGlint() } ?: return
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

		override fun onSlotUpdate(screenHandler: ScreenHandler) {
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
				val stack = slot.stack
				if (slot.index in 9..44 && stack.name.string.noControlCodes().matches(Regex("\\d+"))) {
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

		abstract fun onSlotUpdate(screenHandler: ScreenHandler)

		abstract fun nextClick(): Int?

		abstract fun shouldClose(autoClose: Boolean): Boolean
	}

	private class SuperpairsVisibility {
		private val superpairsSlotMap = mutableMapOf<Int, ItemStack>()
		private val superpairsSlotsToRead = mutableSetOf<Int>()
		private val replacementLogState = mutableMapOf<Int, String>()

		fun onSlotClick(screen: GenericContainerScreen, slotIndex: Int) {
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

		fun onInventoryUpdate(screen: GenericContainerScreen) {
			if (!keepItemsVisibleEnabled()) {
				reset()
				return
			}

			if (superpairsSlotsToRead.isEmpty()) {
				return
			}

			screen.screenHandler.slots
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

		fun onSlotStackChanged(screen: GenericContainerScreen, slotIndex: Int, stack: ItemStack) {
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

		fun replacementFor(screen: GenericContainerScreen, slotIndex: Int, original: ItemStack): ItemStack? {
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
			return unknownSuperpairsClickPattern.matches(stack.formattedName.formattedTextCompatLeadingWhiteLessResets())
		}
	}

	private fun Text.formattedTextCompatLeadingWhiteLessResets(): String {
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
			Formatting.entries.firstOrNull { formatting ->
				formatting.colorValue != null && textColor.rgb == formatting.colorValue
			}?.let { append(it.toString()) }
		}
		if (isBold) append("§l")
		if (isItalic) append("§o")
		if (isUnderlined) append("§n")
		if (isStrikethrough) append("§m")
		if (isObfuscated) append("§k")
	}
}
