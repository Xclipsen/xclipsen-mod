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
	@JvmField var linkedDiscordDisplayName: String = ""
	@JvmField var discordToMinecraftFormat: String = "[Discord] <%user%> %message%"
	@JvmField var ircCommandFormat: String = "[IRC] <%player%> %message%"
	@JvmField var eventPingFormat: String = "[Event] %event%: %message%"
	@JvmField var coopChatFormat: String = "[Co-op] <%player%> %message%"
	@JvmField var shulkerGlowEnabled: Boolean = true

	fun copy(): BridgeConfig = BridgeConfig().also {
		it.backendBaseUrl = backendBaseUrl
		it.backendAuthToken = backendAuthToken
		it.backendPollIntervalMs = backendPollIntervalMs
		it.linkedDiscordDisplayName = linkedDiscordDisplayName
		it.discordToMinecraftFormat = discordToMinecraftFormat
		it.ircCommandFormat = ircCommandFormat
		it.eventPingFormat = eventPingFormat
		it.coopChatFormat = coopChatFormat
		it.shulkerGlowEnabled = shulkerGlowEnabled
	}
}

class HealthResponse {
	@JvmField var status: String = ""
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
