package de.xclipsen.ircbridge

import com.autocroesus.util.ColorUtil
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object DungeonAutoKickFeature {
	private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "xclipsen-dungeon-autokick").apply { isDaemon = true }
	}
	private val kickedCache = mutableSetOf<String>()
	private val pendingLookups = mutableSetOf<String>()
	private var lastCommandSentAt = 0L
	private var lastStatus = "Idle"

	fun onTick(client: Minecraft) {
		return
	}

	fun onIncomingMessage(message: Component?) {
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

	fun showCataStats(playerName: String) {
		val safePlayerName = cleanPlayerName(playerName)
		if (!USERNAME_PATTERN.matches(safePlayerName)) {
			sendClientMessage("Usage: /cata <player>", ChatFormatting.RED)
			return
		}

		executor.execute {
			val client = Minecraft.getInstance()
			try {
				val response = XclipsenIrcBridgeClient.instance?.backendBridge()?.fetchDungeonStats(safePlayerName)
				client.execute {
					if (response == null) {
						sendClientMessage("Cata: backend unreachable for $safePlayerName.", ChatFormatting.RED)
						return@execute
					}
					if (!response.ok) {
						sendClientMessage("Cata: ${response.error.ifBlank { "Stats unavailable for $safePlayerName" }}", ChatFormatting.RED)
						return@execute
					}
					sendOdinStatsCard(response, XclipsenIrcBridgeClient.instance?.config() ?: BridgeConfig(), includeKickLine = false)
				}
			} catch (exception: Exception) {
				client.execute {
					sendClientMessage("Cata: failed to check $safePlayerName: ${exception.message ?: exception::class.java.simpleName}", ChatFormatting.RED)
				}
			}
		}
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
				val client = Minecraft.getInstance()
				if (config.dungeonAutoKickAutoKickEnabled && config.dungeonAutoKickCacheEnabled && kickedCache.contains(key)) {
					client.execute {
						sendCommand(client, "party kick $playerName", forceDelay = true)
						sendClientMessage("Kicked $playerName because they are in the AutoKick cache.", ChatFormatting.YELLOW)
					}
					return@execute
				}

				val response = XclipsenIrcBridgeClient.instance?.backendBridge()?.fetchDungeonStats(playerName)
				client.execute {
					if (response == null) {
						lastStatus = "Backend unreachable for $playerName"
						sendClientMessage("Dungeon AutoKick: backend unreachable for $playerName.", ChatFormatting.RED)
						return@execute
					}
					if (!response.ok) {
						lastStatus = response.error.ifBlank { "Stats unavailable for $playerName" }
						sendClientMessage("Dungeon AutoKick: ${lastStatus}", ChatFormatting.RED)
						return@execute
					}

					if (config.dungeonAutoKickStatsDisplayEnabled) {
						sendOdinStatsCard(response, config)
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
					sendClientMessage("Kicking $playerName for: ${reasons.joinToString("; ")}", ChatFormatting.YELLOW)
				}
			} catch (exception: Exception) {
				lastStatus = "Stats check failed for $playerName"
				Minecraft.getInstance().execute {
					sendClientMessage("Dungeon AutoKick: failed to check $playerName: ${exception.message ?: exception::class.java.simpleName}", ChatFormatting.RED)
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
			reasons += "Couldn't confirm completion status for ${floorLabel.lowercase(Locale.ROOT)}"
		} else if (pbMs > config.dungeonAutoKickMaxPbSeconds * 1000L) {
			reasons += "Did not meet time req for ${floorLabel.lowercase(Locale.ROOT)}: ${formatTime(pbMs)}/${formatTime(config.dungeonAutoKickMaxPbSeconds * 1000L, 0)}"
		}

		val requiredSecrets = config.dungeonAutoKickMinSecretsThousands * 1000L
		if (stats.adjustedSecrets < requiredSecrets) {
			reasons += "Did not meet secret req: ${formatNumber(stats.adjustedSecrets)}/${config.dungeonAutoKickMinSecretsThousands}k"
		}

		if (stats.inventoryApi) {
			if (stats.magicalPower < config.dungeonAutoKickMinMagicalPower) {
				reasons += "Did not meet mp req: ${stats.magicalPower}/${config.dungeonAutoKickMinMagicalPower}"
			}
		} else if (config.dungeonAutoKickApiOffKickEnabled) {
			reasons += "Inventory API is off"
		}

		return reasons
	}

	private fun sendOdinStatsCard(response: BackendDungeonStatsResponse, config: BridgeConfig, includeKickLine: Boolean = config.dungeonAutoKickSendKickLineEnabled) {
		val stats = response.stats
		val card = Component.literal("§d§m           §r §b${response.username} §d§m           §r\n")
			.append(buildCataSecretsBloodLine(stats))
			.append(buildClassLevelsLine(stats))
			.append(buildFloorTimesLine(stats))
			.apply {
				if (stats.armor.isNotEmpty()) append(buildArmorLine(stats.armor))
				if (stats.missingItems.isNotEmpty()) append(buildMissingItemsLine(stats.missingItems))
			}
			.append(Component.literal("§d§m                           §r"))

		sendClientText(card)

		if (includeKickLine) {
			sendClientText(
				Component.literal("§aPress to kick ${response.username}").withStyle {
					it.withClickEvent(ClickEvent.RunCommand("/party kick ${response.username}"))
				},
			)
		}
	}

	private fun buildCataSecretsBloodLine(stats: BackendDungeonStats): MutableComponent {
		val totalRuns = stats.totalRuns.coerceAtLeast(0)
		val watcherKills = stats.watcherKills.takeIf { it > 0L } ?: stats.bloodMobKills
		return hover(
			Component.literal("§7Cata: §e${formatFixed(stats.catacombsLevel)}"),
			Component.literal("§7Catacombs Level\n§7XP: §b${formatNumber(stats.catacombsXp)}"),
		).append(hover(
			Component.literal(" §8| §7Secrets: §e${formatNumber(stats.secrets)} §8(§b${formatFixed(stats.averageSecrets, 1)}§8)"),
			Component.literal("§7Total Secrets: §e${formatNumber(stats.secrets)}\n§7Total Runs: §b$totalRuns\n§7Average: §a${formatFixed(stats.averageSecrets)}"),
		)).append(hover(
			Component.literal(" §8| §7Blood: §c${formatNumber(watcherKills)}"),
			Component.literal("§7Total Watcher Kills: §c${formatNumber(watcherKills)}\n§7Blood Mobs Killed: §5${formatNumber(stats.bloodMobKills)}"),
		)).append(Component.literal("\n"))
	}

	private fun buildClassLevelsLine(stats: BackendDungeonStats): MutableComponent {
		val classEntries = listOf(
			ClassDisplay("archer", "Archer", "§6"),
			ClassDisplay("berserk", "Berserk", "§4"),
			ClassDisplay("healer", "Healer", "§d"),
			ClassDisplay("mage", "Mage", "§b"),
			ClassDisplay("tank", "Tank", "§2"),
		)
		val classLevels = classEntries.map { stats.classes[it.key]?.level ?: 0.0 }
		val classAvg = stats.classAverage.takeIf { it > 0.0 } ?: classLevels.average().takeUnless { it.isNaN() } ?: 0.0
		val totalClassXp = stats.totalClassXp.takeIf { it > 0.0 } ?: classEntries.sumOf { stats.classes[it.key]?.xp ?: 0.0 }
		return Component.literal("§7Classes: ").apply {
			classEntries.forEachIndexed { index, entry ->
				val classStats = stats.classes[entry.key] ?: BackendDungeonClassStats()
				append(hover(
					Component.literal("${entry.color}${formatFixed(classStats.level)}"),
					Component.literal("${entry.color}${entry.displayName} ${entry.color}Level\n§7XP: §b${formatNumber(classStats.xp)}"),
				))
				if (index < classEntries.lastIndex) append(Component.literal("§8/"))
			}
			append(hover(
				Component.literal(" §8(§7Avg: §a${formatFixed(classAvg, 1)}§8)"),
				Component.literal("§7Class Average\n§7Total Class XP: §b${formatNumber(totalClassXp)}"),
			))
			append(Component.literal("\n"))
		}
	}

	private fun buildFloorTimesLine(stats: BackendDungeonStats): MutableComponent {
		return Component.literal("§7Floors: ")
			.append(hover(Component.literal("§6Normal"), buildFloorHover(stats.floors.normal, "§6§lNormal Floors", "§eF")))
			.append(Component.literal(" §8| "))
			.append(hover(Component.literal("§cMaster"), buildFloorHover(stats.floors.master, "§c§lMaster Floors", "§cM")))
			.append(Component.literal(" §8| "))
			.append(hover(
				Component.literal("§7MP: §d${formatNumber(stats.magicalPower.toLong())}"),
				Component.literal("§bTunings").apply {
					stats.tunings.forEach { tuning -> append(Component.literal("\n§7- §e$tuning")) }
				},
			))
			.append(Component.literal("\n"))
	}

	private fun buildFloorHover(floors: Map<String, BackendDungeonFloorStats>, title: String, floorPrefix: String): Component {
		return Component.literal(title).apply {
			for (floor in 1..7) {
				val floorStats = floors[floor.toString()]
				val sPlusMs = floorStats?.sPlusPbMs ?: 0L
				val bestMs = floorStats?.bestTimeMs ?: 0L
				val completions = floorStats?.completions ?: 0
				val time = when {
					sPlusMs > 0L -> "§a${formatTime(sPlusMs, 2)}"
					bestMs > 0L -> "§7${formatTime(bestMs, 2)}"
					else -> "§8None"
				}
				append(Component.literal("\n$floorPrefix$floor: $time §8(§b$completions§8)"))
			}
		}
	}

	private fun buildArmorLine(armor: List<BackendDungeonArmorPiece>): MutableComponent {
		return Component.literal("§7Armor: ").apply {
			armor.take(4).forEachIndexed { index, piece ->
				val slotLabel = when (piece.slot.lowercase(Locale.ROOT)) {
					"helmet" -> "⛑"
					"chestplate" -> "🛡"
					"leggings" -> "👖"
					"boots" -> "👢"
					else -> piece.slot.ifBlank { "?" }
				}
				val hoverText = Component.literal(piece.displayName.ifBlank { "§8Empty Slot" }).apply {
					piece.lore.forEach { loreLine -> append(Component.literal("\n$loreLine")) }
				}
				append(hover(Component.literal(slotLabel), hoverText))
				if (index < armor.take(4).lastIndex) append(Component.literal(" §8| "))
			}
			append(Component.literal("\n"))
		}
	}

	private fun buildMissingItemsLine(missing: List<BackendDungeonMissingItem>): MutableComponent {
		return Component.literal("§7Missing: ").apply {
			missing.forEachIndexed { index, item ->
				append(hover(
					Component.literal("§c✖ ${item.shortName.ifBlank { item.name }}"),
					Component.literal("§cMissing ${item.name.ifBlank { item.shortName }}"),
				))
				if (index < missing.lastIndex) append(Component.literal(" §8| "))
			}
			append(Component.literal("\n"))
		}
	}

	private fun hover(text: MutableComponent, hoverText: Component): MutableComponent {
		return text.withStyle { it.withHoverEvent(HoverEvent.ShowText(hoverText)) }
	}

	private fun sendCommand(client: Minecraft, command: String, forceDelay: Boolean = false): Boolean {
		val networkHandler = client.player?.connection ?: return false
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
		networkHandler.sendCommand(command)
		lastCommandSentAt = now
		return true
	}

	private fun sendClientMessage(message: String, color: ChatFormatting) {
		Minecraft.getInstance().player?.sendSystemMessage(
			Component.literal("[Xclipsen] $message").withStyle(color),
		)
	}

	private fun sendClientText(message: Component) {
		Minecraft.getInstance().player?.sendSystemMessage(message)
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

	private fun localPlayerName(): String = Minecraft.getInstance().user.name

	private fun formatNumber(value: Long): String = formatNumber(value.toDouble())

	private fun formatNumber(value: Int): String = formatNumber(value.toDouble())

	private fun formatNumber(value: Double): String {
		val abs = kotlin.math.abs(value)
		return when {
			abs >= 1_000_000_000.0 -> "%.2fB".format(Locale.US, value / 1_000_000_000.0)
			abs >= 1_000_000.0 -> "%.2fM".format(Locale.US, value / 1_000_000.0)
			abs >= 1_000.0 -> "%.2fK".format(Locale.US, value / 1_000.0)
			else -> "%.0f".format(Locale.US, value)
		}
	}

	private fun formatFixed(value: Double, decimals: Int = 2): String {
		return "%.${decimals}f".format(Locale.US, value)
	}

	private fun formatTime(ms: Long, decimalPlaces: Int = 2): String {
		if (ms <= 0L) return "0s"
		var remaining = ms
		val hours = (remaining / 3_600_000L).toInt()
		remaining -= hours * 3_600_000L
		val minutes = (remaining / 60_000L).toInt()
		remaining -= minutes * 60_000L
		return buildString {
			if (hours > 0) append(hours).append("h ")
			if (minutes > 0) append(minutes).append("m ")
			append(formatFixed(remaining / 1000.0, decimalPlaces)).append("s")
		}
	}

	private data class ClassDisplay(val key: String, val displayName: String, val color: String)

	private val PARTY_FINDER_JOIN_PATTERN = Regex("^Party Finder > (?:\\[[^]]{1,7}])? ?([A-Za-z0-9_]{3,16}) joined the dungeon group! \\(.*\\)$")
	private val USERNAME_PATTERN = Regex("[A-Za-z0-9_]{3,16}")
	private val WHITESPACE_PATTERN = Regex("\\s+")
	private const val COMMAND_GAP_MS = 1_250L
}
