package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.LoreComponent
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text

object ExperimentationTableFeature {
	private const val MODE_SLOT = 49
	private const val CHRONOMATRON_CYCLE_SLOT = 4
	private const val SUPERPAIRS_INFO_SLOT = 4
	private const val DEFAULT_CLICK_DELAY_MS = 200
	private const val DEFAULT_DELAY_VARIETY_MS = 50
	private const val STAKE_SELECTION_BUFFER_MS = 1_000L
	private const val AUTO_CLOSE_DELAY_MS = 500L
	private const val CHRONOMATRON_STOP_AT_ROUND = 10

	private val remainingClicksPattern = Regex("""Remaining Clicks:\s*(\d+)""")
	private val lockedStakeMessages = listOf(
		"Not enough experience!",
		"Enchanting level too low!",
	)
	private val validStakeDetailPrefixes = listOf(
		"Grid Size:",
		"Clicks:",
		"Colors on board:",
		"XP Reward:",
	)
	private val hiddenMessages = listOf(
		"Click any button!",
		"Click a second button!",
		"Next button is instantly rewarded!",
	)
	private val terracottaToGlass = mapOf(
		Items.RED_TERRACOTTA to Items.RED_STAINED_GLASS,
		Items.ORANGE_TERRACOTTA to Items.ORANGE_STAINED_GLASS,
		Items.YELLOW_TERRACOTTA to Items.YELLOW_STAINED_GLASS,
		Items.LIME_TERRACOTTA to Items.LIME_STAINED_GLASS,
		Items.GREEN_TERRACOTTA to Items.GREEN_STAINED_GLASS,
		Items.CYAN_TERRACOTTA to Items.CYAN_STAINED_GLASS,
		Items.LIGHT_BLUE_TERRACOTTA to Items.LIGHT_BLUE_STAINED_GLASS,
		Items.BLUE_TERRACOTTA to Items.BLUE_STAINED_GLASS,
		Items.PURPLE_TERRACOTTA to Items.PURPLE_STAINED_GLASS,
		Items.PINK_TERRACOTTA to Items.PINK_STAINED_GLASS,
	)

	private val chronomatronSolver = ChronomatronSolver()
	private val ultrasequencerSolver = UltrasequencerSolver()
	private val superpairsSolver = SuperpairsSolver()

	fun init() {
		ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
			if (screen !is GenericContainerScreen) {
				return@register
			}

			when {
				isChronomatronRound(screen.title.string) -> {
					chronomatronSolver.reset()
					ScreenEvents.afterTick(screen).register { currentScreen ->
						if (currentScreen !is GenericContainerScreen) return@register
						if (!isEnabled()) {
							chronomatronSolver.reset()
							return@register
						}
						chronomatronSolver.tick(currentScreen)
					}
				}

				isUltrasequencerRound(screen.title.string) -> {
					ultrasequencerSolver.reset()
					ScreenEvents.afterTick(screen).register { currentScreen ->
						if (currentScreen !is GenericContainerScreen) return@register
						if (!isEnabled()) {
							ultrasequencerSolver.reset()
							return@register
						}
						ultrasequencerSolver.tick(currentScreen)
					}
				}

				isSuperpairsRound(screen.title.string) -> {
					superpairsSolver.reset()
					ScreenEvents.afterTick(screen).register { currentScreen ->
						if (currentScreen !is GenericContainerScreen) return@register
						if (!isEnabled()) {
							superpairsSolver.reset()
							return@register
						}
						superpairsSolver.tick(currentScreen)
					}
				}

				isRewardScreen(screen.title.string) -> {
					val openedAtMs = System.currentTimeMillis()
					ScreenEvents.afterTick(screen).register { currentScreen ->
						if (currentScreen !is GenericContainerScreen) return@register
						if (autoCloseEnabled() && System.currentTimeMillis() - openedAtMs >= AUTO_CLOSE_DELAY_MS) {
							closeCurrentScreen()
						}
					}
				}

				isStakeScreen(screen.title.string) -> {
					var started = false
					var attempts = 0
					val openedAtMs = System.currentTimeMillis()
					ScreenEvents.afterTick(screen).register { currentScreen ->
						if (currentScreen !is GenericContainerScreen) return@register
						if (!isEnabled() || started) return@register
						if (System.currentTimeMillis() - openedAtMs < STAKE_SELECTION_BUFFER_MS) return@register
						attempts++
						started = startSelectedExperiment(showFeedback = attempts >= 10)
					}
				}

				else -> resetSolvers()
			}
		}
	}

	@JvmStatic
	fun shouldAddStartButton(title: String): Boolean {
		return false
	}

	@JvmStatic
	fun startSelectedExperiment(showFeedback: Boolean = true): Boolean {
		if (!isEnabled()) {
			if (showFeedback) {
				sendFeedback("Experimentation Table module is disabled in /xclipsen.")
			}
			return false
		}

		val client = MinecraftClient.getInstance()
		val screen = client.currentScreen as? GenericContainerScreen ?: return false
		val player = client.player ?: return false
		val interactionManager = client.interactionManager ?: return false
		val slotIndex = findBestStakeSlot(screen)

		if (slotIndex != null) {
			interactionManager.clickSlot(
				screen.screenHandler.syncId,
				slotIndex,
				0,
				SlotActionType.PICKUP,
				player,
			)
			return true
		}

		if (showFeedback) {
			sendFeedback("No available experiment stake found.")
		}
		return false
	}

	private fun findBestStakeSlot(screen: GenericContainerScreen): Int? {
		val experimentName = screen.title.string.substringBefore("➜").trim()
		val containerSlots = screen.screenHandler.rows * 9

		return (0 until containerSlots)
			.filter { slotIndex ->
				val stack = slotStack(screen, slotIndex)
				if (stack.item == Items.AIR || stack.item == Items.GRAY_DYE || stack.item.isPane()) {
					return@filter false
				}

				val tooltip = normalizedTooltip(slotStack(screen, slotIndex))
				if (tooltip.isEmpty()) {
					return@filter false
				}
				if (!tooltip.any { it.equals(experimentName, ignoreCase = true) }) {
					return@filter false
				}
				if (!tooltip.any { line -> validStakeDetailPrefixes.any { prefix -> line.startsWith(prefix, ignoreCase = true) } }) {
					return@filter false
				}
				if (!tooltip.any { it.contains("Click to play!", ignoreCase = true) }) {
					return@filter false
				}
				if (tooltip.any { line -> lockedStakeMessages.any { locked -> line.contains(locked, ignoreCase = true) } }) {
					return@filter false
				}
				true
			}
			.maxOrNull()
	}

	private fun isEnabled(): Boolean {
		return XclipsenIrcBridgeClient.instance?.config()?.experimentationTableModuleEnabled == true
	}

	private fun clickDelayMs(): Int {
		return XclipsenIrcBridgeClient.instance?.config()?.autoExperimentsClickDelayMs ?: DEFAULT_CLICK_DELAY_MS
	}

	private fun delayVarietyMs(): Int {
		return XclipsenIrcBridgeClient.instance?.config()?.autoExperimentsDelayVarietyMs ?: DEFAULT_DELAY_VARIETY_MS
	}

	private fun autoCloseEnabled(): Boolean {
		return XclipsenIrcBridgeClient.instance?.config()?.autoExperimentsAutoClose == true
	}

	private fun getMaxXp(): Boolean {
		return XclipsenIrcBridgeClient.instance?.config()?.autoExperimentsGetMaxXp == true
	}

	private fun serumCount(): Int {
		return XclipsenIrcBridgeClient.instance?.config()?.autoExperimentsSerumCount ?: 0
	}

	private fun actionDelayTicks(): Int {
		val delayMs = clickDelayMs() + (0..delayVarietyMs()).random()
		return (delayMs / 50).coerceAtLeast(1)
	}

	private fun chronomatronTargetLength(): Int {
		return if (getMaxXp()) 15 else 11 - serumCount()
	}

	private fun ultrasequencerTargetLength(): Int {
		return if (getMaxXp()) 20 else 9 - serumCount()
	}

	private fun isStakeScreen(title: String): Boolean {
		return title == "Chronomatron ➜ Stakes" || title == "Ultrasequencer ➜ Stakes" || title == "Superpairs ➜ Stakes"
	}

	private fun isChronomatronRound(title: String): Boolean = title.startsWith("Chronomatron (")

	private fun isUltrasequencerRound(title: String): Boolean = title.startsWith("Ultrasequencer (")

	private fun isSuperpairsRound(title: String): Boolean = title.startsWith("Superpairs (")

	private fun isRewardScreen(title: String): Boolean = title == "Superpairs Rewards" || title == "Experiment Over"

	private fun resetSolvers() {
		chronomatronSolver.reset()
		ultrasequencerSolver.reset()
		superpairsSolver.reset()
	}

	private fun clickSlot(screen: GenericContainerScreen, slotIndex: Int) {
		val client = MinecraftClient.getInstance()
		val player = client.player ?: return
		val interactionManager = client.interactionManager ?: return
		interactionManager.clickSlot(
			screen.screenHandler.syncId,
			slotIndex,
			0,
			SlotActionType.PICKUP,
			player,
		)
	}

	private fun closeCurrentScreen() {
		MinecraftClient.getInstance().player?.closeHandledScreen()
	}

	private fun autoCloseReadyAtMs(): Long = System.currentTimeMillis() + AUTO_CLOSE_DELAY_MS

	private fun slotStack(screen: GenericContainerScreen, slotIndex: Int): ItemStack = screen.screenHandler.slots[slotIndex].stack

	private fun slotStack(screenHandler: ScreenHandler, slotIndex: Int): ItemStack = screenHandler.slots[slotIndex].stack

	private fun sendFeedback(message: String) {
		MinecraftClient.getInstance().player?.sendMessage(Text.literal(message), false)
	}

	private fun Item.isPane(): Boolean = translationKey.endsWith("_pane")

	private fun ItemStack.tooltipLines(): List<String> {
		val lines = mutableListOf(name.string)
		val lore = get(DataComponentTypes.LORE) as? LoreComponent ?: return lines
		lore.styledLines().forEach { line -> lines.add(line.string) }
		return lines
	}

	private fun normalizedTooltip(stack: ItemStack): List<String> {
		return stack.tooltipLines()
			.map { it.replace('§', '&').replace(Regex("&."), "").trim() }
			.filter { it.isNotEmpty() }
	}

	private class ChronomatronSolver {
		private var glintFound = false
		private var glintSlot = -1
		private val clickStack = ArrayDeque<Item>()
		private var lastCycle = 0
		private var startSeconds = -1
		private var lastModeItem: Item? = null
		private var currentDelay = 0
		private var closeAfterSequence = false
		private var pendingCloseAtMs = 0L

		fun tick(screen: GenericContainerScreen) {
			if (pendingCloseAtMs > 0L && System.currentTimeMillis() >= pendingCloseAtMs) {
				closeCurrentScreen()
				pendingCloseAtMs = 0L
				closeAfterSequence = false
				return
			}

			if (currentDelay > 0) {
				currentDelay--
			}

			val currentCycle = slotStack(screen, CHRONOMATRON_CYCLE_SLOT).count
			val currentModeItem = slotStack(screen, MODE_SLOT).item
			if (autoCloseEnabled() && currentCycle >= CHRONOMATRON_STOP_AT_ROUND) {
				if (pendingCloseAtMs == 0L) {
					pendingCloseAtMs = autoCloseReadyAtMs()
				}
				return
			}

			if ((currentCycle > 0 && currentModeItem == Items.GLOWSTONE) || (currentCycle == lastCycle && currentModeItem != lastModeItem)) {
				startSeconds = -1
				captureSequence(screen)
			} else {
				if (startSeconds == -1) {
					startSeconds = slotStack(screen, MODE_SLOT).count
				}
				if (slotStack(screen, MODE_SLOT).count < startSeconds) {
					inputSequence(screen)
				}
			}

			lastCycle = currentCycle
			lastModeItem = currentModeItem
		}

		fun reset() {
			glintFound = false
			glintSlot = -1
			clickStack.clear()
			lastCycle = 0
			startSeconds = -1
			lastModeItem = null
			currentDelay = 0
			closeAfterSequence = false
			pendingCloseAtMs = 0L
		}

		private fun captureSequence(screen: GenericContainerScreen) {
			if (!glintFound) {
				for (slotIndex in 10..42) {
					val stack = slotStack(screen, slotIndex)
					if (!stack.hasGlint()) continue

					val mappedItem = terracottaToGlass[stack.item] ?: continue
					glintFound = true
					glintSlot = slotIndex
					clickStack.addLast(mappedItem)
					return
				}
				return
			}

			if (glintSlot >= 0 && !slotStack(screen, glintSlot).hasGlint()) {
				glintFound = false
				glintSlot = -1
				closeAfterSequence = clickStack.size > chronomatronTargetLength()
			}
		}

		private fun inputSequence(screen: GenericContainerScreen) {
			if (currentDelay > 0) {
				return
			}

			val client = MinecraftClient.getInstance()
			val player = client.player ?: return
			if (player.currentScreenHandler.cursorStack.item != Items.AIR) return

			val nextItem = clickStack.firstOrNull() ?: return
			for (slotIndex in 10..42) {
				if (slotStack(screen, slotIndex).item != nextItem) continue

				clickSlot(screen, slotIndex)
				clickStack.removeFirst()
				currentDelay = actionDelayTicks()
				if (closeAfterSequence && clickStack.isEmpty() && autoCloseEnabled()) {
					pendingCloseAtMs = autoCloseReadyAtMs()
				}
				return
			}
		}
	}

	private class UltrasequencerSolver {
		private val clickStack = ArrayDeque<Int>()
		private var startSeconds = -1
		private var currentDelay = 0
		private var closeAfterSequence = false
		private var pendingCloseAtMs = 0L

		fun tick(screen: GenericContainerScreen) {
			if (pendingCloseAtMs > 0L && System.currentTimeMillis() >= pendingCloseAtMs) {
				closeCurrentScreen()
				pendingCloseAtMs = 0L
				closeAfterSequence = false
				return
			}

			if (currentDelay > 0) {
				currentDelay--
			}

			val modeItem = slotStack(screen, MODE_SLOT).item
			when (modeItem) {
				Items.GLOWSTONE -> {
					startSeconds = -1
					rememberSequence(screen)
				}

				Items.CLOCK -> {
					if (startSeconds == -1) {
						startSeconds = slotStack(screen, MODE_SLOT).count
					}
					inputSequence(screen)
				}
			}
		}

		fun reset() {
			clickStack.clear()
			startSeconds = -1
			currentDelay = 0
			closeAfterSequence = false
			pendingCloseAtMs = 0L
		}

		private fun rememberSequence(screen: GenericContainerScreen) {
			for (slotIndex in 0..44) {
				val stack = slotStack(screen, slotIndex)
				if (stack.item == Items.AIR || stack.item.isPane()) continue

				if (stack.count == clickStack.size + 1) {
					clickStack.addLast(slotIndex)
				}
			}

			closeAfterSequence = clickStack.size >= ultrasequencerTargetLength()
		}

		private fun inputSequence(screen: GenericContainerScreen) {
			if (currentDelay > 0) {
				return
			}

			val client = MinecraftClient.getInstance()
			val player = client.player ?: return
			if (player.currentScreenHandler.cursorStack.item != Items.AIR) return

			val nextSlot = clickStack.firstOrNull() ?: return
			clickSlot(screen, nextSlot)
			clickStack.removeFirst()
			currentDelay = actionDelayTicks()
			if (closeAfterSequence && clickStack.isEmpty() && autoCloseEnabled()) {
				pendingCloseAtMs = autoCloseReadyAtMs()
			}
		}
	}

	private class SuperpairsSolver {
		private val rememberedCards = mutableMapOf<Int, SuperpairsCard>()
		private val solvedSlots = mutableSetOf<Int>()
		private var pendingReveal: PendingReveal? = null
		private var awaitingBoardReset: AwaitingBoardReset? = null
		private var instantFindPending = false
		private var nextActionAtMs = 0L
		private var pendingCloseAtMs = 0L

		fun tick(screen: GenericContainerScreen) {
			val tier = SuperpairsTier.fromTitle(screen.title.string) ?: return
			val boardSlots = tier.slotRange

			updateBoardMemory(screen, boardSlots)

			if (pendingCloseAtMs > 0L && System.currentTimeMillis() >= pendingCloseAtMs) {
				closeCurrentScreen()
				pendingCloseAtMs = 0L
				return
			}

			val boardReset = awaitingBoardReset
			if (boardReset != null) {
				if (hasBoardReset(screen, boardReset.slots)) {
					awaitingBoardReset = null
					scheduleNextAction()
				}
				return
			}

			val client = MinecraftClient.getInstance()
			val player = client.player ?: return
			if (player.currentScreenHandler.cursorStack.item != Items.AIR) return
			if (remainingClicks(screen) == 0) {
				if (autoCloseEnabled()) {
					pendingCloseAtMs = autoCloseReadyAtMs()
				}
				return
			}

			val pending = pendingReveal
			if (pending != null) {
				handlePendingReveal(screen, boardSlots, pending)
				return
			}

			if (System.currentTimeMillis() < nextActionAtMs) {
				return
			}

			if (instantFindPending) {
				val nextSlot = chooseUnknownHiddenSlot(screen, boardSlots) ?: chooseHiddenSlot(screen, boardSlots) ?: return
				clickSlot(screen, nextSlot)
				pendingReveal = PendingReveal(nextSlot, null, PendingStage.INSTANT_FIND, null)
				scheduleNextAction()
				return
			}

			val knownPair = findKnownPair(screen, boardSlots)
			if (knownPair != null) {
				clickSlot(screen, knownPair.first)
				pendingReveal = PendingReveal(knownPair.first, knownPair.second, PendingStage.FIRST_SELECTION, rememberedCards[knownPair.first]?.key)
				scheduleNextAction()
				return
			}

			val firstUnknown = chooseUnknownHiddenSlot(screen, boardSlots) ?: return
			clickSlot(screen, firstUnknown)
			pendingReveal = PendingReveal(firstUnknown, null, PendingStage.FIRST_SELECTION, null)
			scheduleNextAction()
		}

		fun reset() {
			rememberedCards.clear()
			solvedSlots.clear()
			pendingReveal = null
			awaitingBoardReset = null
			instantFindPending = false
			nextActionAtMs = 0L
			pendingCloseAtMs = 0L
		}

		private fun handlePendingReveal(screen: GenericContainerScreen, boardSlots: List<Int>, pending: PendingReveal) {
			val info = analyzeSlot(screen, pending.clickedSlot)
			if (info.hidden) {
				return
			}

				if (info.removed) {
					when (pending.stage) {
						PendingStage.INSTANT_FIND -> {
							instantFindPending = false
						}

						PendingStage.SECOND_SELECTION -> {
							val partnerSlot = pending.partnerSlot
							if (partnerSlot != null && pending.expectedKey != null) {
								solvedSlots.add(partnerSlot)
								solvedSlots.add(pending.clickedSlot)
								rememberedCards.remove(partnerSlot)
								rememberedCards.remove(pending.clickedSlot)
							}
							awaitingBoardReset = AwaitingBoardReset(listOfNotNull(partnerSlot, pending.clickedSlot))
						}

						PendingStage.FIRST_SELECTION -> {}
					}

					pendingReveal = null
					if (awaitingBoardReset == null) {
						scheduleNextAction()
					}
					return
				}

			val card = info.card ?: return
			rememberedCards[pending.clickedSlot] = card

			when (pending.stage) {
				PendingStage.INSTANT_FIND -> {
					instantFindPending = false
					solvedSlots.add(pending.clickedSlot)
					rememberedCards.remove(pending.clickedSlot)
					pendingReveal = null
					scheduleNextAction()
				}

				PendingStage.FIRST_SELECTION -> {
					if (card.powerUp == SuperpairsPowerUp.INSTANT_FIND) {
						instantFindPending = true
						pendingReveal = null
						scheduleNextAction()
						return
					}

					if (card.powerUp != null) {
						pendingReveal = null
						scheduleNextAction()
						return
					}

					val partnerSlot = pending.partnerSlot
						?.takeIf { isHiddenSlot(screen, it) }
						?: findMatchingHiddenSlot(screen, boardSlots, card.key, pending.clickedSlot)
						?: chooseUnknownHiddenSlot(screen, boardSlots, pending.clickedSlot)
						?: chooseHiddenSlot(screen, boardSlots, pending.clickedSlot)
						?: run {
							pendingReveal = null
							scheduleNextAction()
							return
						}

					if (System.currentTimeMillis() < nextActionAtMs) {
						return
					}

					clickSlot(screen, partnerSlot)
					pendingReveal = PendingReveal(partnerSlot, pending.clickedSlot, PendingStage.SECOND_SELECTION, card.key)
					scheduleNextAction()
				}

				PendingStage.SECOND_SELECTION -> {
					if (card.powerUp == SuperpairsPowerUp.INSTANT_FIND) {
						instantFindPending = true
					}

					val partnerSlot = pending.partnerSlot
					if (partnerSlot != null && pending.expectedKey != null && card.key == pending.expectedKey) {
						solvedSlots.add(partnerSlot)
						solvedSlots.add(pending.clickedSlot)
						rememberedCards.remove(partnerSlot)
						rememberedCards.remove(pending.clickedSlot)
					}

					pendingReveal = null
					awaitingBoardReset = AwaitingBoardReset(listOfNotNull(partnerSlot, pending.clickedSlot))
				}
			}
		}

		private fun hasBoardReset(screen: GenericContainerScreen, slots: List<Int>): Boolean {
			return slots.all { slotIndex ->
				val info = analyzeSlot(screen, slotIndex)
				info.hidden || info.removed
			}
		}

		private fun scheduleNextAction() {
			nextActionAtMs = System.currentTimeMillis() + superpairsDelayMs()
		}

		private fun superpairsDelayMs(): Long {
			val base = clickDelayMs() + (0..delayVarietyMs()).random()
			return base.toLong().coerceAtLeast(50L)
		}

		private fun updateBoardMemory(screen: GenericContainerScreen, boardSlots: List<Int>) {
			for (slotIndex in boardSlots) {
				val info = analyzeSlot(screen, slotIndex)
				when {
					info.card != null -> rememberedCards[slotIndex] = info.card
					info.removed -> rememberedCards.remove(slotIndex)
				}
			}
		}

		private fun findKnownPair(screen: GenericContainerScreen, boardSlots: List<Int>): Pair<Int, Int>? {
			val hiddenRemembered = rememberedCards.entries
				.filter { (slotIndex, _) -> slotIndex in boardSlots && slotIndex !in solvedSlots && isHiddenSlot(screen, slotIndex) }
				.groupBy({ it.value.key }, { it.key })

			return hiddenRemembered.entries
				.filter { it.value.size >= 2 }
				.maxByOrNull { (key, slots) ->
					rememberedCards[slots.first()]?.priority ?: pairPriorityFromKey(key)
				}
				?.value
				?.sorted()
				?.let { it[0] to it[1] }
		}

		private fun findMatchingHiddenSlot(screen: GenericContainerScreen, boardSlots: List<Int>, key: String, excludeSlot: Int): Int? {
			return rememberedCards.entries
				.firstOrNull { (slotIndex, card) ->
					slotIndex in boardSlots &&
						slotIndex !in solvedSlots &&
						slotIndex != excludeSlot &&
						card.key == key &&
						isHiddenSlot(screen, slotIndex)
				}
				?.key
		}

		private fun chooseUnknownHiddenSlot(screen: GenericContainerScreen, boardSlots: List<Int>, excludeSlot: Int? = null): Int? {
			return boardSlots.firstOrNull { slotIndex ->
				slotIndex !in solvedSlots &&
					slotIndex != excludeSlot &&
					slotIndex !in rememberedCards &&
					isHiddenSlot(screen, slotIndex)
			}
		}

		private fun chooseHiddenSlot(screen: GenericContainerScreen, boardSlots: List<Int>, excludeSlot: Int? = null): Int? {
			return boardSlots.firstOrNull { slotIndex ->
				slotIndex !in solvedSlots &&
					slotIndex != excludeSlot &&
					isHiddenSlot(screen, slotIndex)
			}
		}

		private fun isHiddenSlot(screen: GenericContainerScreen, slotIndex: Int): Boolean {
			return analyzeSlot(screen, slotIndex).hidden
		}

		private fun remainingClicks(screen: GenericContainerScreen): Int {
			val text = normalizedTooltip(slotStack(screen, SUPERPAIRS_INFO_SLOT)).joinToString(" ")
			return remainingClicksPattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
		}

		private fun analyzeSlot(screen: GenericContainerScreen, slotIndex: Int): SuperpairsSlotInfo {
			val stack = slotStack(screen, slotIndex)
			if (stack.item == Items.AIR) {
				return SuperpairsSlotInfo(removed = true)
			}

			val tooltip = normalizedTooltip(stack)
			if (tooltip.isEmpty()) {
				return SuperpairsSlotInfo(removed = true)
			}

			val text = tooltip.joinToString(" | ")
			val name = tooltip.first()
			if (name == "?" || hiddenMessages.any { text.contains(it, ignoreCase = true) }) {
				return SuperpairsSlotInfo(hidden = true)
			}

			if (stack.item == Items.GRAY_DYE || (stack.item.isPane() && hiddenMessages.none { text.contains(it, ignoreCase = true) })) {
				return SuperpairsSlotInfo(removed = true)
			}

			val lore = tooltip.drop(1)
			val powerUp = detectPowerUp(name, lore)
			val priority = computePriority(name, lore, powerUp)
			val key = when {
				name.equals("Enchanted Book", ignoreCase = true) && lore.isNotEmpty() -> "BOOK|${lore.joinToString("|")}"
				lore.isNotEmpty() -> "$name|${lore.joinToString("|")}"
				else -> name
			}

			return SuperpairsSlotInfo(card = SuperpairsCard(key = key, displayName = name, powerUp = powerUp, priority = priority))
		}

		private fun detectPowerUp(name: String, lore: List<String>): SuperpairsPowerUp? {
			val text = buildString {
				append(name)
				if (lore.isNotEmpty()) {
					append(' ')
					append(lore.joinToString(" "))
				}
			}

			return when {
				text.contains("Instant Find", ignoreCase = true) -> SuperpairsPowerUp.INSTANT_FIND
				text.contains("Gained +", ignoreCase = true) && text.contains("Click", ignoreCase = true) -> SuperpairsPowerUp.EXTRA_CLICKS
				text.contains(" XP", ignoreCase = true) || text.contains("+XP", ignoreCase = true) -> SuperpairsPowerUp.EXTRA_XP
				else -> null
			}
		}

		private fun computePriority(name: String, lore: List<String>, powerUp: SuperpairsPowerUp?): Long {
			val text = buildString {
				append(name)
				if (lore.isNotEmpty()) {
					append(' ')
					append(lore.joinToString(" "))
				}
			}

			return when (powerUp) {
				SuperpairsPowerUp.EXTRA_CLICKS -> 2_000_000L
				SuperpairsPowerUp.INSTANT_FIND -> 1_900_000L
				SuperpairsPowerUp.EXTRA_XP -> 1_800_000L
				null -> {
					when {
						text.contains("Enchanting Exp", ignoreCase = true) -> 1_000_000L + parseCompactAmount(text)
						text.contains("Titanic Experience Bottle", ignoreCase = true) -> 800_000L
						text.contains("Grand Experience Bottle", ignoreCase = true) -> 700_000L
						text.contains("Experience Bottle", ignoreCase = true) -> 600_000L
						text.contains("Guardian", ignoreCase = true) -> 400_000L
						name.equals("Enchanted Book", ignoreCase = true) -> 200_000L
						else -> 100_000L
					}
				}
			}
		}

		private fun parseCompactAmount(text: String): Long {
			val match = Regex("""(\d[\d,]*)([kKmMbB]?)""").find(text) ?: return 0L
			val raw = match.groupValues[1].replace(",", "")
			val base = raw.toLongOrNull() ?: return 0L
			return when (match.groupValues[2].lowercase()) {
				"k" -> base * 1_000L
				"m" -> base * 1_000_000L
				"b" -> base * 1_000_000_000L
				else -> base
			}
		}

		private fun pairPriorityFromKey(key: String): Long {
			return when {
				key.contains("Enchanting Exp", ignoreCase = true) -> 1_000_000L + parseCompactAmount(key)
				key.contains("Titanic Experience Bottle", ignoreCase = true) -> 800_000L
				key.contains("Grand Experience Bottle", ignoreCase = true) -> 700_000L
				key.contains("Experience Bottle", ignoreCase = true) -> 600_000L
				key.contains("Guardian", ignoreCase = true) -> 400_000L
				key.startsWith("BOOK|") -> 200_000L
				else -> 100_000L
			}
		}
	}

	private data class SuperpairsCard(
		val key: String,
		val displayName: String,
		val powerUp: SuperpairsPowerUp?,
		val priority: Long,
	)

	private data class SuperpairsSlotInfo(
		val hidden: Boolean = false,
		val removed: Boolean = false,
		val card: SuperpairsCard? = null,
	)

	private data class PendingReveal(
		val clickedSlot: Int,
		val partnerSlot: Int?,
		val stage: PendingStage,
		val expectedKey: String?,
	)

	private data class AwaitingBoardReset(
		val slots: List<Int>,
	)

	private enum class PendingStage {
		FIRST_SELECTION,
		SECOND_SELECTION,
		INSTANT_FIND,
	}

	private enum class SuperpairsPowerUp {
		INSTANT_FIND,
		EXTRA_CLICKS,
		EXTRA_XP,
	}

	private enum class SuperpairsTier(val displayName: String, private val overInclusiveSlotRange: IntRange, private val sideSpace: Int = 1) {
		BEGINNER("Beginner", 18..35),
		HIGH("High", 10..43, sideSpace = 2),
		GRAND("Grand", 10..43, sideSpace = 2),
		SUPREME("Supreme", 9..44),
		TRANSCENDENT("Transcendent", 9..44),
		METAPHYSICAL("Metaphysical", 9..44),
		;

		val slotRange: List<Int> = overInclusiveSlotRange.filter {
			(it % 9) !in when (sideSpace) {
				1 -> listOf(0, 8)
				2 -> listOf(0, 1, 7, 8)
				else -> emptyList()
			}
		}

		companion object {
			fun fromTitle(title: String): SuperpairsTier? {
				val tierName = Regex("""Superpairs \((.+)\)""").find(title)?.groupValues?.getOrNull(1) ?: return null
				return entries.firstOrNull { it.displayName.equals(tierName, ignoreCase = true) }
			}
		}
	}
}
