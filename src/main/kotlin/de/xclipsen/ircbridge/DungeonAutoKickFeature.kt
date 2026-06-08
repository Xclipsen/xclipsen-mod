package de.xclipsen.ircbridge

import com.autocroesus.util.ColorUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object DungeonAutoKickFeature {
	private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "xclipsen-dungeon-autokick").apply { isDaemon = true }
	}
	private val kickedCache = mutableSetOf<String>()
	private val pendingLookups = mutableSetOf<String>()
	private var lastCommandSentAt = 0L
	private var lastStatus = "Idle"

	fun onTick(client: MinecraftClient) {
		return
	}

	fun onIncomingMessage(message: Text?) {
		val normalized = normalize(message?.string ?: return)
		if (normalized.isBlank()) {
			return
		}

		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.dungeonAutoKickModuleEnabled || !LocationTracker.isOnHypixelSkyBlock) {
			return
		}
		val playerName = PARTY_FINDER_JOIN_PATTERN.matchEntire(normalized)?.groupValues?.getOrNull(1) ?: return
		if (playerName.equals(localPlayerName(), ignoreCase = true)) {
			return
		}
		onPartyFinderJoin(playerName, config.copy())
	}

	fun onDisconnect() {
		pendingLookups.clear()
		lastStatus = "Disconnected"
	}

	fun clearKickCache() {
		kickedCache.clear()
		lastStatus = "Kick cache cleared"
	}

	fun statusLine(): String {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return "Unavailable"
		if (!config.dungeonAutoKickModuleEnabled) {
			return "Disabled"
		}
		return "Cache: ${kickedCache.size} | $lastStatus"
	}

	private fun onPartyFinderJoin(playerName: String, config: BridgeConfig) {
		val key = playerName.lowercase(Locale.ROOT)
		if (!pendingLookups.add(key)) {
			return
		}
		lastStatus = "Checking $playerName"

		executor.execute {
			try {
				val client = MinecraftClient.getInstance()
				if (config.dungeonAutoKickAutoKickEnabled && config.dungeonAutoKickCacheEnabled && kickedCache.contains(key)) {
					client.execute {
						sendCommand(client, "party kick $playerName", forceDelay = true)
						sendClientMessage("Kicked $playerName because they are in the AutoKick cache.", Formatting.YELLOW)
					}
					return@execute
				}

				val response = XclipsenIrcBridgeClient.instance?.backendBridge()?.fetchDungeonStats(playerName)
				client.execute {
					if (response == null) {
						lastStatus = "Backend unreachable for $playerName"
						sendClientMessage("Dungeon AutoKick: backend unreachable for $playerName.", Formatting.RED)
						return@execute
					}
					if (!response.ok) {
						lastStatus = response.error.ifBlank { "Stats unavailable for $playerName" }
						sendClientMessage("Dungeon AutoKick: ${lastStatus}", Formatting.RED)
						return@execute
					}

					if (config.dungeonAutoKickStatsDisplayEnabled) {
						sendStatsLine(response)
					}

					val reasons = kickReasons(response, config)
					if (reasons.isEmpty()) {
						lastStatus = "$playerName passed"
						return@execute
					}

					lastStatus = "$playerName failed ${reasons.size} requirement(s)"
					if (!config.dungeonAutoKickAutoKickEnabled) {
						return@execute
					}

					if (config.dungeonAutoKickInformKickedEnabled) {
						sendCommand(client, "pc Kicked $playerName for: ${reasons.joinToString(", ")}", forceDelay = true)
						executor.execute {
							Thread.sleep(COMMAND_GAP_MS + 250L)
							client.execute {
								sendCommand(client, "party kick $playerName", forceDelay = true)
							}
						}
					} else {
						sendCommand(client, "party kick $playerName", forceDelay = true)
					}
					if (config.dungeonAutoKickCacheEnabled) {
						kickedCache += key
					}
					sendClientMessage("Kicking $playerName for: ${reasons.joinToString("; ")}", Formatting.YELLOW)
				}
			} finally {
				pendingLookups.remove(key)
			}
		}
	}

	private fun kickReasons(response: BackendDungeonStatsResponse, config: BridgeConfig): List<String> {
		val stats = response.stats
		val floor = config.dungeonAutoKickFloor
		val floorStats = if (config.dungeonAutoKickMasterMode) stats.floors.master[floor] else stats.floors.normal[floor]
		val floorLabel = "${if (config.dungeonAutoKickMasterMode) "M" else "F"}$floor"
		val reasons = mutableListOf<String>()
		val pbMs = floorStats?.sPlusPbMs ?: 0L
		if (pbMs <= 0L) {
			reasons += "Couldn't confirm S+ PB for $floorLabel"
		} else if (pbMs > config.dungeonAutoKickMaxPbSeconds * 1000L) {
			reasons += "PB $floorLabel ${formatDuration(pbMs)}>${formatDuration(config.dungeonAutoKickMaxPbSeconds * 1000L)}"
		}

		val requiredSecrets = config.dungeonAutoKickMinSecretsThousands * 1000L
		if (stats.adjustedSecrets < requiredSecrets) {
			reasons += "Secrets ${formatCompact(stats.adjustedSecrets)}/${config.dungeonAutoKickMinSecretsThousands}k"
		}

		if (stats.inventoryApi) {
			if (stats.magicalPower < config.dungeonAutoKickMinMagicalPower) {
				reasons += "MP ${stats.magicalPower}/${config.dungeonAutoKickMinMagicalPower}"
			}
		} else if (config.dungeonAutoKickApiOffKickEnabled) {
			reasons += "Inventory API off"
		}

		return reasons
	}

	private fun sendStatsLine(response: BackendDungeonStatsResponse) {
		val stats = response.stats
		val classes = listOf(
			"H" to stats.classes["healer"],
			"M" to stats.classes["mage"],
			"B" to stats.classes["berserk"],
			"A" to stats.classes["archer"],
			"T" to stats.classes["tank"],
		).joinToString("/") { (label, value) -> "$label${formatDecimal(value ?: 0.0)}" }
		sendClientMessage(
			"${response.username}: Cata ${formatDecimal(stats.catacombsLevel)} | Secrets ${formatCompact(stats.secrets)} | Blood ${formatCompact(stats.bloodMobKills)} | Classes $classes | Avg ${formatDecimal(stats.averageSecrets)} | MP ${formatCompact(stats.magicalPower.toLong())}",
			Formatting.AQUA,
		)
	}

	private fun sendCommand(client: MinecraftClient, command: String, forceDelay: Boolean = false): Boolean {
		val networkHandler = client.player?.networkHandler ?: return false
		val now = System.currentTimeMillis()
		if (now - lastCommandSentAt < COMMAND_GAP_MS) {
			if (!forceDelay) {
				return false
			}
			val delay = COMMAND_GAP_MS - (now - lastCommandSentAt) + 100L
			executor.execute {
				Thread.sleep(delay)
				client.execute {
					sendCommand(client, command, forceDelay = false)
				}
			}
			return true
		}
		networkHandler.sendChatCommand(command)
		lastCommandSentAt = now
		return true
	}

	private fun sendClientMessage(message: String, color: Formatting) {
		MinecraftClient.getInstance().player?.sendMessage(
			Text.literal("[Xclipsen] $message").formatted(color),
			false,
		)
	}

	private fun normalize(raw: String): String {
		return ColorUtil.stripColors(raw)
			.replace('\r', ' ')
			.replace('\n', ' ')
			.replace(WHITESPACE_PATTERN, " ")
			.trim()
	}

	private fun cleanPlayerName(raw: String): String {
		var current = normalize(raw)
		while (current.startsWith("[")) {
			val closing = current.indexOf(']')
			if (closing <= 0) break
			current = current.substring(closing + 1).trimStart()
		}
		return USERNAME_PATTERN.findAll(current).lastOrNull()?.value ?: current.trim()
	}

	private fun localPlayerName(): String = MinecraftClient.getInstance().session?.username.orEmpty()

	private fun formatCompact(value: Long): String {
		val abs = kotlin.math.abs(value)
		return when {
			abs >= 1_000_000 -> "${formatDecimal(value / 1_000_000.0)}m"
			abs >= 1_000 -> "${formatDecimal(value / 1_000.0)}k"
			else -> value.toString()
		}
	}

	private fun formatDecimal(value: Double): String {
		val rounded = (value * 10.0).roundToInt() / 10.0
		return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else "%.1f".format(Locale.ROOT, rounded)
	}

	private fun formatDuration(ms: Long): String {
		val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
		return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
	}

	private val PARTY_FINDER_JOIN_PATTERN = Regex("^Party Finder > (?:\\[[^]]{1,7}])? ?([A-Za-z0-9_]{3,16}) joined the dungeon group! \\(.*\\)$")
	private val USERNAME_PATTERN = Regex("[A-Za-z0-9_]{3,16}")
	private val WHITESPACE_PATTERN = Regex("\\s+")
	private const val COMMAND_GAP_MS = 1_250L
}
