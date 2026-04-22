package de.xclipsen.ircbridge

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Hideonleaf Shards Profit Tracker — inspired by SkyHanni profit trackers.
 *
 * Detects shard and item drops from chat messages, tracks session/total stats,
 * and renders a draggable HUD overlay with profit-per-hour calculations.
 */
object HideonleafShardTracker {

	private val LOGGER = LoggerFactory.getLogger("xclipsen_shard_tracker")
	private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
	private val DATA_PATH: Path = FabricLoader.getInstance().configDir.resolve("xclipsen-shard-tracker.json")

	// ── Chat detection patterns ──────────────────────────────────────────
	// Adjust these to match the exact chat messages on your server.
	// Each pattern must have named groups "amount" and "item".

	private val DROP_PATTERNS: List<Regex> = listOf(
		// PRIMARY: "You caught x2 Hideonleaf Shards!" (exact server format)
		Regex("^You caught x(?<amount>\\d+)\\s+(?<item>.+?)!?\\s*$"),
		// Fallback: "+1 Hideonleaf Shard" / "+5 Shards"
		Regex("^\\+(?<amount>\\d+)\\s+(?<item>.+?)\\s*$"),
		// Fallback: "RARE DROP! Hideonleaf Shard (1x)"
		Regex("^RARE DROP!\\s+(?<item>.+?)(?:\\s*\\((?<amount>\\d+)x\\))?\\s*$"),
	)

	// Only track items whose name contains one of these keywords (case-insensitive).
	// Set to empty to track ALL detected drops.
	private val ITEM_KEYWORDS: List<String> = listOf(
		"shard", "hideonleaf", "fragment", "essence", "crystal",
	)

	// ── Fallback prices (used before live Bazaar prices arrive) ──────────
	// Source: Hypixel Bazaar sell-price, approximate as of 2026-04.
	private val DEFAULT_PRICES: Map<String, Double> = mapOf(
		"Hideonleaf Shards"  to  68_000.0,   // SHARD_HIDEONLEAF
		"Hideonbox Shards"   to 1_475_506.0, // SHARD_HIDEONBOX
		"Hideoncave Shards"  to   124_312.0, // SHARD_HIDEONCAVE
		"Hideondra Shards"   to   377_313.0, // SHARD_HIDEONDRA
		"Hideongeon Shards"  to   856_001.0, // SHARD_HIDEONGEON
		"Hideongift Shards"  to    50_000.0, // SHARD_HIDEONGIFT
		"Hideonring Shards"  to   914_635.0, // SHARD_HIDEONRING
		"Hideonsack Shards"  to   959_222.0, // SHARD_HIDEONSACK
	)

	// ── Price refresh ────────────────────────────────────────────────────
	// Fetches live Bazaar prices from the bot backend every 5 minutes.

	private const val PRICE_REFRESH_INTERVAL_MINUTES = 5L

	// ── AFK detection ────────────────────────────────────────────────────
	// Timer pauses after this many ms without any drop or kill.
	private const val AFK_THRESHOLD_MS = 1 * 60 * 1_000L   // 1 minute
	private var priceScheduler: ScheduledExecutorService? = null

	/** Last successful prices from the backend (display name → sell price). Updated on background thread. */
	@Volatile
	private var livePrices: Map<String, Double> = emptyMap()

	/** Timestamp of the last successful price refresh (epoch ms). */
	@Volatile
	var pricesLastRefreshedAt: Long = 0L
		private set

	// ── State ────────────────────────────────────────────────────────────

	private var totalData: TrackerData = TrackerData()
	private var sessionData: TrackerData = TrackerData()

	@Volatile
	var showingSession: Boolean = true
		private set

	@Volatile
	var sessionActive: Boolean = false
		private set

	/** True while the per-session stopwatch is actually counting up. */
	@Volatile
	var timerRunning: Boolean = false
		private set

	/** Epoch-ms of the last recorded drop or kill — used for AFK detection. */
	@Volatile
	private var lastActivityAt: Long = 0L

	private var sessionStartedAt: Long = 0L

	// ── Data classes ─────────────────────────────────────────────────────

	data class TrackerData(
		@JvmField var items: MutableMap<String, TrackedItem> = mutableMapOf(),
		@JvmField var kills: Long = 0,
		@JvmField var totalDurationMs: Long = 0,
	)

	data class TrackedItem(
		@JvmField var amount: Long = 0,
		@JvmField var timesDropped: Long = 0,
		@JvmField var pricePerUnit: Double = 0.0,
	)

	// ── Initialisation ───────────────────────────────────────────────────

	fun init() {
		totalData = loadData()
		resetSession()
		startPriceRefresher()
	}

	private fun startPriceRefresher() {
		priceScheduler?.shutdownNow()
		priceScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "xclipsen-price-refresher").apply { isDaemon = true }
		}
		// First fetch after 5 s so the mod finishes loading; then every 5 min.
		priceScheduler?.scheduleAtFixedRate(
			::refreshPrices,
			5L,
			PRICE_REFRESH_INTERVAL_MINUTES * 60L,
			TimeUnit.SECONDS,
		)
	}

	private fun refreshPrices() {
		val backend = XclipsenIrcBridgeClient.instance?.backendBridge() ?: return
		val payload = backend.fetchSkyblockPrices() ?: return

		val fresh = mutableMapOf<String, Double>()
		for ((displayName, price) in payload.prices) {
			// Use sell-price (what you get when you sell instantly on the Bazaar)
			val value = price.sellPrice
			if (value > 0) fresh[displayName] = value
		}

		livePrices = fresh
		pricesLastRefreshedAt = System.currentTimeMillis()
		LOGGER.info("Shard tracker: prices refreshed — {}", fresh)

		// Retroactively apply live prices to all already-tracked items
		applyLivePricesToData(totalData, fresh)
		applyLivePricesToData(sessionData, fresh)
	}

	private fun applyLivePricesToData(data: TrackerData, prices: Map<String, Double>) {
		for ((name, item) in data.items) {
			val livePrice = prices[name]
			if (livePrice != null && livePrice > 0) {
				item.pricePerUnit = livePrice
			}
		}
	}

	// ── Chat processing ──────────────────────────────────────────────────

	/**
	 * Called for every incoming chat/game message. Strips formatting and tries
	 * to match a drop pattern.
	 */
	fun processChat(message: Text?) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.hideonleafHelperEnabled || !config.shardTrackerEnabled) return
		if (!LocationTracker.isOnGalatea) return

		val raw = message?.string ?: return
		val clean = stripFormatting(raw).trim()

		for (pattern in DROP_PATTERNS) {
			val match = pattern.matchEntire(clean) ?: continue
			val itemName = match.groups["item"]?.value?.trim() ?: continue
			val amount = match.groups["amount"]?.value?.toLongOrNull() ?: 1L
			if (amount <= 0) continue
			if (!isTrackedItem(itemName)) continue

			addItem(itemName, amount)
			return
		}
	}

	/**
	 * Record a Hideonleaf fight completion (kill).
	 */
	fun recordKill() {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.hideonleafHelperEnabled || !config.shardTrackerEnabled) return
		if (!LocationTracker.isOnGalatea) return

		lastActivityAt = System.currentTimeMillis()
		totalData.kills++
		sessionData.kills++
		saveData()
	}

	// ── Manual item add (for testing / commands) ─────────────────────────

	fun addItem(name: String, amount: Long) {
		ensureSessionActive()
		lastActivityAt = System.currentTimeMillis()

		val canonicalName = canonicalize(name)
		addToData(totalData, canonicalName, amount)
		addToData(sessionData, canonicalName, amount)

		saveData()

		val client = MinecraftClient.getInstance()
		client.execute {
			client.player?.sendMessage(
				Text.literal("§a§l+ $amount §r§a$canonicalName §7(Tracker)"),
				true,
			)
		}
	}

	// ── Session control ──────────────────────────────────────────────────

	fun resetSession() {
		sessionData = TrackerData()
		sessionStartedAt = 0L
		sessionActive = false
		timerRunning = false
		lastActivityAt = 0L
	}

	fun resetTotal() {
		totalData = TrackerData()
		resetSession()
		saveData()
	}

	fun toggleView() {
		showingSession = !showingSession
	}

	// ── Timer pause / resume ─────────────────────────────────────────────

	/**
	 * Called every client tick. Pauses the stopwatch when the player is not
	 * on Galatea or has been AFK (no drops / kills) for longer than
	 * [AFK_THRESHOLD_MS]. Resumes automatically once both conditions clear.
	 */
	fun onTick() {
		if (!sessionActive) return

		val onGalatea = LocationTracker.isOnGalatea
		val now = System.currentTimeMillis()
		val isAfk = lastActivityAt > 0L && (now - lastActivityAt) > AFK_THRESHOLD_MS
		val shouldRun = onGalatea && !isAfk

		if (shouldRun && !timerRunning) resumeTimer()
		else if (!shouldRun && timerRunning) {
			// When AFK: cut the timer back to the last activity so the idle
			// minute is not counted towards the session duration.
			if (isAfk) pauseTimer(effectiveEndMs = lastActivityAt)
			else pauseTimer()
		}
	}

	/**
	 * Stops the stopwatch and adds the elapsed time to [sessionData].
	 *
	 * @param effectiveEndMs  The "real" end of active play in epoch-ms.
	 *   Defaults to now. Pass [lastActivityAt] when pausing due to AFK so
	 *   the idle period before the pause is not billed to the session.
	 */
	private fun pauseTimer(effectiveEndMs: Long = System.currentTimeMillis()) {
		if (!timerRunning) return
		val elapsed = (effectiveEndMs - sessionStartedAt).coerceAtLeast(0L)
		sessionData.totalDurationMs += elapsed
		sessionStartedAt = 0L
		timerRunning = false
	}

	private fun resumeTimer() {
		if (!sessionActive || timerRunning) return
		sessionStartedAt = System.currentTimeMillis()
		timerRunning = true
	}

	// ── Display data ─────────────────────────────────────────────────────

	fun displayData(): TrackerData = if (showingSession) sessionData else totalData

	fun sessionDurationMs(): Long {
		if (!sessionActive) return sessionData.totalDurationMs
		if (!timerRunning) return sessionData.totalDurationMs
		return sessionData.totalDurationMs + (System.currentTimeMillis() - sessionStartedAt)
	}

	/** True when the session is active but the stopwatch is paused. */
	val isTimerPaused: Boolean get() = sessionActive && !timerRunning

	/**
	 * When paused: true = paused due to AFK (still on Galatea),
	 * false = paused because the player left Galatea.
	 */
	val afkPauseActive: Boolean get() = isTimerPaused && LocationTracker.isOnGalatea

	fun totalProfit(data: TrackerData): Double {
		return data.items.values.sumOf { it.amount * it.pricePerUnit }
	}

	fun profitPerHour(data: TrackerData, durationMs: Long): Double {
		if (durationMs <= 0) return 0.0
		val hours = durationMs / 3_600_000.0
		return totalProfit(data) / hours
	}

	// ── Persistence ──────────────────────────────────────────────────────

	private fun loadData(): TrackerData {
		return try {
			if (Files.notExists(DATA_PATH)) {
				TrackerData()
			} else {
				Files.newBufferedReader(DATA_PATH).use { reader ->
					GSON.fromJson(reader, TrackerData::class.java) ?: TrackerData()
				}
			}
		} catch (exception: Exception) {
			LOGGER.warn("Failed to load shard tracker data from {}", DATA_PATH, exception)
			TrackerData()
		}
	}

	private fun saveData() {
		try {
			Files.createDirectories(DATA_PATH.parent)
			Files.newBufferedWriter(DATA_PATH).use { writer ->
				GSON.toJson(totalData, writer)
			}
		} catch (exception: IOException) {
			LOGGER.warn("Failed to save shard tracker data to {}", DATA_PATH, exception)
		}
	}

	// ── Internals ────────────────────────────────────────────────────────

	private fun ensureSessionActive() {
		if (!sessionActive) {
			sessionActive = true
			sessionStartedAt = System.currentTimeMillis()
			timerRunning = true
		}
	}

	private fun addToData(data: TrackerData, itemName: String, amount: Long) {
		val item = data.items.getOrPut(itemName) {
			// Prefer live price from Hypixel, fall back to hardcoded default
			val price = livePrices[itemName] ?: DEFAULT_PRICES[itemName] ?: 0.0
			TrackedItem(pricePerUnit = price)
		}
		item.amount += amount
		item.timesDropped++
	}

	private fun isTrackedItem(name: String): Boolean {
		if (ITEM_KEYWORDS.isEmpty()) return true
		val lower = name.lowercase(Locale.ROOT)
		return ITEM_KEYWORDS.any { lower.contains(it) }
	}

	private fun canonicalize(name: String): String {
		return name.trim().split("\\s+".toRegex()).joinToString(" ") { part ->
			part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
		}
	}

	private fun stripFormatting(input: String): String {
		var result = input
		if (result.contains('§')) {
			val builder = StringBuilder(result.length)
			var skip = false
			for (character in result) {
				if (skip) { skip = false; continue }
				if (character == '§') { skip = true; continue }
				builder.append(character)
			}
			result = builder.toString()
		}
		result = AMPERSAND_PATTERN.replace(result, "")
		return result.replace('\r', ' ').replace('\n', ' ').replace("\\s+".toRegex(), " ").trim()
	}

	private val AMPERSAND_PATTERN = Regex("(?i)&[0-9A-FK-OR]")

	// ── Formatting helpers ───────────────────────────────────────────────

	fun formatCoins(value: Double): String {
		return when {
			value >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1fB", value / 1_000_000_000)
			value >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", value / 1_000_000)
			value >= 1_000 -> String.format(Locale.ROOT, "%.1fK", value / 1_000)
			else -> String.format(Locale.ROOT, "%.0f", value)
		}
	}

	fun formatDuration(ms: Long): String {
		val totalSeconds = ms / 1000
		val hours = totalSeconds / 3600
		val minutes = (totalSeconds % 3600) / 60
		val seconds = totalSeconds % 60
		return if (hours > 0) {
			String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, seconds)
		} else if (minutes > 0) {
			String.format(Locale.ROOT, "%dm %02ds", minutes, seconds)
		} else {
			String.format(Locale.ROOT, "%ds", seconds)
		}
	}
}

// ── HUD Element ──────────────────────────────────────────────────────────

object HideonleafShardTrackerHudElement : XclipsenHudElement(
	id = "hideonleaf_shard_tracker",
	displayName = "Shard Tracker",
) {
	private const val LINE_HEIGHT = 11
	private const val PADDING = 4
	// Line index of the clickable Session/Total toggle (0 = title, 1 = toggle)
	private const val TOGGLE_LINE_INDEX = 1

	private const val HEADER_COLOR  = 0xFF55FFFF.toInt() // aqua
	private const val TOGGLE_COLOR  = 0xFF55FFFF.toInt() // aqua — indicates clickable
	private const val PROFIT_COLOR  = 0xFF55FF55.toInt() // green
	private const val LOSS_COLOR    = 0xFFFF5555.toInt() // red
	private const val MUTED_COLOR   = 0xFFAAAAAA.toInt() // gray
	private const val ITEM_COLOR    = 0xFFFFFFFF.toInt() // white
	private const val SEPARATOR_COLOR  = 0x50FFFFFF
	private const val BACKGROUND_COLOR = 0xC8101010.toInt()
	private const val BORDER_COLOR     = 0xFF36C5F0.toInt()
	private const val TOGGLE_HOVER_BG  = 0x30FFFFFF      // subtle highlight when clickable

	// ── Click detection ───────────────────────────────────────────────

	/** Absolute screen bounds of the toggle row, updated every render tick. */
	@Volatile private var toggleClickBounds: ClickArea? = null

	private data class ClickArea(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
		fun contains(mouseX: Int, mouseY: Int) =
			mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y2
	}

	/**
	 * Called by [XclipsenHudManager.handleScreenClick].
	 * Returns true if the click hit the toggle button and was consumed.
	 */
	fun handleClick(mouseX: Int, mouseY: Int): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		if (!shouldDraw(config)) return false
		val bounds = toggleClickBounds ?: return false
		if (!bounds.contains(mouseX, mouseY)) return false
		HideonleafShardTracker.toggleView()
		return true
	}

	/** Recomputes the absolute screen position of the toggle row after each render. */
	private fun refreshToggleBounds(totalWidth: Int) {
		val pl = XclipsenIrcBridgeClient.instance?.config()?.hudElements?.get(id) ?: return
		if (!pl.x.isFinite() || !pl.y.isFinite()) return
		val scale = pl.scale.coerceIn(0.5f, 4f)
		val localY = (PADDING + TOGGLE_LINE_INDEX * LINE_HEIGHT).toFloat()
		toggleClickBounds = ClickArea(
			x1 = pl.x,
			y1 = pl.y + localY * scale,
			x2 = pl.x + totalWidth * scale,
			y2 = pl.y + (localY + LINE_HEIGHT) * scale,
		)
	}

	// ── XclipsenHudElement overrides ──────────────────────────────────

	override fun isEnabled(config: BridgeConfig): Boolean =
		config.hideonleafHelperEnabled && config.shardTrackerEnabled

	override fun shouldDraw(config: BridgeConfig): Boolean =
		isEnabled(config) && LocationTracker.isOnGalatea

	override fun defaultX(context: DrawContext): Float = 4f
	override fun defaultY(context: DrawContext): Float = 80f

	override fun draw(context: DrawContext, example: Boolean): Pair<Float, Float> {
		val client   = MinecraftClient.getInstance()
		val renderer = client.textRenderer
		val data      = if (example) exampleData() else HideonleafShardTracker.displayData()
		val durationMs = if (example) 3_723_000L    else HideonleafShardTracker.sessionDurationMs()
		val isSession  = HideonleafShardTracker.showingSession

		// ── Build lines ───────────────────────────────────────────────
		val lines = mutableListOf<TrackerLine>()

		// Line 0: title
		lines += TrackerLine("Hideonleaf Profit Tracker", HEADER_COLOR, centered = true)

		// Line 1: clickable toggle — arrows hint that it's interactive
		val viewLabel = if (isSession) "Session" else "Total"
		lines += TrackerLine("< $viewLabel >", TOGGLE_COLOR, centered = true, isToggle = true)

		lines += TrackerLine.SEPARATOR

		// Items
		if (data.items.isEmpty()) {
			lines += TrackerLine("No drops yet...", MUTED_COLOR, centered = true)
		} else {
			val sorted = data.items.entries.sortedByDescending { it.value.amount * it.value.pricePerUnit }
			for ((name, item) in sorted) {
				val value    = item.amount * item.pricePerUnit
				val valueStr = if (value > 0) " §a(${HideonleafShardTracker.formatCoins(value)})" else ""
				lines += TrackerLine("  §e${formatNumber(item.amount)}x §f$name$valueStr", ITEM_COLOR)
			}
		}

		lines += TrackerLine.SEPARATOR

		// Stats
		val totalProfit  = HideonleafShardTracker.totalProfit(data)
		val profitPerHour = HideonleafShardTracker.profitPerHour(data, durationMs)
		val profitColor  = if (totalProfit >= 0) PROFIT_COLOR else LOSS_COLOR

		lines += TrackerLine("Profit: §a${HideonleafShardTracker.formatCoins(totalProfit)}", profitColor)
		lines += TrackerLine("Per Hour: §a${HideonleafShardTracker.formatCoins(profitPerHour)}/h", MUTED_COLOR)
		if (data.kills > 0)
			lines += TrackerLine("Kills: §e${formatNumber(data.kills)}", MUTED_COLOR)
		val timerSuffix = when {
			!example && HideonleafShardTracker.afkPauseActive  -> " §6[AFK]"
			!example && HideonleafShardTracker.isTimerPaused   -> " §c[Pausiert]"
			else -> ""
		}
		lines += TrackerLine("Time: §f${HideonleafShardTracker.formatDuration(durationMs)}$timerSuffix", MUTED_COLOR)

		// ── Compute dimensions ────────────────────────────────────────
		var maxTextWidth = 0
		for (line in lines) {
			if (line.isSeparator) continue
			val w = renderer.getWidth(stripSectionSigns(line.text))
			if (w > maxTextWidth) maxTextWidth = w
		}
		val totalWidth  = maxTextWidth + (PADDING * 2) + 4
		val totalHeight = (lines.size * LINE_HEIGHT) + (PADDING * 2)

		// Store click bounds so handleClick() knows where the toggle is
		if (!example) refreshToggleBounds(totalWidth)

		// ── Background & border ───────────────────────────────────────
		context.fill(-2, -2, totalWidth + 2, totalHeight + 2, BORDER_COLOR)
		context.fill(0, 0, totalWidth, totalHeight, BACKGROUND_COLOR)

		// ── Render lines ──────────────────────────────────────────────
		var y = PADDING
		for (line in lines) {
			if (line.isSeparator) {
				context.fill(PADDING, y + 4, totalWidth - PADDING, y + 5, SEPARATOR_COLOR)
				y += LINE_HEIGHT
				continue
			}

			// Subtle background highlight behind the toggle row
			if (line.isToggle) {
				context.fill(0, y - 1, totalWidth, y + LINE_HEIGHT, TOGGLE_HOVER_BG)
			}

			val segments = parseColoredText(line.text)
			if (line.centered) {
				val fullWidth = segments.sumOf { renderer.getWidth(it.text) }
				var x = (totalWidth - fullWidth) / 2
				for (seg in segments) {
					context.drawTextWithShadow(renderer, seg.text, x, y, seg.color)
					x += renderer.getWidth(seg.text)
				}
			} else {
				var x = PADDING
				for (seg in segments) {
					context.drawTextWithShadow(renderer, seg.text, x, y, seg.color)
					x += renderer.getWidth(seg.text)
				}
			}
			y += LINE_HEIGHT
		}

		return totalWidth.toFloat() to totalHeight.toFloat()
	}

	// ── Example data ──────────────────────────────────────────────────

	private fun exampleData() = HideonleafShardTracker.TrackerData(
		items = mutableMapOf(
			"Hideonleaf Shards" to HideonleafShardTracker.TrackedItem(42, 42, 68_000.0),
			"Hideonring Shards" to HideonleafShardTracker.TrackedItem(3, 3, 914_635.0),
			"Hideoncave Shards" to HideonleafShardTracker.TrackedItem(12, 12, 124_312.0),
		),
		kills = 156,
		totalDurationMs = 3_723_000,
	)

	// ── Internal helpers ──────────────────────────────────────────────

	private data class TrackerLine(
		val text: String,
		val color: Int,
		val centered: Boolean = false,
		val isToggle: Boolean = false,
	) {
		val isSeparator: Boolean get() = this === SEPARATOR
		companion object {
			val SEPARATOR = TrackerLine("---", 0)
		}
	}

	private data class TextSegment(val text: String, val color: Int)

	private fun parseColoredText(input: String): List<TextSegment> {
		val segments = mutableListOf<TextSegment>()
		var color = 0xFFFFFFFF.toInt()
		val buf   = StringBuilder()
		var i = 0
		while (i < input.length) {
			if (input[i] == '§' && i + 1 < input.length) {
				if (buf.isNotEmpty()) { segments += TextSegment(buf.toString(), color); buf.clear() }
				color = colorFromCode(input[i + 1])
				i += 2; continue
			}
			buf.append(input[i++])
		}
		if (buf.isNotEmpty()) segments += TextSegment(buf.toString(), color)
		return segments
	}

	private fun stripSectionSigns(input: String): String {
		val sb = StringBuilder(input.length)
		var skip = false
		for (ch in input) { if (skip) { skip = false; continue }; if (ch == '§') { skip = true; continue }; sb.append(ch) }
		return sb.toString()
	}

	private fun colorFromCode(code: Char) = when (code.lowercaseChar()) {
		'0' -> 0xFF000000.toInt(); '1' -> 0xFF0000AA.toInt(); '2' -> 0xFF00AA00.toInt()
		'3' -> 0xFF00AAAA.toInt(); '4' -> 0xFFAA0000.toInt(); '5' -> 0xFFAA00AA.toInt()
		'6' -> 0xFFFFAA00.toInt(); '7' -> 0xFFAAAAAA.toInt(); '8' -> 0xFF555555.toInt()
		'9' -> 0xFF5555FF.toInt(); 'a' -> 0xFF55FF55.toInt(); 'b' -> 0xFF55FFFF.toInt()
		'c' -> 0xFFFF5555.toInt(); 'd' -> 0xFFFF55FF.toInt(); 'e' -> 0xFFFFFF55.toInt()
		else -> 0xFFFFFFFF.toInt()
	}

	private fun formatNumber(value: Long) = String.format(Locale.ROOT, "%,d", value)
}
