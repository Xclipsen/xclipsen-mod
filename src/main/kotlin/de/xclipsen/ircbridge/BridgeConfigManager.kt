package de.xclipsen.ircbridge

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.Logger
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class BridgeConfigManager(
	private val logger: Logger,
) {
	private val baseDir: Path = FabricLoader.getInstance().configDir.resolve("Xclipsen")
	private val sharedPath: Path = baseDir.resolve("config.json")
	private val playersDir: Path = baseDir.resolve("players")
	private val legacyPaths: List<Path> = listOf(
		FabricLoader.getInstance().configDir.resolve("xclipsen-mod.json"),
		FabricLoader.getInstance().configDir.resolve("xclipsen-irc-bridge.json"),
	)
	private var activeProfileId: UUID? = null

	@Synchronized
	fun load(profileId: UUID): BridgeConfig {
		return try {
			Files.createDirectories(baseDir)
			Files.createDirectories(playersDir)
			migrateLegacyConfig()
			activeProfileId = profileId

			if (Files.notExists(sharedPath)) {
				val defaults = normalized(BridgeConfig())
				writePlayerConfig(profileId, defaults)
				writeSharedConfig(defaults)
				logger.info("Created shared config at {} and player config at {}", sharedPath, playerPath(profileId))
				defaults
			} else {
				val sharedJson = readJsonObject(sharedPath)
				if (!isSplitConfig(sharedJson)) {
					migrateFlatConfig(profileId, sharedJson)
				}

				val playerPath = playerPath(profileId)
				if (Files.notExists(playerPath)) {
					writePlayerConfig(profileId, BridgeConfig())
					logger.info("Created default player config at {}", playerPath)
				}

				normalized(mergeConfig(readJsonObject(sharedPath), readJsonObject(playerPath)))
			}
		} catch (exception: Exception) {
			logger.error("Failed to load config for profile {}", profileId, exception)
			normalized(BridgeConfig())
		}
	}

	@Throws(IOException::class)
	@Synchronized
	fun save(config: BridgeConfig) {
		val profileId = activeProfileId ?: throw IOException("Cannot save player config without an active profile UUID")
		val value = normalized(config)
		writePlayerConfig(profileId, value)
		writeSharedConfig(value)
	}

	fun path(): Path = activeProfileId?.let(::playerPath) ?: sharedPath

	fun sharedPath(): Path = sharedPath

	fun credentialPath(): Path = baseDir.resolve("credentials.json")

	fun activeProfileId(): UUID? = activeProfileId

	fun normalize(config: BridgeConfig): BridgeConfig = normalized(config)

	private fun migrateLegacyConfig() {
		if (Files.exists(sharedPath)) {
			return
		}

		val legacyPath = legacyPaths.firstOrNull(Files::exists) ?: return
		Files.move(legacyPath, sharedPath)
		logger.info("Migrated legacy config {} to {}", legacyPath, sharedPath)
	}

	private fun migrateFlatConfig(profileId: UUID, source: JsonObject) {
		val backupPath = baseDir.resolve("config.json.pre-player-config.bak")
		if (Files.notExists(backupPath)) {
			Files.copy(sharedPath, backupPath, StandardCopyOption.COPY_ATTRIBUTES)
		}

		val legacyConfig = normalized(GSON.fromJson(source, BridgeConfig::class.java))
		// Commit the shared schema last so an interrupted migration is retried safely.
		writePlayerConfig(profileId, legacyConfig)
		writeSharedConfig(legacyConfig)
		logger.info("Migrated flat config to player profile {} and preserved backup at {}", profileId, backupPath)
	}

	private fun mergeConfig(shared: JsonObject, player: JsonObject): BridgeConfig {
		val merged = GSON.toJsonTree(BridgeConfig()).asJsonObject
		for (field in SHARED_CONFIG_FIELDS) {
			shared.get(field)?.let { merged.add(field, it.deepCopy()) }
		}
		for ((field, value) in player.entrySet()) {
			if (field != SCHEMA_VERSION_FIELD && field !in SHARED_CONFIG_FIELDS) {
				merged.add(field, value.deepCopy())
			}
		}
		return GSON.fromJson(merged, BridgeConfig::class.java)
	}

	private fun writeSharedConfig(config: BridgeConfig) {
		val source = GSON.toJsonTree(config).asJsonObject
		val output = JsonObject().apply {
			addProperty(SCHEMA_VERSION_FIELD, CONFIG_SCHEMA_VERSION)
			for (field in SHARED_CONFIG_FIELDS) {
				source.get(field)?.let { add(field, it.deepCopy()) }
			}
		}
		writeJsonAtomically(sharedPath, output)
	}

	private fun writePlayerConfig(profileId: UUID, config: BridgeConfig) {
		val source = GSON.toJsonTree(config).asJsonObject
		val output = JsonObject().apply {
			addProperty(SCHEMA_VERSION_FIELD, CONFIG_SCHEMA_VERSION)
			for ((field, value) in source.entrySet()) {
				if (field !in SHARED_CONFIG_FIELDS) {
					add(field, value.deepCopy())
				}
			}
		}
		writeJsonAtomically(playerPath(profileId), output)
	}

	private fun readJsonObject(path: Path): JsonObject =
		Files.newBufferedReader(path).use { reader ->
			GSON.fromJson(reader, JsonObject::class.java) ?: JsonObject()
		}

	private fun writeJsonAtomically(path: Path, value: JsonObject) {
		Files.createDirectories(path.parent)
		val temporaryPath = path.resolveSibling("${path.fileName}.tmp")
		try {
			Files.newBufferedWriter(temporaryPath).use { writer -> GSON.toJson(value, writer) }
			try {
				Files.move(
					temporaryPath,
					path,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING,
				)
			} catch (_: AtomicMoveNotSupportedException) {
				Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING)
			}
		} finally {
			Files.deleteIfExists(temporaryPath)
		}
	}

	private fun playerPath(profileId: UUID): Path = playersDir.resolve("${profileId.toString().lowercase(Locale.ROOT)}.json")

	private fun isSplitConfig(value: JsonObject): Boolean =
		value.get(SCHEMA_VERSION_FIELD)?.asInt == CONFIG_SCHEMA_VERSION

	private fun normalized(config: BridgeConfig?): BridgeConfig {
		val value = config ?: BridgeConfig()
		value.backendBaseUrl = MOD_BACKEND_BASE_URL
		value.minigameBackendBaseUrl = MOD_BACKEND_BASE_URL
		value.devBackendBaseUrl = normalizeServerBaseUrl(value.devBackendBaseUrl, DEFAULT_DEV_BACKEND_BASE_URL)
		value.ircServerBaseUrl = normalizeConfiguredIrcServerBaseUrl(value.ircServerBaseUrl)
		value.backendAuthToken = normalizeAuthToken(value.backendAuthToken)
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
		value.purpleTerracottaHighlightColorHex = normalizedHexColor(value.purpleTerracottaHighlightColorHex, "#B06CFF")
		value.duplicoEspModuleEnabled = normalizedBoolean(value.duplicoEspModuleEnabled)
		value.hideyhoEspModuleEnabled = normalizedBoolean(value.hideyhoEspModuleEnabled)
		value.safariEspMode = SafariEspMode.normalize(value.safariEspMode)
		value.wormholeDepartureAlertSoundId = SoundCatalog.normalizeSoundId(value.wormholeDepartureAlertSoundId)
		value.wormholeDepartureAlertSoundVolume = value.wormholeDepartureAlertSoundVolume.coerceIn(0.0f, 2.0f)
		value.wormholeDepartureAlertSoundPitch = value.wormholeDepartureAlertSoundPitch.coerceIn(0.1f, 2.0f)
		value.timeChangerMode = value.timeChangerMode.coerceIn(0, ClientTimeChanger.modeCount - 1)
		value.autoExperimentsClickDelayMs = value.autoExperimentsClickDelayMs.coerceIn(50, 5_000)
		value.autoExperimentsDelayVarietyMs = value.autoExperimentsDelayVarietyMs.coerceIn(0, 5_000)
		value.highClassDiceTrackerEnabled = normalizedBoolean(value.highClassDiceTrackerEnabled)
		value.autoExperimentsSerumCount = value.autoExperimentsSerumCount.coerceIn(0, 3)
		value.dungeonDoorMode = value.dungeonDoorMode.coerceIn(0, MortDoorBarrierFeature.modeCount - 1)
		value.pestEspColorHex = normalizedHexColor(value.pestEspColorHex, "#7CFF6B")
		value.mobModelEntityType = normalizeEntityTypeId(value.mobModelEntityType)
		value.mobModelVariant = normalizeMobModelVariant(value.mobModelVariant)
		value.mobModelScale = value.mobModelScale.coerceIn(0.25f, 4.0f)
		value.itemUpdateFixModuleEnabled = normalizedBoolean(value.itemUpdateFixModuleEnabled)
		value.customCrosshairPattern = CustomCrosshairFeature.normalizePattern(value.customCrosshairPattern)
		value.silentDisconnectLastStatus = normalizeSilentDisconnectStatus(value.silentDisconnectLastStatus)
		value.dungeonAutoKickFloor = normalizeDungeonAutoKickFloor(value.dungeonAutoKickFloor)
		value.dungeonAutoKickMaxPbSeconds = value.dungeonAutoKickMaxPbSeconds.coerceIn(60, 900)
		value.dungeonAutoKickMinSecretsThousands = value.dungeonAutoKickMinSecretsThousands.coerceIn(0, 200)
		value.dungeonAutoKickMinMagicalPower = value.dungeonAutoKickMinMagicalPower.coerceIn(0, 2_500)
		value.pickaxeAbilityCooldownAlertSoundId = SoundCatalog.normalizeSoundId(value.pickaxeAbilityCooldownAlertSoundId)
		value.pickaxeAbilityCooldownAlertSoundVolume = value.pickaxeAbilityCooldownAlertSoundVolume.coerceIn(0.0f, 2.0f)
		value.pickaxeAbilityCooldownAlertSoundPitch = value.pickaxeAbilityCooldownAlertSoundPitch.coerceIn(0.1f, 2.0f)
		value.pickobulusHelperModuleEnabled = normalizedBoolean(value.pickobulusHelperModuleEnabled)
		value.chimeraBookDropEffectsSoundId = SoundCatalog.normalizeSoundId(value.chimeraBookDropEffectsSoundId)
		value.chimeraBookDropEffectsSoundVolume = value.chimeraBookDropEffectsSoundVolume.coerceIn(0.0f, 2.0f)
		value.chimeraBookDropEffectsSoundPitch = value.chimeraBookDropEffectsSoundPitch.coerceIn(0.1f, 2.0f)
		value.pickaxeAbilityCooldownAlertText = normalizedTemplate(value.pickaxeAbilityCooldownAlertText, PickaxeAbilityCooldownFeature.DEFAULT_ALERT_TEXT)
		value.fireFreezeCircleColorHex = normalizedHexColor(value.fireFreezeCircleColorHex, "#00F5FF")
		value.fireFreezeCircleLineWidth = value.fireFreezeCircleLineWidth.coerceIn(1.0f, 8.0f)
		value.fireFreezeRefreezeAlertSoundId = SoundCatalog.normalizeSoundId(value.fireFreezeRefreezeAlertSoundId)
		value.fireFreezeRefreezeAlertSoundVolume = value.fireFreezeRefreezeAlertSoundVolume.coerceIn(0.0f, 2.0f)
		value.fireFreezeRefreezeAlertSoundPitch = value.fireFreezeRefreezeAlertSoundPitch.coerceIn(0.1f, 2.0f)
		value.mineshaftAutoWarpCorpseRule = normalizedTemplate(value.mineshaftAutoWarpCorpseRule, "")
		value.mineshaftAutoWarpDelayMs = value.mineshaftAutoWarpDelayMs.coerceIn(500L, 30_000L)
		value.mineshaftAutoWarpWindowMs = value.mineshaftAutoWarpWindowMs.coerceIn(5_000L, 60_000L)
		value.slayerRngMeterDisplayEnabled = normalizedBoolean(value.slayerRngMeterDisplayEnabled)
		value.slayerRngMeterOptimalRemovalEnabled = normalizedBoolean(value.slayerRngMeterOptimalRemovalEnabled)
		value.slayerRngMeterCompactMode = normalizedBoolean(value.slayerRngMeterCompactMode)
		value.slayerRngMeterUseMagicFind = normalizedBoolean(value.slayerRngMeterUseMagicFind)
		value.slayerRngMeterMagicFind = value.slayerRngMeterMagicFind.coerceIn(0, 900)
		value.slayerRngMeterActiveSlayer = normalizedShortKey(value.slayerRngMeterActiveSlayer)
		value.slayerRngMeterState = (value.slayerRngMeterState ?: mutableMapOf())
			.mapKeys { entry -> normalizedShortKey(entry.key) }
			.filterKeys { it.isNotBlank() }
			.mapValues { entry -> normalizeSlayerRngMeterState(entry.value) }
			.toMutableMap()
		value.slayerRngMeterWikiCacheUpdatedAtMs = value.slayerRngMeterWikiCacheUpdatedAtMs.coerceAtLeast(0L)
		value.slayerRngMeterWikiCache = (value.slayerRngMeterWikiCache ?: mutableMapOf())
			.mapKeys { entry -> normalizedShortKey(entry.key) }
			.filterKeys { it.isNotBlank() }
			.mapValues { entry -> normalizeSlayerRngMeterDropCache(entry.value) }
			.toMutableMap()
		value.slayerSpawnAnnouncerText = normalizedTemplate(value.slayerSpawnAnnouncerText, SlayerFeature.DEFAULT_ANNOUNCER_TEXT)
		value.slayerSpawnAnnouncerSoundId = SoundCatalog.normalizeSoundId(value.slayerSpawnAnnouncerSoundId)
		value.slayerSpawnAnnouncerSoundVolume = value.slayerSpawnAnnouncerSoundVolume.coerceIn(0.0f, 2.0f)
		value.slayerSpawnAnnouncerSoundPitch = value.slayerSpawnAnnouncerSoundPitch.coerceIn(0.1f, 2.0f)
		return value
	}

	private fun safeString(value: String?, fallback: String): String = value ?: fallback

	private fun normalizedBoolean(value: Boolean): Boolean = value

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

	private fun normalizedShortKey(value: String?): String {
		val candidate = safeString(value, "")
			.replace('\r', ' ')
			.replace('\n', ' ')
			.replace(Regex("\\s+"), " ")
			.trim()
		return if (candidate.length > 96) candidate.substring(0, 96) else candidate
	}

	private fun normalizeSlayerRngMeterState(value: SlayerRngMeterState?): SlayerRngMeterState {
		val state = value ?: SlayerRngMeterState()
		state.currentMeter = state.currentMeter.coerceAtLeast(-1L)
		state.gainPerBoss = state.gainPerBoss.coerceAtLeast(-1L)
		state.goalNeeded = state.goalNeeded.coerceAtLeast(-1L)
		state.itemGoal = normalizedShortKey(state.itemGoal)
		state.lastSelectedItemGoal = normalizedShortKey(state.lastSelectedItemGoal)
		state.oddsPercent = state.oddsPercent.takeIf { it.isFinite() }?.coerceIn(-1.0, 100.0) ?: -1.0
		state.lastSelectedOddsPercent = state.lastSelectedOddsPercent.takeIf { it.isFinite() }?.coerceIn(-1.0, 100.0) ?: -1.0
		return state
	}

	private fun normalizeSlayerRngMeterDropCache(value: SlayerRngMeterDropCache?): SlayerRngMeterDropCache {
		val cache = value ?: SlayerRngMeterDropCache()
		cache.slayer = normalizedShortKey(cache.slayer)
		cache.item = normalizedShortKey(cache.item)
		cache.oddsPercent = cache.oddsPercent.takeIf { it.isFinite() }?.coerceIn(-1.0, 100.0) ?: -1.0
		cache.goalNeeded = cache.goalNeeded.coerceAtLeast(-1L)
		return cache
	}

	private fun normalizeServerBaseUrl(value: String?, fallback: String): String {
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

	private fun normalizeConfiguredIrcServerBaseUrl(value: String?): String {
		val candidate = safeString(value, "http://127.0.0.1:8765").trim()
		return normalizeIrcServerBaseUrl(candidate) ?: ""
	}

	private fun normalizeAuthToken(value: String?): String {
		val candidate = safeString(value, "change-me").trim()
		if (!isValidIrcAuthToken(candidate)) {
			return ""
		}
		return candidate
	}

	private fun normalizedHexColor(value: String?, fallback: String): String {
		return ClientColor.normalize(value, fallback)
	}

	private fun normalizeEntityTypeId(value: String?): String {
		val fallback = "minecraft:zombie"
		val candidate = safeString(value, fallback)
			.trim()
			.lowercase(Locale.ROOT)
		if (candidate.isBlank()) {
			return fallback
		}
		val namespaced = if (':' in candidate) candidate else "minecraft:$candidate"
		return if (ENTITY_TYPE_PATTERN.matches(namespaced)) namespaced else fallback
	}

	private fun normalizeMobModelVariant(value: String?): String {
		val candidate = safeString(value, "")
			.replace('\r', ' ')
			.replace('\n', ' ')
			.trim()
			.lowercase(Locale.ROOT)
		return if (candidate.length <= 96) candidate else candidate.substring(0, 96)
	}

	private fun normalizeSilentDisconnectStatus(value: String?): String {
		val candidate = safeString(value, "online")
			.trim()
			.lowercase(Locale.ROOT)
		return if (SILENT_DISCONNECT_STATUSES.contains(candidate)) candidate else "online"
	}

	private fun normalizeDungeonAutoKickFloor(value: String?): String {
		val candidate = safeString(value, "7")
			.trim()
			.removePrefix("F")
			.removePrefix("f")
			.removePrefix("M")
			.removePrefix("m")
		return if (DUNGEON_AUTOKICK_FLOORS.contains(candidate)) candidate else "7"
	}

	companion object {
		const val MOD_BACKEND_BASE_URL = "https://api.xclipsen.de"
		const val DEFAULT_DEV_BACKEND_BASE_URL = "http://127.0.0.1:8765"
		private const val SCHEMA_VERSION_FIELD = "schemaVersion"
		private const val CONFIG_SCHEMA_VERSION = 1
		private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
		private val SHARED_CONFIG_FIELDS = setOf(
			"backendBaseUrl",
			"minigameBackendBaseUrl",
			"devModeEnabled",
			"devBackendBaseUrl",
			"ircServerBaseUrl",
			"backendAuthToken",
			"backendPollIntervalMs",
			"checkForUpdatesEnabled",
			"autoUpdateEnabled",
			"ircCommandFormat",
		)
		private val ENTITY_TYPE_PATTERN = Regex("[a-z0-9_.-]+:[a-z0-9_/.-]+")
		private val SILENT_DISCONNECT_STATUSES = setOf("online", "busy", "away", "offline")
		private val DUNGEON_AUTOKICK_FLOORS = setOf("1", "2", "3", "4", "5", "6", "7")
	}
}

fun normalizeIrcServerBaseUrl(value: String): String? {
	return try {
		val uri = URI.create(value)
		val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
		val host = uri.host?.lowercase(Locale.ROOT) ?: return null
		if (scheme != "https" && !(scheme == "http" && isLoopbackHost(host))) return null
		if (uri.userInfo != null || uri.rawPath.orEmpty().isNotEmpty() && uri.rawPath != "/" || uri.rawQuery != null || uri.rawFragment != null) return null
		if (uri.port == 0 || uri.port > 65_535) return null
		URI(scheme, null, host, uri.port, null, null, null).toString().removeSuffix("/")
	} catch (_: IllegalArgumentException) {
		null
	} catch (_: URISyntaxException) {
		null
	}
}

private fun isLoopbackHost(host: String): Boolean =
	host == "localhost" || host == "::1" || host.split('.').let { parts ->
		parts.size == 4 && parts[0] == "127" && parts.all { part -> part.toIntOrNull() in 0..255 }
	}

fun isValidIrcAuthToken(value: String): Boolean =
	value.isNotBlank() && value.length <= 512 && value.none { it.isISOControl() }

fun activeModBackendBaseUrl(config: BridgeConfig): String =
	if (config.devModeEnabled) config.devBackendBaseUrl else BridgeConfigManager.MOD_BACKEND_BASE_URL
