package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import java.util.Locale
import kotlin.math.max

object PickaxeAbilityCooldownFeature {
	@Volatile
	private var currentStatus: AbilityStatus? = null

	private var tickCounter = 0

	fun onTick(client: MinecraftClient) {
		if (++tickCounter < UPDATE_INTERVAL_TICKS) {
			return
		}
		tickCounter = 0

		val config = XclipsenIrcBridgeClient.instance?.config()
		if (config?.pickaxeAbilityCooldownModuleEnabled != true) {
			currentStatus = null
			return
		}

		if (client.world == null || client.player == null || !LocationTracker.isOnHypixelSkyBlock) {
			currentStatus = null
			return
		}

		currentStatus = readStatus(client)
	}

	fun onWorldChange() {
		tickCounter = 0
		currentStatus = null
	}

	fun currentStatus(): AbilityStatus? = currentStatus

	fun shouldRender(config: BridgeConfig): Boolean {
		val status = currentStatus ?: return false
		return !status.ready || config.pickaxeAbilityCooldownShowReady
	}

	fun statusLine(): String {
		val status = currentStatus ?: return "No mining ability detected"
		return "${status.name}: ${status.stateText}"
	}

	fun exampleStatus(): AbilityStatus = AbilityStatus("Pickobulus", "12s", ready = false)

	private fun readStatus(client: MinecraftClient): AbilityStatus? {
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

				return AbilityStatus(
					name = abilityName,
					stateText = normalizeState(stateText),
					ready = isReadyState(stateText),
				)
			}
		}

		return null
	}

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

	private const val UPDATE_INTERVAL_TICKS = 5
	private val AMPERSAND_PATTERN = Regex("(?i)&[0-9A-FK-OR]")
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
