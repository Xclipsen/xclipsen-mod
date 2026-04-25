package de.xclipsen.ircbridge

class BackendLinkCompleteRequest {
	@JvmField var playerName: String = ""
	@JvmField var code: String = ""
}

class BackendLinkStatusResponse {
	@JvmField var linked: Boolean = false
	@JvmField var discordUserId: String = ""
	@JvmField var discordDisplayName: String = ""
	@JvmField var playerName: String = ""
	@JvmField var error: String = ""
	@JvmField var minecraftUsernames: MutableList<String> = mutableListOf()
}

class BackendMessage {
	@JvmField var id: Long = 0L
	@JvmField var source: String = "discord"
	@JvmField var user: String = ""
	@JvmField var content: String = ""
	@JvmField var title: String = ""
	@JvmField var eventKey: String = ""
}

class BackendMessagesResponse {
	@JvmField var messages: MutableList<BackendMessage> = mutableListOf()
}

class BackendOutgoingMessage {
	@JvmField var type: String = "chat"
	@JvmField var playerName: String = ""
	@JvmField var message: String = ""
	@JvmField var forwardedPlayerName: String = ""
}

class BackendStatusSnapshot(
	@JvmField val state: String,
	@JvmField val lastHttpStatus: Int,
	@JvmField val lastSuccessAt: Long,
	@JvmField val lastPollAt: Long,
	@JvmField val lastMessageAt: Long,
	@JvmField val lastError: String,
)

class BridgeConfig {
	@JvmField var backendBaseUrl: String = "http://127.0.0.1:8765"
	@JvmField var backendAuthToken: String = "change-me"
	@JvmField var backendPollIntervalMs: Long = 2000L
	@JvmField var checkForUpdatesEnabled: Boolean = true
	@JvmField var linkedDiscordDisplayName: String = ""
	@JvmField var discordToMinecraftFormat: String = "[Discord] <%user%> %message%"
	@JvmField var ircCommandFormat: String = "[IRC] <%player%> %message%"
	@JvmField var eventPingFormat: String = "[Event] %event%: %message%"
	@JvmField var coopChatFormat: String = "[Co-op] <%player%> %message%"
	@JvmField var ircBridgeEnabled: Boolean = false
	@JvmField var coopChatRelayEnabled: Boolean = false
	@JvmField var hideonleafHelperEnabled: Boolean = false
	@JvmField var shulkerGlowEnabled: Boolean = true
	@JvmField var shulkerGlowColorHex: String = "#36C5F0"
	@JvmField var shulkerProjectileGlowColorHex: String = "#FF4D4D"
	@JvmField var shulkerTracerLineEnabled: Boolean = true
	@JvmField var shulkerTracerLineMode: Int = 1
	@JvmField var shulkerTracerLineColorHex: String = "#36C5F0"
	@JvmField var shulkerTracerLineWidth: Float = 2.0f
	@JvmField var hideonleafLostFightAlertEnabled: Boolean = true
	@JvmField var hideonleafShareDataEnabled: Boolean = true
	@JvmField var hideonleafLostFightAlertSoundId: String = "minecraft:block.note_block.pling"
	@JvmField var hideonleafLostFightAlertSoundVolume: Float = 1.0f
	@JvmField var hideonleafLostFightAlertSoundPitch: Float = 1.5f
	@JvmField var shardTrackerEnabled: Boolean = true
	@JvmField var timeChangerEnabled: Boolean = false
	@JvmField var timeChangerMode: Int = 0
	@JvmField var dungeonDoorModuleEnabled: Boolean = false
	@JvmField var dungeonDoorEnabled: Boolean = false
	@JvmField var dungeonDoorDebugEnabled: Boolean = false
	@JvmField var dungeonDoorMode: Int = 0
	@JvmField var dungeonRedVignetteModuleEnabled: Boolean = false
	@JvmField var dungeonRedVignetteEnabled: Boolean = false
	@JvmField var hudElements: MutableMap<String, HudElementPlacement> = mutableMapOf()

	fun copy(): BridgeConfig = BridgeConfig().also {
		it.backendBaseUrl = backendBaseUrl
		it.backendAuthToken = backendAuthToken
		it.backendPollIntervalMs = backendPollIntervalMs
		it.checkForUpdatesEnabled = checkForUpdatesEnabled
		it.linkedDiscordDisplayName = linkedDiscordDisplayName
		it.discordToMinecraftFormat = discordToMinecraftFormat
		it.ircCommandFormat = ircCommandFormat
		it.eventPingFormat = eventPingFormat
		it.coopChatFormat = coopChatFormat
		it.ircBridgeEnabled = ircBridgeEnabled
		it.coopChatRelayEnabled = coopChatRelayEnabled
		it.hideonleafHelperEnabled = hideonleafHelperEnabled
		it.shulkerGlowEnabled = shulkerGlowEnabled
		it.shulkerGlowColorHex = shulkerGlowColorHex
		it.shulkerProjectileGlowColorHex = shulkerProjectileGlowColorHex
		it.shulkerTracerLineEnabled = shulkerTracerLineEnabled
		it.shulkerTracerLineMode = shulkerTracerLineMode
		it.shulkerTracerLineColorHex = shulkerTracerLineColorHex
		it.shulkerTracerLineWidth = shulkerTracerLineWidth
		it.hideonleafLostFightAlertEnabled = hideonleafLostFightAlertEnabled
		it.hideonleafShareDataEnabled = hideonleafShareDataEnabled
		it.hideonleafLostFightAlertSoundId = hideonleafLostFightAlertSoundId
		it.hideonleafLostFightAlertSoundVolume = hideonleafLostFightAlertSoundVolume
		it.hideonleafLostFightAlertSoundPitch = hideonleafLostFightAlertSoundPitch
		it.shardTrackerEnabled = shardTrackerEnabled
		it.timeChangerEnabled = timeChangerEnabled
		it.timeChangerMode = timeChangerMode
		it.dungeonDoorModuleEnabled = dungeonDoorModuleEnabled
		it.dungeonDoorEnabled = dungeonDoorEnabled
		it.dungeonDoorDebugEnabled = dungeonDoorDebugEnabled
		it.dungeonDoorMode = dungeonDoorMode
		it.dungeonRedVignetteModuleEnabled = dungeonRedVignetteModuleEnabled
		it.dungeonRedVignetteEnabled = dungeonRedVignetteEnabled
		it.hudElements = hudElements.mapValues { entry -> entry.value.copy() }.toMutableMap()
	}
}

class HudElementPlacement() {
	@JvmField var x: Float = -1f
	@JvmField var y: Float = -1f
	@JvmField var scale: Float = 1f

	constructor(x: Float, y: Float, scale: Float) : this() {
		this.x = x
		this.y = y
		this.scale = scale
	}

	fun copy(): HudElementPlacement = HudElementPlacement(x, y, scale)
}

class HealthResponse {
	@JvmField var status: String = ""
}

class BackendHideonleafTrackedItem {
	@JvmField var amount: Long = 0L
	@JvmField var timesDropped: Long = 0L
	@JvmField var pricePerUnit: Double = 0.0
}

class BackendHideonleafStatsUpload {
	@JvmField var playerName: String = ""
	@JvmField var kills: Long = 0L
	@JvmField var totalShards: Long = 0L
	@JvmField var totalProfit: Double = 0.0
	@JvmField var profitPerHour: Double = 0.0
	@JvmField var totalDurationMs: Long = 0L
	@JvmField var updatedAt: Long = 0L
	@JvmField var items: MutableMap<String, BackendHideonleafTrackedItem> = mutableMapOf()
}

/** Response from GET /api/skyblock/prices */
class BackendPricePayload {
	@JvmField var prices: Map<String, ItemPrice> = emptyMap()
}

class ItemPrice {
	/** Instant-buy price per unit from the Bazaar buy-summary. */
	@JvmField var buyPrice: Double = 0.0
	/** Instant-sell price per unit from the Bazaar sell-summary. */
	@JvmField var sellPrice: Double = 0.0
	/** Unix-ms timestamp when the Bot last fetched this from Hypixel. */
	@JvmField var lastUpdated: Long = 0L
}

object TextFormatter {
	@JvmStatic
	fun apply(template: String, vararg replacements: String): String {
		var result = template
		var index = 0
		while (index + 1 < replacements.size) {
			result = result.replace(replacements[index], replacements[index + 1])
			index += 2
		}

		return result
	}
}
