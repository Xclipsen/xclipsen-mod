package de.xclipsen.ircbridge

import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component

object ChimeraBookDropEffectsFeature {
	private const val CHIMERA_BOOK = "Enchanted Book (Chimera I)"
	private const val DEDUPE_MS = 750L
	private val rareDropPattern = Regex("^(?:RARE|VERY RARE|CRAZY RARE|INSANE) DROP!\\s+\\(?(?<item>Enchanted Book \\(Chimera I\\))\\)?(?:\\s+\\(\\+\\d+%?.*Magic Find\\))?$")
	private var pendingTestTicks = 0
	private var pendingTestConfig: BridgeConfig? = null
	private var lastTriggerAt = 0L

	fun onIncomingGameMessage(message: Component?, overlay: Boolean) {
		if (overlay || !isEnabled()) {
			return
		}

		tryTriggerFromMessage(message)
	}

	fun onIncomingChatMessage(message: Component?) {
		if (!isEnabled()) {
			return
		}

		tryTriggerFromMessage(message)
	}

	fun runTest(config: BridgeConfig? = null): Boolean {
		val value = config ?: XclipsenIrcBridgeClient.instance?.config() ?: return false
		if (!value.devModeEnabled || !isEnabled(value)) {
			return false
		}

		Minecraft.getInstance().execute {
			Minecraft.getInstance().setScreen(null)
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

	private fun tryTriggerFromMessage(message: Component?) {
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
		val client = Minecraft.getInstance()
		val player = client.player ?: return
		val stack = chimeraBookStack()

		client.particleEngine.createTrackingEmitter(player, ParticleTypes.SCRAPE, 30)
		client.gameRenderer.displayItemActivation(stack)
		client.soundManager.play(
			SoundCatalog.masterSound(
				config.chimeraBookDropEffectsSoundId,
				config.chimeraBookDropEffectsSoundPitch.coerceIn(0.1f, 2.0f),
				config.chimeraBookDropEffectsSoundVolume.coerceIn(0.0f, 2.0f),
			),
		)
	}

	private fun chimeraBookStack(): ItemStack {
		return ItemStack(Items.ENCHANTED_BOOK).also { stack ->
			stack.set(DataComponents.CUSTOM_NAME, Component.literal(CHIMERA_BOOK))
		}
	}
}
