package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.text.HoverEvent
import net.minecraft.text.Text
import kotlin.math.max

object SlayerFeature {
	const val DEFAULT_ANNOUNCER_TEXT = "Slayer boss spawned!"

	private const val DEDUPE_MS = 2500L
	private const val ALERT_VISIBLE_MS = 2800L
	private var lastAnnounceAt = 0L
	private var currentAlertText = ""
	private var alertVisibleUntil = 0L

	fun onIncomingMessage(message: Text?) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.slayerModuleEnabled || !config.slayerSpawnAnnouncerEnabled) {
			return
		}

		if (!LocationTracker.isOnHypixelSkyBlock) {
			return
		}

		if (!isSlayerSpawnPetRule(message ?: return)) {
			return
		}

		val now = System.currentTimeMillis()
		if (now - lastAnnounceAt < DEDUPE_MS) {
			return
		}
		lastAnnounceAt = now

		MinecraftClient.getInstance().execute {
			triggerAnnouncer(config)
		}
	}

	fun statusLine(config: BridgeConfig = XclipsenIrcBridgeClient.instance?.config() ?: BridgeConfig()): String {
		return if (config.slayerModuleEnabled && config.slayerSpawnAnnouncerEnabled) "Spawn Announcer enabled" else "Spawn Announcer disabled"
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

	private fun triggerAnnouncer(config: BridgeConfig) {
		val text = renderAnnouncerText(config)
		if (text.isNotBlank()) {
			currentAlertText = text
			alertVisibleUntil = System.currentTimeMillis() + ALERT_VISIBLE_MS
		}

		MinecraftClient.getInstance().soundManager.play(
			PositionedSoundInstance.master(
				SoundCatalog.soundEvent(config.slayerSpawnAnnouncerSoundId),
				config.slayerSpawnAnnouncerSoundPitch.coerceIn(0.1f, 2.0f),
				config.slayerSpawnAnnouncerSoundVolume.coerceIn(0.0f, 2.0f),
			),
		)
	}

	private fun renderAnnouncerText(config: BridgeConfig): String {
		return translateAmpersandFormatting(config.slayerSpawnAnnouncerText.ifBlank { DEFAULT_ANNOUNCER_TEXT })
	}

	private fun isSlayerSpawnPetRule(message: Text): Boolean {
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

	private fun collectHoverTexts(text: Text): List<String> {
		val result = mutableListOf<String>()
		collectHoverTexts(text, result)
		return result
	}

	private fun collectHoverTexts(text: Text, result: MutableList<String>) {
		val hoverEvent = text.style.hoverEvent
		if (hoverEvent is HoverEvent.ShowText) {
			result += hoverEvent.value().string
		}

		text.siblings.forEach { sibling -> collectHoverTexts(sibling, result) }
	}

	private fun normalize(raw: String): String {
		return stripMinecraftFormatting(raw)
			.replace('\r', ' ')
			.replace('\n', ' ')
			.replace(Regex("\\s+"), " ")
			.trim()
	}

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
}

object SlayerSpawnAnnouncerHudElement : XclipsenHudElement(
	id = "slayer_spawn_announcer",
	displayName = "Slayer Spawn Announcer",
) {
	override fun isEnabled(config: BridgeConfig): Boolean =
		config.slayerModuleEnabled && config.slayerSpawnAnnouncerEnabled

	override fun shouldDraw(config: BridgeConfig): Boolean =
		isEnabled(config) && SlayerFeature.shouldDrawAlert(config)

	override fun defaultX(context: DrawContext): Float {
		return ((context.scaledWindowWidth - DEFAULT_WIDTH) / 2f).coerceAtLeast(4f)
	}

	override fun defaultY(context: DrawContext): Float {
		return (context.scaledWindowHeight * 0.28f).coerceAtLeast(28f)
	}

	override fun draw(context: DrawContext, example: Boolean): Pair<Float, Float> {
		val client = MinecraftClient.getInstance()
		val textRenderer = client.textRenderer
		val text = if (example) SlayerFeature.DEFAULT_ANNOUNCER_TEXT else SlayerFeature.currentAlertText()
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
	private const val INNER_BACKGROUND = 0x4055E3FF
	private const val ACCENT = 0xFF55E3FF.toInt()
	private const val TEXT_COLOR = 0xFFFFFFFF.toInt()
}
