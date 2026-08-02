package de.xclipsen.ircbridge

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Component
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.min

object SlayerFeature {
	const val DEFAULT_ANNOUNCER_TEXT = "Slayer boss spawned!"

	private const val DEDUPE_MS = 2500L
	private const val ALERT_VISIBLE_MS = 2800L
	private const val BACKEND_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
	private const val STATE_SAVE_INTERVAL_MS = 3000L
	private const val SCREEN_SCAN_INTERVAL_TICKS = 10
	private var lastAnnounceAt = 0L
	private var lastStateSaveAt = 0L
	private var screenScanTickCounter = 0
	private var lastSlayerFromChat = ""
	private var currentAlertText = ""
	private var alertVisibleUntil = 0L
	@Volatile
	private var wikiFetchInFlight = false
	private val backendExecutor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "xclipsen-slayer-backend").apply { isDaemon = true }
	}

	fun onWorldChange() {
		lastAnnounceAt = 0L
		lastStateSaveAt = 0L
		screenScanTickCounter = 0
		lastSlayerFromChat = ""
		currentAlertText = ""
		alertVisibleUntil = 0L
		wikiFetchInFlight = false
	}

	fun shutdown() {
		onWorldChange()
		backendExecutor.shutdownNow()
	}

	fun onIncomingMessage(message: Component?) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.slayerModuleEnabled) {
			return
		}

		if (!LocationTracker.isOnHypixelSkyBlock) {
			return
		}

		handleRngMeterChatUpdate(config, message)

		if (config.slayerSpawnAnnouncerEnabled && isSlayerSpawnPetRule(message ?: return)) {
			val now = System.currentTimeMillis()
			if (now - lastAnnounceAt < DEDUPE_MS) {
				return
			}
			lastAnnounceAt = now

			Minecraft.getInstance().execute {
				triggerAnnouncer(config)
			}
		}
	}

	fun onTick(client: Minecraft) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.slayerModuleEnabled || !config.slayerRngMeterDisplayEnabled || !LocationTracker.isOnHypixelSkyBlock) {
			return
		}
		if (System.currentTimeMillis() - config.slayerRngMeterWikiCacheUpdatedAtMs > BACKEND_CACHE_TTL_MS) {
			refreshBackendCacheAsync(config)
		}
		if (++screenScanTickCounter >= SCREEN_SCAN_INTERVAL_TICKS) {
			screenScanTickCounter = 0
			(client.screen as? ContainerScreen)?.let { screen ->
				readRngMeterInventory(config, screen)
			}
		}
	}

	fun onSlotUpdate(screenHandler: AbstractContainerMenu) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.slayerModuleEnabled || !config.slayerRngMeterDisplayEnabled || !LocationTracker.isOnHypixelSkyBlock) {
			return
		}
		val screen = Minecraft.getInstance().screen as? ContainerScreen ?: return
		if (screen.menu != screenHandler) {
			return
		}
		readRngMeterInventory(config, screen)
	}

	fun statusLine(config: BridgeConfig = XclipsenIrcBridgeClient.instance?.config() ?: BridgeConfig()): String {
		val announcer = if (config.slayerSpawnAnnouncerEnabled) "Spawn Announcer enabled" else "Spawn Announcer disabled"
		val rng = if (config.slayerRngMeterDisplayEnabled) "RNG Meter enabled" else "RNG Meter disabled"
		return if (config.slayerModuleEnabled) "$announcer, $rng" else "Slayer disabled"
	}

	fun playPreview(config: BridgeConfig) {
		triggerAnnouncer(config)
	}

	fun shouldDrawAlert(config: BridgeConfig): Boolean {
		return config.slayerModuleEnabled &&
			config.slayerSpawnAnnouncerEnabled &&
			currentAlertText.isNotBlank() &&
			System.currentTimeMillis() <= alertVisibleUntil
	}

	fun currentAlertText(): String = currentAlertText

	fun rngMeterDisplayLines(config: BridgeConfig = XclipsenIrcBridgeClient.instance?.config() ?: BridgeConfig()): List<String> {
		val state = currentRngMeterState(config) ?: return listOf(
			if (config.slayerRngMeterCompactMode) "§cOpen RNG Meter" else "§cOpen RNG Meter Container!"
		)
		val lines = mutableListOf<String>()
		val selectedItem = state.itemGoal.trim()
		val displayItem = selectedItem.takeIf { it.isNotBlank() && it != "?" } ?: state.lastSelectedItemGoal
		if (config.slayerRngMeterCompactMode) {
			return compactRngMeterDisplayLines(config, state, selectedItem, displayItem)
		}
		when {
			state.itemGoal == "?" -> lines += "§cOpen RNG Meter Container!"
			selectedItem.isBlank() -> {
				lines += "§eNo RNG Item selected!"
			}
			state.currentMeter < 0L || state.gainPerBoss <= 0L || state.goalNeeded <= 0L -> {
				lines += if (state.currentMeter >= 0L) "§cKill one slayer boss!" else "§cOpen RNG Meter, then kill one boss!"
			}
			else -> {
				val bosses = bossesUntilFull(state)
				lines += "§f$selectedItem §7in §e${formatNumber(bosses.toLong())} §7bosses!"
			}
		}

		if (config.slayerRngMeterOptimalRemovalEnabled &&
			displayItem.isNotBlank() &&
			state.currentMeter >= 0L &&
			state.gainPerBoss > 0L &&
			state.goalNeeded > 0L
		) {
			optimalRemovalLine(config, state, displayItem)?.let(lines::add)
		}
		return lines
	}

	private fun compactRngMeterDisplayLines(
		config: BridgeConfig,
		state: SlayerRngMeterState,
		selectedItem: String,
		displayItem: String,
	): List<String> {
		return when {
			state.itemGoal == "?" -> listOf("§cOpen RNG Meter")
			selectedItem.isBlank() -> listOf("§eNo RNG Item")
			state.currentMeter < 0L || state.gainPerBoss <= 0L || state.goalNeeded <= 0L -> {
				listOf(if (state.currentMeter >= 0L) "§cKill one boss" else "§cOpen meter first")
			}
			else -> {
				val item = compactItemName(displayItem.takeIf { it.isNotBlank() } ?: selectedItem)
				val bosses = formatNumber(bossesUntilFull(state).toLong())
				val removal = if (config.slayerRngMeterOptimalRemovalEnabled && displayItem.isNotBlank()) {
					compactOptimalRemovalText(config, state)
				} else {
					null
				}
				val line = if (removal == null) {
					"§f$item§7: §e$bosses §7bosses"
				} else {
					"§f$item§7: §e$bosses §7bosses §8| $removal"
				}
				listOf(line)
			}
		}
	}

	fun shouldDrawRngMeter(config: BridgeConfig): Boolean {
		return config.slayerModuleEnabled &&
			config.slayerRngMeterDisplayEnabled &&
			LocationTracker.isOnHypixelSkyBlock &&
			rngMeterDisplayLines(config).any { it.isNotBlank() }
	}

	private fun handleRngMeterChatUpdate(config: BridgeConfig, message: Component?) {
		if (!config.slayerRngMeterDisplayEnabled) {
			return
		}
		val clean = normalize(message?.string ?: return)
		SLAYER_LEVEL_PATTERN.matchEntire(clean)?.let { match ->
			slayerTypeToRngMeterSlayer(match.groupValues[1])?.let { slayer ->
				lastSlayerFromChat = slayer
				config.slayerRngMeterActiveSlayer = slayer
				stateFor(config, slayer)
				saveCurrentConfigThrottled()
			}
			return
		}
		RNG_METER_RESET_ITEM_PATTERN.matchEntire(clean)?.let { match ->
			val slayer = normalize(match.groupValues[1])
			if (slayer.isNotBlank()) {
				lastSlayerFromChat = slayer
				config.slayerRngMeterActiveSlayer = slayer
				stateFor(config, slayer).itemGoal = ""
				saveCurrentConfigThrottled()
			}
			return
		}
		RNG_METER_RESELECTED_ITEM_PATTERN.matchEntire(clean)?.let { match ->
			val item = normalizeItemName(match.groupValues[1])
			val slayer = slayerTypeToRngMeterSlayer(match.groupValues[2]) ?: normalize(match.groupValues[2])
			if (slayer.isNotBlank() && item.isNotBlank()) {
				lastSlayerFromChat = slayer
				config.slayerRngMeterActiveSlayer = slayer
				val state = stateFor(config, slayer)
				state.itemGoal = item
				state.lastSelectedItemGoal = item
				state.currentMeter = 0L
				state.gainPerBoss = -1L
				fillStateFromCache(config, slayer, state)
				saveCurrentConfigThrottled()
			}
			return
		}
		RNG_METER_CHANGED_ITEM_PATTERN.matchEntire(clean)?.let { match ->
			val slayer = normalize(match.groupValues[1])
			val item = normalizeItemName(match.groupValues[2])
			if (slayer.isNotBlank() && item.isNotBlank()) {
				lastSlayerFromChat = slayer
				config.slayerRngMeterActiveSlayer = slayer
				val state = stateFor(config, slayer)
				state.itemGoal = item
				state.lastSelectedItemGoal = item
				fillStateFromCache(config, slayer, state)
				saveCurrentConfigThrottled()
			}
			return
		}
		RARE_DROP_PATTERN.matchEntire(clean)?.let { match ->
			val item = normalizeItemName(match.groupValues[1])
			resetSelectedRngMeterDrop(config, item)
			return
		}

		val match = RNG_METER_UPDATE_PATTERN.matchEntire(clean) ?: return
		val currentMeter = parseCompactLong(match.groupValues[1]) ?: return
		val state = rngMeterStateForChatUpdate(config) ?: return
		val old = state.currentMeter
		state.currentMeter = currentMeter
		if (old >= 0L) {
			val diff = currentMeter - old
				if (diff > 0L) {
					state.gainPerBoss = diff
				} else if (diff < 0L) {
					if (currentMeter > 0L) {
						state.gainPerBoss = currentMeter
					}
				}
			}
			saveCurrentConfigThrottled()
		}

	private fun readRngMeterInventory(config: BridgeConfig, screen: ContainerScreen) {
		val title = normalize(screen.title.string)
		if (SLAYER_RNG_METER_OVERVIEW_TITLES.any { title.equals(it, ignoreCase = true) }) {
			readSlayerRngMeterOverview(config, screen)
			return
		}

		val slayer = RNG_METER_TITLE_PATTERN.matchEntire(title)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: return
		config.slayerRngMeterActiveSlayer = slayer
		val state = stateFor(config, slayer)

		val menuSlots = screen.menu.slots.filter { slot -> slot.container !is Inventory }
		val parsedDrops = menuSlots.mapNotNull { slot -> parseRngMeterDrop(slayer, slot) }
		if (parsedDrops.isEmpty()) {
			saveCurrentConfigThrottled()
			return
		}

		parsedDrops.forEach { drop ->
			config.slayerRngMeterWikiCache[cacheKey(drop.slayer, drop.item)] = SlayerRngMeterDropCache().also {
				it.slayer = drop.slayer
				it.item = drop.item
				it.oddsPercent = drop.oddsPercent
				it.goalNeeded = drop.goalNeeded
			}
		}

		val selected = parsedDrops.firstOrNull { it.selected }
		if (selected == null) {
			state.itemGoal = ""
			val current = parsedDrops.firstOrNull { it.currentMeter >= 0L }?.currentMeter
			if (current != null) {
				state.currentMeter = current
			}
			fillStateFromCache(config, slayer, state)
		} else {
			state.itemGoal = selected.item
			state.lastSelectedItemGoal = selected.item
			state.currentMeter = selected.currentMeter
			state.goalNeeded = selected.goalNeeded
			state.oddsPercent = selected.oddsPercent
			state.lastSelectedOddsPercent = selected.oddsPercent
		}
		saveCurrentConfigThrottled()
	}

	private fun readSlayerRngMeterOverview(config: BridgeConfig, screen: ContainerScreen) {
		val menuSlots = screen.menu.slots.filter { slot -> slot.container !is Inventory }
		val parsedSlots = menuSlots.mapNotNull(::parseSlayerRngMeterOverviewSlot)
		val parsed = parsedSlots.firstOrNull { it.item.isNotBlank() }
			?: parsedSlots.firstOrNull { it.slayer.equals(config.slayerRngMeterActiveSlayer, ignoreCase = true) }
			?: parsedSlots.firstOrNull()
			?: run {
			saveCurrentConfigThrottled()
			return
		}
		config.slayerRngMeterActiveSlayer = parsed.slayer
		val state = stateFor(config, parsed.slayer)
		if (parsed.item.isBlank()) {
			if (state.lastSelectedItemGoal.isBlank()) {
				state.itemGoal = ""
			}
			if (parsed.currentMeter >= 0L) {
				state.currentMeter = parsed.currentMeter
			}
			if (parsed.goalNeeded > 0L) {
				state.goalNeeded = parsed.goalNeeded
			}
			fillStateFromCache(config, parsed.slayer, state)
		} else {
			state.itemGoal = parsed.item
			state.lastSelectedItemGoal = parsed.item
			state.currentMeter = parsed.currentMeter
			state.goalNeeded = parsed.goalNeeded
			fillStateFromCache(config, parsed.slayer, state)
		}
		saveCurrentConfigThrottled()
	}

	private fun parseSlayerRngMeterOverviewSlot(slot: Slot): ParsedRngMeterOverview? {
		val stack = slot.item
		if (stack.isEmpty) {
			return null
		}
		val lore = loreLines(stack).map(::normalize)
		if (lore.isEmpty()) {
			return null
		}
		val allLines = listOf(normalize(stack.hoverName.string)) + lore
		val slayer = allLines.firstNotNullOfOrNull { line ->
			RNG_METER_TITLE_PATTERN.matchEntire(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
		} ?: return null

		val selectedDrop = selectedDropFromOverviewLore(lore)

		val progressLine = lore.firstNotNullOfOrNull { line -> RNG_METER_PROGRESS_PATTERN.find(line) }
		val current = progressLine?.groupValues?.getOrNull(1)?.let(::parseCompactLong) ?: -1L
		val goal = progressLine?.groupValues?.getOrNull(2)?.let(::parseCompactLong) ?: -1L
		return ParsedRngMeterOverview(
			slayer = slayer,
			item = selectedDrop,
			currentMeter = current,
			goalNeeded = goal,
		)
	}

	private fun selectedDropFromOverviewLore(lore: List<String>): String {
		for (index in lore.indices) {
			val line = lore[index]
			val inlineMatch = SELECTED_DROP_INLINE_PATTERN.matchEntire(line)
			if (inlineMatch != null) {
				return normalizeItemName(inlineMatch.groupValues[1])
			}
			if (!line.equals("Selected Drop", ignoreCase = true)) {
				continue
			}
			for (candidate in lore.drop(index + 1)) {
				if (candidate.isBlank()) {
					continue
				}
				if (candidate.startsWith("Progress:", ignoreCase = true) ||
					candidate.startsWith("MouseButtonEvent to", ignoreCase = true) ||
					candidate.startsWith("minecraft:", ignoreCase = true)
				) {
					return ""
				}
				return normalizeItemName(candidate)
			}
		}
		return ""
	}

	private fun parseRngMeterDrop(slayer: String, slot: Slot): ParsedRngMeterDrop? {
		val stack = slot.item
		if (stack.isEmpty) {
			return null
		}
		val lore = loreLines(stack)
		if (lore.isEmpty()) {
			return null
		}
		val joined = lore.joinToString(" ") { normalize(it) }
		val xpMatch = SLAYER_XP_PATTERN.find(joined) ?: RNG_METER_PROGRESS_PATTERN.find(joined) ?: return null
		val current = parseCompactLong(xpMatch.groupValues[1]) ?: return null
			val goal = parseCompactLong(xpMatch.groupValues[2]) ?: return null
			val odds = parseOddsPercent(lore) ?: return null
			val item = normalizeItemName(stack.hoverName.string).takeIf { it.isNotBlank() } ?: return null
			if (isRngMeterInfoItemName(item)) {
				return null
			}
			return ParsedRngMeterDrop(
			slayer = slayer,
			item = item,
			currentMeter = current,
			goalNeeded = goal,
			oddsPercent = odds,
			selected = lore.any { normalize(it).equals("SELECTED", ignoreCase = true) },
		)
	}

	private fun parseOddsPercent(lore: List<String>): Double? {
		val oddsLine = lore.map(::normalize).firstOrNull { it.contains("Odds:", ignoreCase = true) } ?: return null
		val percentages = ODDS_PERCENT_PATTERN.findAll(oddsLine).mapNotNull { match ->
			match.groupValues.getOrNull(1)?.toDoubleOrNull()
		}.toList()
		return percentages.firstOrNull()
	}

	private fun fillStateFromCache(config: BridgeConfig, slayer: String, state: SlayerRngMeterState) {
		val item = state.lastSelectedItemGoal.takeIf { it.isNotBlank() } ?: return
		val cache = config.slayerRngMeterWikiCache[cacheKey(slayer, item)] ?: return
		if (state.goalNeeded <= 0L) {
			state.goalNeeded = cache.goalNeeded
		}
		if (state.oddsPercent <= 0.0) {
			state.oddsPercent = cache.oddsPercent
		}
		if (state.lastSelectedOddsPercent <= 0.0) {
			state.lastSelectedOddsPercent = cache.oddsPercent
		}
	}

	private fun currentRngMeterState(config: BridgeConfig): SlayerRngMeterState? {
		val slayer = config.slayerRngMeterActiveSlayer.takeIf { it.isNotBlank() } ?: return null
		return stateFor(config, slayer)
	}

	private fun rngMeterStateForChatUpdate(config: BridgeConfig): SlayerRngMeterState? {
		val slayer = lastSlayerFromChat.ifBlank { config.slayerRngMeterActiveSlayer }
			.ifBlank { config.slayerRngMeterState.keys.singleOrNull().orEmpty() }
			.takeIf { it.isNotBlank() }
			?: return null
		config.slayerRngMeterActiveSlayer = slayer
		return stateFor(config, slayer)
	}

	private fun resetSelectedRngMeterDrop(config: BridgeConfig, droppedItem: String) {
		if (droppedItem.isBlank()) {
			return
		}
		val slayer = config.slayerRngMeterActiveSlayer.takeIf { it.isNotBlank() } ?: lastSlayerFromChat.takeIf { it.isNotBlank() } ?: return
		val state = stateFor(config, slayer)
		if (!state.itemGoal.equals(droppedItem, ignoreCase = true)) {
			return
		}
		state.currentMeter = 0L
		state.gainPerBoss = -1L
		saveCurrentConfigThrottled()
	}

	private fun stateFor(config: BridgeConfig, slayer: String): SlayerRngMeterState {
		return config.slayerRngMeterState.getOrPut(slayer) { SlayerRngMeterState() }.also { state ->
			if (isRngMeterInfoItemName(state.itemGoal)) {
				state.itemGoal = ""
				state.oddsPercent = -1.0
			}
			if (isRngMeterInfoItemName(state.lastSelectedItemGoal)) {
				state.lastSelectedItemGoal = ""
				state.lastSelectedOddsPercent = -1.0
			}
		}
	}

	private fun optimalRemovalLine(config: BridgeConfig, state: SlayerRngMeterState, displayItem: String): String? {
		val oddsPercent = when {
			state.itemGoal.isNotBlank() && state.itemGoal != "?" && state.oddsPercent > 0.0 -> state.oddsPercent
			state.lastSelectedOddsPercent > 0.0 -> state.lastSelectedOddsPercent
			else -> return "§cOpen RNG Meter Container for odds"
		}
		val threshold = optimalRemovalThreshold(
			goalNeeded = state.goalNeeded,
			gainPerBoss = state.gainPerBoss,
			baseChance = oddsPercent / 100.0,
			useMagicFind = config.slayerRngMeterUseMagicFind,
			magicFind = config.slayerRngMeterMagicFind.coerceIn(0, 900),
		)
		if (state.itemGoal.isBlank()) {
			return "§a$displayItem removed - farm to full meter"
		}
		return when {
			threshold <= 0L -> "§eRemove immediately"
			threshold >= state.goalNeeded -> "§aKeep selected until full"
			state.currentMeter >= threshold -> "§cRemove $displayItem now"
			else -> {
				val bosses = ceil((threshold - state.currentMeter).coerceAtLeast(0L).toDouble() / state.gainPerBoss.toDouble())
					.toLong()
					.coerceAtLeast(1L)
				"§eRemove at ${formatNumber(threshold)} XP §7(${formatNumber(bosses)} bosses)"
			}
		}
	}

	private fun compactOptimalRemovalText(config: BridgeConfig, state: SlayerRngMeterState): String? {
		val oddsPercent = when {
			state.itemGoal.isNotBlank() && state.itemGoal != "?" && state.oddsPercent > 0.0 -> state.oddsPercent
			state.lastSelectedOddsPercent > 0.0 -> state.lastSelectedOddsPercent
			else -> return "§copen odds"
		}
		val threshold = optimalRemovalThreshold(
			goalNeeded = state.goalNeeded,
			gainPerBoss = state.gainPerBoss,
			baseChance = oddsPercent / 100.0,
			useMagicFind = config.slayerRngMeterUseMagicFind,
			magicFind = config.slayerRngMeterMagicFind.coerceIn(0, 900),
		)
		if (state.itemGoal.isBlank()) {
			return "§afarm full"
		}
		return when {
			threshold <= 0L -> "§eremove now"
			threshold >= state.goalNeeded -> "§akeep to full"
			state.currentMeter >= threshold -> "§cremove now"
			else -> {
				val bosses = ceil((threshold - state.currentMeter).coerceAtLeast(0L).toDouble() / state.gainPerBoss.toDouble())
					.toLong()
					.coerceAtLeast(1L)
				"§eremove in ${formatNumber(bosses)}"
			}
		}
	}

	private fun compactItemName(item: String): String {
		val compact = item
			.replace("High Class ", "HC ")
			.replace("Enchanted Book (", "Book: ")
			.removeSuffix(")")
		return if (compact.length <= 28) compact else compact.take(25).trimEnd() + "..."
	}

	private fun optimalRemovalThreshold(
		goalNeeded: Long,
		gainPerBoss: Long,
		baseChance: Double,
		useMagicFind: Boolean,
		magicFind: Int,
	): Long {
		if (goalNeeded <= 0L || gainPerBoss <= 0L || baseChance <= 0.0) {
			return goalNeeded
		}
		val steps = ceil(goalNeeded.toDouble() / gainPerBoss.toDouble()).toInt().coerceAtLeast(1)
		var bestThreshold = 0L
		var bestValue = Double.NEGATIVE_INFINITY
		for (thresholdStep in 0..steps) {
			val threshold = min(goalNeeded, thresholdStep.toLong() * gainPerBoss)
			val value = expectedDropsPerBoss(goalNeeded, gainPerBoss, baseChance, threshold, steps, useMagicFind, magicFind)
			if (value > bestValue * (1.0 + 0.0005)) {
				bestValue = value
				bestThreshold = threshold
			}
		}
		return bestThreshold
	}

	private fun expectedDropsPerBoss(
		goalNeeded: Long,
		gainPerBoss: Long,
		baseChance: Double,
		threshold: Long,
		steps: Int,
		useMagicFind: Boolean,
		magicFind: Int,
	): Double {
		val expectedDrops = DoubleArray(steps + 1)
		val expectedBosses = DoubleArray(steps + 1)
		expectedDrops[steps] = 1.0
		expectedBosses[steps] = 1.0
		for (step in steps - 1 downTo 0) {
			val currentXp = min(goalNeeded, step.toLong() * gainPerBoss)
			val nextStep = (step + 1).coerceAtMost(steps)
			val selected = currentXp < threshold
			val chance = effectiveDropChance(baseChance, currentXp, goalNeeded, selected, useMagicFind, magicFind)
			if (selected) {
				expectedDrops[step] = chance + ((1.0 - chance) * expectedDrops[nextStep])
				expectedBosses[step] = 1.0 + ((1.0 - chance) * expectedBosses[nextStep])
			} else {
				expectedDrops[step] = chance + expectedDrops[nextStep]
				expectedBosses[step] = 1.0 + expectedBosses[nextStep]
			}
		}
		return expectedDrops[0] / expectedBosses[0].coerceAtLeast(1.0)
	}

	private fun effectiveDropChance(
		baseChance: Double,
		currentXp: Long,
		goalNeeded: Long,
		selected: Boolean,
		useMagicFind: Boolean,
		magicFind: Int,
	): Double {
		val meterMultiplier = if (selected) {
			1.0 + (2.0 * min(currentXp, goalNeeded).toDouble() / goalNeeded.toDouble())
		} else {
			1.0
		}
		var chance = (baseChance * meterMultiplier).coerceIn(0.0, 1.0)
		if (useMagicFind && chance < 0.05) {
			chance *= 1.0 + (magicFind.coerceIn(0, 900).toDouble() / 100.0)
		}
		return chance.coerceIn(0.0, 1.0)
	}

	private fun bossesUntilFull(state: SlayerRngMeterState): Long {
		val missing = state.goalNeeded - state.currentMeter + state.gainPerBoss
		return ceil(missing.coerceAtLeast(state.gainPerBoss).toDouble() / state.gainPerBoss.toDouble())
			.toLong()
			.coerceAtLeast(1L)
	}

	private fun refreshBackendCacheAsync(config: BridgeConfig) {
		if (wikiFetchInFlight) {
			return
		}
		wikiFetchInFlight = true
		val generation = ClientSessionLifecycle.snapshot()
		backendExecutor.execute {
			try {
				val response = XclipsenIrcBridgeClient.instance?.backendBridge()?.fetchSlayerRngMeterDrops()
				val parsed = response?.drops
					?.filter { drop -> drop.slayer.isNotBlank() && drop.item.isNotBlank() && drop.oddsPercent > 0.0 && drop.goalNeeded > 0L }
					?.associate { drop ->
						cacheKey(drop.slayer, drop.item) to SlayerRngMeterDropCache().also {
							it.slayer = drop.slayer
							it.item = drop.item
							it.oddsPercent = drop.oddsPercent
							it.goalNeeded = drop.goalNeeded
						}
					}
					.orEmpty()
				if (parsed.isNotEmpty()) {
					Minecraft.getInstance().execute {
						if (!ClientSessionLifecycle.isCurrent(generation)) return@execute
						config.slayerRngMeterWikiCache.putAll(parsed)
						config.slayerRngMeterWikiCacheUpdatedAtMs = response?.updatedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
						saveCurrentConfig()
					}
				}
			} catch (_: Exception) {
				Minecraft.getInstance().execute {
					if (!ClientSessionLifecycle.isCurrent(generation)) return@execute
					config.slayerRngMeterWikiCacheUpdatedAtMs = System.currentTimeMillis()
					saveCurrentConfig()
				}
			} finally {
				if (ClientSessionLifecycle.isCurrent(generation)) wikiFetchInFlight = false
			}
		}
	}

	private fun saveCurrentConfigThrottled() {
		val now = System.currentTimeMillis()
		if (now - lastStateSaveAt < STATE_SAVE_INTERVAL_MS) {
			return
		}
		lastStateSaveAt = now
		saveCurrentConfig()
	}

	private fun saveCurrentConfig() {
		try {
			XclipsenIrcBridgeClient.instance?.saveCurrentConfig()
		} catch (_: Exception) {
		}
	}

	private fun loreLines(stack: ItemStack): List<String> {
		return stack.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList()
	}

	private fun normalizeItemName(raw: String): String {
		val clean = normalize(raw).trimEnd('!', '.')
		val book = BOOK_NAME_PATTERN.matchEntire(clean)?.groupValues?.getOrNull(1)?.trim()
		return book ?: clean
	}

	private fun isRngMeterInfoItemName(item: String): Boolean {
		return RNG_METER_TITLE_PATTERN.matchEntire(normalize(item)) != null
	}

	private fun slayerTypeToRngMeterSlayer(type: String): String? {
		return when (type.lowercase(Locale.ROOT)) {
			"zombie" -> "Revenant Horror"
			"spider" -> "Tarantula Broodfather"
			"wolf" -> "Sven Packmaster"
			"enderman" -> "Voidgloom Seraph"
			"blaze" -> "Inferno Demonlord"
			"vampire" -> "Riftstalker Bloodfiend"
			else -> null
		}
	}

	private fun triggerAnnouncer(config: BridgeConfig) {
		val text = renderAnnouncerText(config)
		if (text.isNotBlank()) {
			currentAlertText = text
			alertVisibleUntil = System.currentTimeMillis() + ALERT_VISIBLE_MS
		}

		Minecraft.getInstance().soundManager.play(
			SoundCatalog.masterSound(
				config.slayerSpawnAnnouncerSoundId,
				config.slayerSpawnAnnouncerSoundPitch.coerceIn(0.1f, 2.0f),
				config.slayerSpawnAnnouncerSoundVolume.coerceIn(0.0f, 2.0f),
			),
		)
	}

	private fun renderAnnouncerText(config: BridgeConfig): String {
		return translateAmpersandFormatting(config.slayerSpawnAnnouncerText.ifBlank { DEFAULT_ANNOUNCER_TEXT })
	}

	private fun isSlayerSpawnPetRule(message: Component): Boolean {
		val visibleText = normalize(message.string)
		if (!visibleText.contains("Autopet equipped your", ignoreCase = true) || !visibleText.contains("VIEW RULE", ignoreCase = true)) {
			return false
		}

		return collectHoverTexts(message).any { hoverText ->
			val normalized = normalize(hoverText)
			normalized.contains("Autopet Rule", ignoreCase = true) &&
				normalized.contains("When: Any Slayer Boss spawns", ignoreCase = true)
		}
	}

	private fun collectHoverTexts(text: Component): List<String> {
		val result = mutableListOf<String>()
		collectHoverTexts(text, result)
		return result
	}

	private fun collectHoverTexts(text: Component, result: MutableList<String>) {
		val hoverEvent = text.style.hoverEvent
		if (hoverEvent is HoverEvent.ShowText) {
			result += hoverEvent.value().string
		}

		text.siblings.forEach { sibling -> collectHoverTexts(sibling, result) }
	}

	private fun normalize(raw: String): String {
		return ChatFormatting.stripFormatting(stripMinecraftFormatting(raw)).orEmpty()
			.replace('\r', ' ')
			.replace('\n', ' ')
			.replace(Regex("\\s+"), " ")
			.trim()
	}

	private fun parseCompactLong(raw: String): Long? {
		val clean = raw.trim().replace(",", "")
		if (clean.isBlank()) {
			return null
		}
		val multiplier = when (clean.last().lowercaseChar()) {
			'k' -> 1_000.0
			'm' -> 1_000_000.0
			else -> 1.0
		}
		val number = if (multiplier == 1.0) clean else clean.dropLast(1)
		return number.toDoubleOrNull()?.times(multiplier)?.toLong()
	}

	private fun formatNumber(value: Long): String = String.format(Locale.ROOT, "%,d", value)

	private fun cacheKey(slayer: String, item: String): String =
		"${slayer.lowercase(Locale.ROOT)}|${item.lowercase(Locale.ROOT)}"

	private fun stripMinecraftFormatting(input: String): String {
		if (!input.contains('§')) {
			return input
		}

		val builder = StringBuilder(input.length)
		var skip = false
		for (character in input) {
			if (skip) {
				skip = false
				continue
			}

			if (character == '§') {
				skip = true
				continue
			}

			builder.append(character)
		}
		return builder.toString()
	}

	private fun translateAmpersandFormatting(input: String): String {
		if (!input.contains('&')) {
			return input
		}

		val builder = StringBuilder(input.length)
		var index = 0
		while (index < input.length) {
			val current = input[index]
			if (current == '&' && index + 1 < input.length && FORMATTING_CODE_PATTERN.matches(input[index + 1].toString())) {
				builder.append('§').append(input[index + 1].lowercaseChar())
				index += 2
				continue
			}

			builder.append(current)
			index++
		}
		return builder.toString()
	}

	private val FORMATTING_CODE_PATTERN = Regex("[0-9A-FK-ORa-fk-or]")
	private val SLAYER_RNG_METER_OVERVIEW_TITLES = setOf("Slayer RNG Meter", "Slayer RNG Meters")
	private val SLAYER_LEVEL_PATTERN = Regex("(Zombie|Spider|Wolf|Enderman|Blaze|Vampire) Slayer LVL\\s+\\d+.*", RegexOption.IGNORE_CASE)
	private val RNG_METER_UPDATE_PATTERN = Regex("RNG Meter\\s+-\\s+([\\d,.kKmM]+) Stored XP", RegexOption.IGNORE_CASE)
	private val RNG_METER_CHANGED_ITEM_PATTERN = Regex("You set your (.+?) RNG Meter to drop (.+?)[!.]?", RegexOption.IGNORE_CASE)
	private val RNG_METER_RESET_ITEM_PATTERN = Regex("You reset your selected drop for your (.+?) RNG Meter[!.]?", RegexOption.IGNORE_CASE)
	private val RNG_METER_RESELECTED_ITEM_PATTERN = Regex("RNG METER!\\s+Reselected the\\s+(.+?)\\s+for\\s+(.+?)\\s+Slayer!.*", RegexOption.IGNORE_CASE)
	private val RARE_DROP_PATTERN = Regex("^(?:VERY RARE|CRAZY RARE|INSANE|RARE) DROP!\\s+\\(?(.+?)\\)?(?:\\s+\\(\\+.*Magic Find\\))?$", RegexOption.IGNORE_CASE)
	private val RNG_METER_TITLE_PATTERN = Regex("(.+) RNG Meters?", RegexOption.IGNORE_CASE)
	private val SELECTED_DROP_INLINE_PATTERN = Regex("Selected Drop\\s*:?\\s*(.+)", RegexOption.IGNORE_CASE)
	private val RNG_METER_PROGRESS_PATTERN = Regex("([\\d,.kKmM]+)\\s*/\\s*([\\d,.kKmM]+)", RegexOption.IGNORE_CASE)
	private val ODDS_PERCENT_PATTERN = Regex("([\\d.]+)%")
	private val SLAYER_XP_PATTERN = Regex("Slayer XP:\\s*([\\d,.kKmM]+)\\s*/\\s*([\\d,.kKmM]+)", RegexOption.IGNORE_CASE)
	private val BOOK_NAME_PATTERN = Regex("Enchanted Book\\s*\\((.+)\\)", RegexOption.IGNORE_CASE)
	private data class ParsedRngMeterOverview(
		val slayer: String,
		val item: String,
		val currentMeter: Long,
		val goalNeeded: Long,
	)

	private data class ParsedRngMeterDrop(
		val slayer: String,
		val item: String,
		val currentMeter: Long,
		val goalNeeded: Long,
		val oddsPercent: Double,
		val selected: Boolean,
	)
}

object SlayerSpawnAnnouncerHudElement : XclipsenHudElement(
	id = "slayer_spawn_announcer",
	displayName = "Slayer Spawn Announcer",
) {
	override fun isEnabled(config: BridgeConfig): Boolean =
		config.slayerModuleEnabled && config.slayerSpawnAnnouncerEnabled

	override fun shouldDraw(config: BridgeConfig): Boolean =
		isEnabled(config) && SlayerFeature.shouldDrawAlert(config)

	override fun defaultX(context: GuiGraphicsExtractor): Float {
		return ((context.guiWidth() - DEFAULT_WIDTH) / 2f).coerceAtLeast(4f)
	}

	override fun defaultY(context: GuiGraphicsExtractor): Float {
		return (context.guiHeight() * 0.28f).coerceAtLeast(28f)
	}

	override fun draw(context: GuiGraphicsExtractor, example: Boolean): Tuple<Float, Float> {
		val client = Minecraft.getInstance()
		val textRenderer = client.font
		val text = if (example) SlayerFeature.DEFAULT_ANNOUNCER_TEXT else SlayerFeature.currentAlertText()
		val width = max(DEFAULT_WIDTH, textRenderer.width(text) + (PADDING_X * 2))
		val height = PADDING_Y + textRenderer.lineHeight + PADDING_Y

		context.text(textRenderer, text, (width - textRenderer.width(text)) / 2, PADDING_Y, TEXT_COLOR, true)
		return width.toFloat() to height.toFloat()
	}

	private const val DEFAULT_WIDTH = 180
	private const val PADDING_X = 8
	private const val PADDING_Y = 6
	private const val TEXT_COLOR = 0xFFFFFFFF.toInt()
}

object SlayerRngMeterHudElement : XclipsenHudElement(
	id = "slayer_rng_meter",
	displayName = "Slayer RNG Meter",
) {
	override fun isEnabled(config: BridgeConfig): Boolean =
		config.slayerModuleEnabled && config.slayerRngMeterDisplayEnabled

	override fun shouldDraw(config: BridgeConfig): Boolean =
		isEnabled(config) && SlayerFeature.shouldDrawRngMeter(config)

	override fun defaultX(context: GuiGraphicsExtractor): Float = 410f.coerceAtMost((context.guiWidth() - 120).coerceAtLeast(4).toFloat())

	override fun defaultY(context: GuiGraphicsExtractor): Float = 110f.coerceAtMost((context.guiHeight() - 20).coerceAtLeast(4).toFloat())

		override fun draw(context: GuiGraphicsExtractor, example: Boolean): Tuple<Float, Float> {
			val client = Minecraft.getInstance()
			val renderer = client.font
			val config = XclipsenIrcBridgeClient.instance?.config()
			val lines = if (example) {
				if (config?.slayerRngMeterCompactMode == true) {
					listOf("§fHC Archfiend Dice§7: §e351 §7bosses §8| §eremove in 208")
				} else {
					listOf("§fHigh Class Archfiend Dice §7in §e351 §7bosses!", "§eRemove at 112,800 XP §7(208 bosses)")
				}
			} else {
				SlayerFeature.rngMeterDisplayLines()
			}
		var width = 1
		var y = 0
		for (line in lines) {
			context.text(renderer, line, 0, y, 0xFFFFFFFF.toInt(), true)
			width = max(width, renderer.width(line))
			y += renderer.lineHeight + LINE_GAP
		}
		return width.toFloat() to (y - LINE_GAP).coerceAtLeast(renderer.lineHeight).toFloat()
	}

	private const val LINE_GAP = 2
}
