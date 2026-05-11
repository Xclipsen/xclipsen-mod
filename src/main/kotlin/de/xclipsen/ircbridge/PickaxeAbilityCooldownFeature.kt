package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.gui.DrawContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

object PickaxeAbilityCooldownFeature {
	const val DEFAULT_ALERT_TEXT = "%ability% is ready!"

	@Volatile
	private var currentStatus: AbilityStatus? = null

	@Volatile
	private var currentAlertText: String = ""

	@Volatile
	private var alertVisibleUntil: Long = 0L

	private var trackedStatus: TrackedAbilityStatus? = null
	private var tickCounter = 0
	private var pendingReadyAlertAbility: String? = null

	fun onTick(client: MinecraftClient) {
		if (++tickCounter < UPDATE_INTERVAL_TICKS) {
			return
		}
		tickCounter = 0

		val config = XclipsenIrcBridgeClient.instance?.config()
		if (config?.pickaxeAbilityCooldownModuleEnabled != true) {
			trackedStatus = null
			currentStatus = null
			clearAlert()
			pendingReadyAlertAbility = null
			return
		}

		if (client.world == null || client.player == null || !LocationTracker.isOnHypixelSkyBlock) {
			trackedStatus = null
			currentStatus = null
			clearAlert()
			pendingReadyAlertAbility = null
			return
		}

		val nextStatus = updateTrackedStatus(readStatus(client), System.currentTimeMillis())
		handleReadyAlert(client, config, nextStatus)
		currentStatus = nextStatus
	}

	fun onWorldChange() {
		tickCounter = 0
		trackedStatus = null
		currentStatus = null
		clearAlert()
		pendingReadyAlertAbility = null
	}

	fun currentStatus(): AbilityStatus? {
		val tracked = trackedStatus
		return if (tracked != null) tracked.toDisplayStatus(System.currentTimeMillis()) else currentStatus
	}

	fun shouldRender(config: BridgeConfig): Boolean {
		val status = currentStatus() ?: return false
		return !status.ready || config.pickaxeAbilityCooldownShowReady
	}

	fun statusLine(): String {
		val status = currentStatus() ?: return "No mining ability detected"
		return "${status.name}: ${status.stateText}"
	}

	fun exampleStatus(): AbilityStatus = AbilityStatus("Pickobulus", "12s", ready = false)

	fun playAlertPreview(config: BridgeConfig) {
		playAlertSound(config)
		val previewText = renderAlertText(config.pickaxeAbilityCooldownAlertText, exampleStatus())
		if (previewText.isNotBlank()) {
			showAlert(previewText)
		}
	}

	private fun readStatus(client: MinecraftClient): ServerAbilityStatus? {
		val playerList = client.player?.networkHandler?.playerList ?: return null
		for (entry in playerList) {
			val raw = entry.displayName?.string ?: continue
			val line = stripFormatting(raw)
			if (line.isBlank()) {
				continue
			}

			for (abilityName in ABILITY_NAMES) {
				val prefix = "$abilityName: "
				if (!line.startsWith(prefix, ignoreCase = true)) {
					continue
				}

				val stateText = line.substring(prefix.length).trim()
				if (!STATE_PATTERN.matches(stateText)) {
					continue
				}

				val normalizedState = normalizeState(stateText)
				return ServerAbilityStatus(
					name = abilityName,
					stateText = normalizedState,
					ready = isReadyState(stateText),
					remainingMs = parseRemainingMs(normalizedState),
				)
			}
		}

		return null
	}

	private fun updateTrackedStatus(serverStatus: ServerAbilityStatus?, now: Long): AbilityStatus? {
		if (serverStatus == null) {
			trackedStatus = null
			return null
		}

		val currentTracked = trackedStatus
		trackedStatus = when {
			currentTracked == null || !currentTracked.name.equals(serverStatus.name, ignoreCase = true) ->
				createTrackedStatus(serverStatus, now)
			serverStatus.ready ->
				TrackedAbilityStatus(serverStatus.name, serverStatus.stateText, null)
			currentTracked.serverStateText != serverStatus.stateText ->
				createTrackedStatus(serverStatus, now)
			else -> currentTracked
		}

		return trackedStatus?.toDisplayStatus(now)
	}

	private fun createTrackedStatus(serverStatus: ServerAbilityStatus, now: Long): TrackedAbilityStatus {
		return if (serverStatus.ready) {
			TrackedAbilityStatus(serverStatus.name, serverStatus.stateText, null)
		} else {
			TrackedAbilityStatus(
				name = serverStatus.name,
				serverStateText = serverStatus.stateText,
				readyAtMs = serverStatus.remainingMs?.let { now + it },
			)
		}
	}

	private fun handleReadyAlert(client: MinecraftClient, config: BridgeConfig, nextStatus: AbilityStatus?) {
		when {
			nextStatus == null -> return
			!nextStatus.ready -> {
				pendingReadyAlertAbility = nextStatus.name
			}
			pendingReadyAlertAbility.equals(nextStatus.name, ignoreCase = true) -> {
				pendingReadyAlertAbility = null
				if (config.pickaxeAbilityCooldownAlertEnabled) {
					triggerReadyAlert(client, config, nextStatus)
				}
			}
		}
	}

	private fun triggerReadyAlert(client: MinecraftClient, config: BridgeConfig, status: AbilityStatus) {
		playAlertSound(config)
		val renderedText = renderAlertText(config.pickaxeAbilityCooldownAlertText, status)
		if (renderedText.isNotBlank()) {
			showAlert(renderedText)
		}
	}

	private fun showAlert(text: String) {
		currentAlertText = text
		alertVisibleUntil = System.currentTimeMillis() + ALERT_VISIBLE_MS
	}

	private fun clearAlert() {
		currentAlertText = ""
		alertVisibleUntil = 0L
	}

	private fun playAlertSound(config: BridgeConfig) {
		val sound = SoundCatalog.soundEvent(config.pickaxeAbilityCooldownAlertSoundId)
		MinecraftClient.getInstance().soundManager.play(
			PositionedSoundInstance.master(
				sound,
				config.pickaxeAbilityCooldownAlertSoundPitch.coerceIn(0.1f, 2.0f),
				config.pickaxeAbilityCooldownAlertSoundVolume.coerceIn(0.0f, 2.0f),
			),
		)
	}

	private fun renderAlertText(template: String, status: AbilityStatus): String {
		return translateAmpersandFormatting(
			TextFormatter.apply(
			template,
			"%ability%", status.name,
			"%status%", status.stateText,
			).trim(),
		)
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

	fun shouldDrawAlert(config: BridgeConfig): Boolean {
		return config.pickaxeAbilityCooldownModuleEnabled &&
			config.pickaxeAbilityCooldownAlertEnabled &&
			currentAlertText.isNotBlank() &&
			System.currentTimeMillis() <= alertVisibleUntil
	}

	fun currentAlertText(): String = currentAlertText

	private fun normalizeState(stateText: String): String {
		val normalized = stateText.trim()
		return when {
			normalized.equals("available", ignoreCase = true) -> "Available"
			normalized.equals("ready", ignoreCase = true) -> "Ready"
			else -> normalized
		}
	}

	private fun isReadyState(stateText: String): Boolean {
		return stateText.equals("available", ignoreCase = true) || stateText.equals("ready", ignoreCase = true)
	}

	private fun stripFormatting(input: String): String {
		var result = input
		if (result.contains('§')) {
			val builder = StringBuilder(result.length)
			var skip = false
			for (character in result) {
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
			result = builder.toString()
		}

		result = AMPERSAND_PATTERN.replace(result, "")
		return result.replace('\r', ' ').replace('\n', ' ').replace(WHITESPACE_PATTERN, " ").trim()
	}

	data class AbilityStatus(
		val name: String,
		val stateText: String,
		val ready: Boolean,
	)

	private data class ServerAbilityStatus(
		val name: String,
		val stateText: String,
		val ready: Boolean,
		val remainingMs: Long?,
	)

	private data class TrackedAbilityStatus(
		val name: String,
		val serverStateText: String,
		val readyAtMs: Long?,
	) {
		fun toDisplayStatus(now: Long): AbilityStatus {
			if (readyAtMs == null) {
				return AbilityStatus(name, serverStateText, ready = true)
			}

			val remainingMs = readyAtMs - now
			return if (remainingMs <= 0L) {
				AbilityStatus(name, "Ready", ready = true)
			} else {
				AbilityStatus(name, formatRemainingMs(remainingMs), ready = false)
			}
		}
	}

	private fun parseRemainingMs(stateText: String): Long? {
		if (isReadyState(stateText)) {
			return 0L
		}

		var totalMs = 0L
		for (part in stateText.split(' ')) {
			if (part.isBlank()) {
				continue
			}

			when {
				part.endsWith("h", ignoreCase = true) -> {
					val hours = part.dropLast(1).toLongOrNull() ?: return null
					totalMs += hours * 3_600_000L
				}
				part.endsWith("m", ignoreCase = true) -> {
					val minutes = part.dropLast(1).toLongOrNull() ?: return null
					totalMs += minutes * 60_000L
				}
				part.endsWith("s", ignoreCase = true) -> {
					val seconds = part.dropLast(1).toDoubleOrNull() ?: return null
					totalMs += (seconds * 1000.0).toLong()
				}
				else -> return null
			}
		}

		return totalMs.takeIf { it >= 0L }
	}

	private fun formatRemainingMs(remainingMs: Long): String {
		val safeRemainingMs = remainingMs.coerceAtLeast(0L)
		val hours = safeRemainingMs / 3_600_000L
		val minutes = (safeRemainingMs % 3_600_000L) / 60_000L
		val secondsRemainderMs = safeRemainingMs % 60_000L
		if (hours > 0L) {
			val seconds = ceil((secondsRemainderMs / 1000.0)).toLong().coerceAtMost(59L)
			return buildString {
				append(hours).append('h')
				if (minutes > 0L || seconds > 0L) append(' ').append(minutes).append('m')
				if (seconds > 0L) append(' ').append(seconds).append('s')
			}
		}
		if (minutes > 0L) {
			val seconds = ceil((secondsRemainderMs / 1000.0)).toLong().coerceAtMost(59L)
			return if (seconds > 0L) "${minutes}m ${seconds}s" else "${minutes}m"
		}

		val seconds = safeRemainingMs / 1000.0
		return if (seconds >= 10.0) {
			"${ceil(seconds).toInt()}s"
		} else {
			String.format(Locale.ROOT, "%.1fs", ceil(seconds * 10.0) / 10.0)
		}
	}

	private const val UPDATE_INTERVAL_TICKS = 5
	private val AMPERSAND_PATTERN = Regex("(?i)&[0-9A-FK-OR]")
	private val FORMATTING_CODE_PATTERN = Regex("(?i)[0-9A-FK-OR]")
	private val WHITESPACE_PATTERN = Regex("\\s+")
	private val STATE_PATTERN = Regex("(?i)^(available|ready|\\d+h(?: \\d+m)?(?: \\d+(?:\\.\\d+)?s)?|\\d+m(?: \\d+(?:\\.\\d+)?s)?|\\d+(?:\\.\\d+)?s)$")
	private val ABILITY_NAMES = listOf(
		"Pickobulus",
		"Mining Speed Boost",
		"Vein Seeker",
		"Maniac Miner",
		"Front Loaded",
		"Gemstone Infusion",
		"Tactical Destruction",
	)

	private const val ALERT_VISIBLE_MS = 2_800L
}

object PickaxeAbilityCooldownHudElement : XclipsenHudElement(
	id = "pickaxe_ability_cooldown",
	displayName = "Pickaxe Cooldown",
) {
	override fun isEnabled(config: BridgeConfig): Boolean = config.pickaxeAbilityCooldownModuleEnabled

	override fun shouldDraw(config: BridgeConfig): Boolean = isEnabled(config) && PickaxeAbilityCooldownFeature.shouldRender(config)

	override fun defaultX(context: DrawContext): Float = 20f

	override fun defaultY(context: DrawContext): Float = 80f

	override fun draw(context: DrawContext, example: Boolean): Pair<Float, Float> {
		val status = if (example) PickaxeAbilityCooldownFeature.exampleStatus() else PickaxeAbilityCooldownFeature.currentStatus()
			?: return 96f to 26f
		val client = MinecraftClient.getInstance()
		val textRenderer = client.textRenderer
		val title = status.name.uppercase(Locale.ROOT)
		val value = status.stateText
		val contentWidth = max(textRenderer.getWidth(title), textRenderer.getWidth(value))
		val boxWidth = max(MIN_WIDTH, contentWidth + (PADDING_X * 2))
		val boxHeight = PADDING_Y + textRenderer.fontHeight + LINE_GAP + textRenderer.fontHeight + PADDING_Y
		val accent = if (status.ready) READY_ACCENT else COOLDOWN_ACCENT
		val valueColor = if (status.ready) READY_TEXT else COOLDOWN_TEXT

		context.fill(0, 0, boxWidth, boxHeight, BACKGROUND)
		context.fill(0, 0, boxWidth, 1, accent)
		context.fill(0, boxHeight - 1, boxWidth, boxHeight, accent)
		context.fill(0, 0, 1, boxHeight, accent)
		context.fill(boxWidth - 1, 0, boxWidth, boxHeight, accent)
		context.fill(3, 3, boxWidth - 3, boxHeight - 3, INNER_BACKGROUND)
		context.drawTextWithShadow(textRenderer, title, PADDING_X, PADDING_Y, LABEL_TEXT)
		context.drawTextWithShadow(textRenderer, value, PADDING_X, PADDING_Y + textRenderer.fontHeight + LINE_GAP, valueColor)

		return boxWidth.toFloat() to boxHeight.toFloat()
	}

	private const val PADDING_X = 6
	private const val PADDING_Y = 5
	private const val LINE_GAP = 2
	private const val MIN_WIDTH = 90
	private const val BACKGROUND = 0xB4121212.toInt()
	private const val INNER_BACKGROUND = 0x40181818
	private const val LABEL_TEXT = 0xFFE8E8E8.toInt()
	private const val READY_TEXT = 0xFF8CF5A9.toInt()
	private const val COOLDOWN_TEXT = 0xFFFFC76B.toInt()
	private const val READY_ACCENT = 0xFF2FAD5A.toInt()
	private const val COOLDOWN_ACCENT = 0xFFE39A2D.toInt()
}

object PickaxeAbilityReadyAlertHudElement : XclipsenHudElement(
	id = "pickaxe_ability_ready_alert",
	displayName = "Pickaxe Ready Alert",
) {
	override fun isEnabled(config: BridgeConfig): Boolean =
		config.pickaxeAbilityCooldownModuleEnabled && config.pickaxeAbilityCooldownAlertEnabled

	override fun shouldDraw(config: BridgeConfig): Boolean =
		isEnabled(config) && PickaxeAbilityCooldownFeature.shouldDrawAlert(config)

	override fun defaultX(context: DrawContext): Float {
		return ((context.scaledWindowWidth - DEFAULT_WIDTH) / 2f).coerceAtLeast(4f)
	}

	override fun defaultY(context: DrawContext): Float {
		return (context.scaledWindowHeight * 0.24f).coerceAtLeast(24f)
	}

	override fun draw(context: DrawContext, example: Boolean): Pair<Float, Float> {
		val client = MinecraftClient.getInstance()
		val textRenderer = client.textRenderer
		val text = if (example) "Pickobulus is ready!" else PickaxeAbilityCooldownFeature.currentAlertText()
		val width = max(DEFAULT_WIDTH, textRenderer.getWidth(text) + (PADDING_X * 2))
		val height = PADDING_Y + textRenderer.fontHeight + PADDING_Y

		drawAlertPanel(context, textRenderer, text, width, height)
		return width.toFloat() to height.toFloat()
	}

	private fun drawAlertPanel(
		context: DrawContext,
		textRenderer: TextRenderer,
		text: String,
		width: Int,
		height: Int,
	) {
		context.fill(0, 0, width, height, BACKGROUND)
		context.fill(0, 0, width, 1, ACCENT)
		context.fill(0, height - 1, width, height, ACCENT)
		context.fill(0, 0, 1, height, ACCENT)
		context.fill(width - 1, 0, width, height, ACCENT)
		context.fill(3, 3, width - 3, height - 3, INNER_BACKGROUND)
		context.drawCenteredTextWithShadow(textRenderer, text, width / 2, PADDING_Y, TEXT_COLOR)
	}

	private const val DEFAULT_WIDTH = 180
	private const val PADDING_X = 8
	private const val PADDING_Y = 6
	private const val BACKGROUND = 0xC0181818.toInt()
	private const val INNER_BACKGROUND = 0x402FAD5A
	private const val ACCENT = 0xFF2FAD5A.toInt()
	private const val TEXT_COLOR = 0xFFFFFFFF.toInt()
}
