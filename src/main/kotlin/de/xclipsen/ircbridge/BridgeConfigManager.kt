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
	private val baseDir: Path = FabricLoader.getInstance().configDir.resolve("Xclipsen")
	private val pathInternal: Path = baseDir.resolve("config.json")
	private val legacyPaths: List<Path> = listOf(
		FabricLoader.getInstance().configDir.resolve("xclipsen-mod.json"),
		FabricLoader.getInstance().configDir.resolve("xclipsen-irc-bridge.json"),
	)

	fun load(): BridgeConfig {
		return try {
			Files.createDirectories(pathInternal.parent)
			migrateLegacyConfig()

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

	private fun migrateLegacyConfig() {
		if (Files.exists(pathInternal)) {
			return
		}

		val legacyPath = legacyPaths.firstOrNull(Files::exists) ?: return
		Files.move(legacyPath, pathInternal)
		logger.info("Migrated legacy config {} to {}", legacyPath, pathInternal)
	}

	private fun normalized(config: BridgeConfig?): BridgeConfig {
		val value = config ?: BridgeConfig()
		value.backendBaseUrl = normalizeBackendBaseUrl(value.backendBaseUrl)
		value.backendAuthToken = safeString(value.backendAuthToken, "change-me")
		value.linkedDiscordDisplayName = normalizedTemplate(value.linkedDiscordDisplayName, "")
		value.ircCommandFormat = normalizedTemplate(value.ircCommandFormat, "[IRC] <%player%> %message%")
		value.backendPollIntervalMs = max(500L, min(60_000L, value.backendPollIntervalMs))
		value.shulkerGlowColorHex = normalizedHexColor(value.shulkerGlowColorHex, "#36C5F0")
		value.shulkerProjectileGlowColorHex = normalizedHexColor(value.shulkerProjectileGlowColorHex, "#FF4D4D")
		value.shulkerTracerLineColorHex = normalizedHexColor(value.shulkerTracerLineColorHex, "#36C5F0")
		value.shulkerTracerLineMode = if (value.shulkerTracerLineEnabled) value.shulkerTracerLineMode.coerceIn(0, 3) else 0
		value.shulkerTracerLineEnabled = value.shulkerTracerLineMode > 0
		value.shulkerTracerLineWidth = value.shulkerTracerLineWidth.coerceIn(1.0f, 8.0f)
		value.hideonleafLostFightAlertSoundId = SoundCatalog.normalizeSoundId(value.hideonleafLostFightAlertSoundId)
		value.hideonleafLostFightAlertSoundVolume = value.hideonleafLostFightAlertSoundVolume.coerceIn(0.0f, 2.0f)
		value.hideonleafLostFightAlertSoundPitch = value.hideonleafLostFightAlertSoundPitch.coerceIn(0.1f, 2.0f)
		value.timeChangerMode = value.timeChangerMode.coerceIn(0, ClientTimeChanger.modeCount - 1)
		value.dungeonDoorMode = value.dungeonDoorMode.coerceIn(0, MortDoorBarrierFeature.modeCount - 1)
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

	private fun normalizedHexColor(value: String?, fallback: String): String {
		val candidate = safeString(value, fallback).trim().removePrefix("#")
		if (!HEX_COLOR_PATTERN.matches(candidate)) {
			return fallback
		}
		return "#${candidate.uppercase(Locale.ROOT)}"
	}

	companion object {
		private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
		private val HEX_COLOR_PATTERN = Regex("[0-9a-fA-F]{6}")
	}
}
