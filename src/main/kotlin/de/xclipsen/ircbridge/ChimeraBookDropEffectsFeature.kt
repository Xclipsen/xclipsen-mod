package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.particle.ParticleTypes
import net.minecraft.text.Text

object ChimeraBookDropEffectsFeature {
	private const val CHIMERA_BOOK = "Enchanted Book (Chimera I)"
	private const val DEDUPE_MS = 750L
	private val rareDropPattern = Regex("^(?:RARE|VERY RARE|CRAZY RARE|INSANE) DROP!\\s+\\(?(?<item>Enchanted Book \\(Chimera I\\))\\)?(?:\\s+\\(\\+\\d+%?.*Magic Find\\))?$")
	private var pendingTestTicks = 0
	private var pendingTestConfig: BridgeConfig? = null
	private var lastTriggerAt = 0L

	fun onIncomingGameMessage(message: Text?, overlay: Boolean) {
		if (overlay || !isEnabled()) {
			return
		}

		tryTriggerFromMessage(message)
	}

	fun onIncomingChatMessage(message: Text?) {
		if (!isEnabled()) {
			return
		}

		tryTriggerFromMessage(message)
	}

	fun runTest(config: BridgeConfig? = null): Boolean {
		val value = config ?: XclipsenIrcBridgeClient.instance?.config() ?: return false
		if (!isEnabled(value)) {
			return false
		}

		MinecraftClient.getInstance().execute {
			MinecraftClient.getInstance().setScreen(null)
			pendingTestConfig = value.copy()
			pendingTestTicks = 3
		}
		return true
	}

	fun onTick() {
		if (pendingTestTicks <= 0) {
			return
		}

		pendingTestTicks--
		val config = pendingTestConfig ?: XclipsenIrcBridgeClient.instance?.config()
		if (pendingTestTicks == 0 && config != null && isEnabled(config)) {
			pendingTestConfig = null
			triggerEffect(config)
		}
	}

	fun statusLine(): String = if (isEnabled()) "Enabled" else "Disabled"

	private fun tryTriggerFromMessage(message: Text?) {
		val itemName = rareDropPattern.matchEntire(normalizeMessage(message?.string ?: return))?.groups?.get("item")?.value ?: return
		if (itemName == CHIMERA_BOOK) {
			val now = System.currentTimeMillis()
			if (now - lastTriggerAt < DEDUPE_MS) {
				return
			}
			lastTriggerAt = now
			triggerEffect(XclipsenIrcBridgeClient.instance?.config() ?: return)
		}
	}

	private fun normalizeMessage(raw: String): String {
		val trimmed = raw.trim()
		val testingPrefix = "Testing message: "
		val testingIndex = trimmed.indexOf(testingPrefix)
		return if (testingIndex >= 0) trimmed.substring(testingIndex + testingPrefix.length).trim() else trimmed
	}

	private fun isEnabled(): Boolean = XclipsenIrcBridgeClient.instance?.config()?.chimeraBookDropEffectsModuleEnabled == true

	private fun isEnabled(config: BridgeConfig): Boolean = config.chimeraBookDropEffectsModuleEnabled

	private fun triggerEffect(config: BridgeConfig) {
		val client = MinecraftClient.getInstance()
		val player = client.player ?: return
		val stack = chimeraBookStack()

		client.particleManager.addEmitter(player, ParticleTypes.SCRAPE, 30)
		client.gameRenderer.showFloatingItem(stack)
		client.soundManager.play(
			PositionedSoundInstance.master(
				SoundCatalog.soundEvent(config.chimeraBookDropEffectsSoundId),
				config.chimeraBookDropEffectsSoundPitch.coerceIn(0.1f, 2.0f),
				config.chimeraBookDropEffectsSoundVolume.coerceIn(0.0f, 2.0f),
			),
		)
	}

	private fun chimeraBookStack(): ItemStack {
		return ItemStack(Items.ENCHANTED_BOOK).also { stack ->
			stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(CHIMERA_BOOK))
		}
	}
}
