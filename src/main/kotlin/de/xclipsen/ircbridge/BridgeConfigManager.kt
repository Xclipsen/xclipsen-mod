package de.xclipsen.ircbridge

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.Logger
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class BridgeConfigManager(
	private val logger: Logger,
) {
	private val pathInternal: Path = FabricLoader.getInstance().configDir.resolve("xclipsen-irc-bridge.json")

	fun load(): BridgeConfig {
		return try {
			Files.createDirectories(pathInternal.parent)

			if (Files.notExists(pathInternal)) {
				val defaults = normalized(BridgeConfig())
				save(defaults)
				logger.info("Created default config at {}", pathInternal)
				defaults
			} else {
				Files.newBufferedReader(pathInternal).use { reader ->
					normalized(GSON.fromJson(reader, BridgeConfig::class.java))
				}
			}
		} catch (exception: IOException) {
			logger.error("Failed to load config {}", pathInternal, exception)
			normalized(BridgeConfig())
		}
	}

	@Throws(IOException::class)
	fun save(config: BridgeConfig) {
		Files.createDirectories(pathInternal.parent)
		Files.newBufferedWriter(pathInternal).use { writer ->
			GSON.toJson(normalized(config), writer)
		}
	}

	fun path(): Path = pathInternal

	fun normalize(config: BridgeConfig): BridgeConfig = normalized(config)

	private fun normalized(config: BridgeConfig?): BridgeConfig {
		val value = config ?: BridgeConfig()
		value.backendBaseUrl = normalizeBackendBaseUrl(value.backendBaseUrl)
		value.backendAuthToken = safeString(value.backendAuthToken, "change-me")
		value.linkedDiscordDisplayName = normalizedTemplate(value.linkedDiscordDisplayName, "")
		value.discordToMinecraftFormat = normalizedTemplate(value.discordToMinecraftFormat, "[Discord] <%user%> %message%")
		value.ircCommandFormat = normalizedTemplate(value.ircCommandFormat, "[IRC] <%player%> %message%")
		value.eventPingFormat = normalizedTemplate(value.eventPingFormat, "[Event] %event%: %message%")
		value.coopChatFormat = normalizedTemplate(value.coopChatFormat, "[Co-op] <%player%> %message%")
		value.backendPollIntervalMs = max(500L, min(60_000L, value.backendPollIntervalMs))
		return value
	}

	private fun safeString(value: String?, fallback: String): String = value ?: fallback

	private fun normalizedTemplate(value: String?, fallback: String): String {
		val candidate = safeString(value, fallback)
			.replace('\r', ' ')
			.replace('\n', ' ')
			.trim()
		if (candidate.isBlank()) {
			return fallback
		}

		return if (candidate.length > 256) candidate.substring(0, 256) else candidate
	}

	private fun normalizeBackendBaseUrl(value: String?): String {
		val fallback = "http://127.0.0.1:8765"
		val candidate = safeString(value, fallback).trim()
		if (candidate.isBlank()) {
			return fallback
		}

		return try {
			val uri = URI.create(candidate)
			val scheme = safeString(uri.scheme, "").lowercase(Locale.ROOT)
			if (scheme != "http" && scheme != "https") {
				return fallback
			}

			val host = safeString(uri.host, "")
			if (host.isBlank()) {
				return fallback
			}

			val normalized = URI(
				scheme,
				uri.userInfo,
				host,
				uri.port,
				null,
				null,
				null,
			).toString()

			if (normalized.endsWith("/")) normalized.dropLast(1) else normalized
		} catch (_: IllegalArgumentException) {
			fallback
		} catch (_: URISyntaxException) {
			fallback
		}
	}

	companion object {
		private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
	}
}
