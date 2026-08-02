package de.xclipsen.ircbridge

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.inventory.Slot
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.network.chat.Component
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

object PartyFinderFeature {
	private val executor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "xclipsen-party-finder").apply { isDaemon = true }
	}
	@Volatile
	private var parties: Map<Int, PartyFinderData> = emptyMap()
	private val statsCache = ConcurrentHashMap<String, BackendDungeonStatsResponse>()
	private val pendingStats = CopyOnWriteArraySet<String>()
	private var inPartyFinder = false
	private var inCatacombsGate = false
	private var currentRole: String? = null
	private var activeContainerId = -1
	private var activeRevision = -1

	fun statusLine(): String =
		"enabled=${isEnabled()}, screen=$inPartyFinder, catacombsGate=$inCatacombsGate, role=${currentRole ?: "unknown"}, " +
			"parties=${parties.size}, cachedPlayers=${statsCache.size}, pending=${pendingStats.size}"

	fun onTick(client: Minecraft) {
		val screen = client.screen as? AbstractContainerScreen<*> ?: return
		if (!isEnabled() || !isPartyFinderScreen(screen)) {
			return
		}

		if (parties.isEmpty()) {
			scanCurrentScreen(screen)
		}
		if (isGuiStatsEnabled()) {
			applyOverviewLore(screen)
		}
	}

	fun onDisconnect() {
		reset()
		statsCache.clear()
		pendingStats.clear()
	}

	fun shutdown() {
		onDisconnect()
		executor.shutdownNow()
	}

	fun onServerContainerOpen(syncId: Int, title: Component) {
		val titleString = title.string
		inPartyFinder = titleString == "Party Finder"
		inCatacombsGate = titleString == "Catacombs Gate"
		activeContainerId = if (inPartyFinder || inCatacombsGate) syncId else -1
		activeRevision = -1
		if (!inPartyFinder) {
			parties = emptyMap()
		}
	}

	fun onServerContainerClose(syncId: Int) {
		if (syncId == activeContainerId) reset()
	}

	fun onServerContainerContent(syncId: Int, revision: Int, stacks: List<ItemStack>) {
		if (!isEnabled() || syncId != activeContainerId || revision < activeRevision) {
			return
		}
		activeRevision = revision
		if (inCatacombsGate) {
			parseCurrentRole(stacks.getOrNull(45))
			inCatacombsGate = false
			return
		}
		if (!inPartyFinder) {
			return
		}
		scanStacks(stacks)
	}

	fun onServerContainerSlot(syncId: Int, revision: Int, slot: Int, stack: ItemStack) {
		if (!isEnabled() || syncId != activeContainerId || revision < activeRevision) {
			return
		}
		activeRevision = revision
		if (inCatacombsGate && slot == 45) {
			parseCurrentRole(stack)
			inCatacombsGate = false
			return
		}
		if (!inPartyFinder) {
			return
		}
		val screen = Minecraft.getInstance().screen as? AbstractContainerScreen<*> ?: return
		if (isPartyFinderScreen(screen)) {
			scanCurrentScreen(screen)
		}
	}

	fun beforeDrawSlot(context: GuiGraphicsExtractor, screen: AbstractContainerScreen<*>, slot: Slot) {
		if (!isEnabled() || !isHighlightsEnabled() || !isPartyFinderScreen(screen) || slot.container == Minecraft.getInstance().player?.inventory) {
			return
		}
		val data = parties[slot.index] ?: return
		val color = if (data.statuses.isEmpty()) JOINABLE_SLOT_COLOR else BLOCKED_SLOT_COLOR
		context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color)
	}

	fun afterDrawSlots(context: GuiGraphicsExtractor, screen: AbstractContainerScreen<*>) {
		if (!isEnabled() || !isMemberCountEnabled() || !isPartyFinderScreen(screen)) {
			return
		}
		val textRenderer = Minecraft.getInstance().font
		for (slot in screen.menu.slots) {
			if (slot.container == Minecraft.getInstance().player?.inventory) continue
			val data = parties[slot.index] ?: continue
			context.centeredText(textRenderer, data.members.size.toString(), slot.x + 14, slot.y + 8, 0xFFFFFF)
		}
	}

	fun onSlotClick(screen: AbstractContainerScreen<*>, slot: Slot?, button: Int, actionType: ContainerInput): Boolean {
		if (!isEnabled() || !isRightClickEnabled() || !isPartyFinderScreen(screen)) {
			return false
		}
		if (slot == null || button != 1 || actionType != ContainerInput.PICKUP || slot.container == Minecraft.getInstance().player?.inventory) {
			return false
		}
		if (slot.index !in PARTY_SLOT_RANGE || slot.item.item != Items.PLAYER_HEAD) {
			return false
		}

		val leader = LEADER_NAME_PATTERN.matchEntire(ChatFormatting.stripFormatting(slot.item.hoverName.string).orEmpty())?.groupValues?.getOrNull(1) ?: return false
		val client = Minecraft.getInstance()
		client.keyboardHandler.setClipboard(leader)
		client.player?.sendSystemMessage(Component.literal("§bCopied leader name §a$leader"))
		return true
	}

	private fun scanCurrentScreen(screen: AbstractContainerScreen<*>) {
		scanStacks(screen.menu.slots.map { it.item })
	}

	private fun scanStacks(stacks: List<ItemStack>) {
		val parsed = mutableMapOf<Int, PartyFinderData>()
		for (index in PARTY_SLOT_RANGE) {
			val stack = stacks.getOrNull(index) ?: continue
			val data = parseParty(index, stack) ?: continue
			parsed[index] = data
		}

		parties = parsed.toMap()
		requestMissingStats(parsed.values.flatMap { party -> party.members.map { it.name } })
	}

	private fun parseParty(index: Int, stack: ItemStack): PartyFinderData? {
		if (stack.item != Items.PLAYER_HEAD) {
			return null
		}
		val lore = loreLines(stack)
		var data: PartyFinderData? = null
		val rolesInParty = mutableSetOf<String>()
		for (line in lore) {
			if (data == null) {
				val type = TYPE_REGEX.matchEntire(line)?.groupValues ?: continue
				data = PartyFinderData(slot = index, isMasterMode = type.getOrNull(1) == "Master Mode ")
				continue
			}
			when {
				LOW_CATA_REGEX.matches(line) -> data.statuses.add(PartyFinderStatus.LOW_CATA)
				LOW_ROLE_REGEX.matches(line) -> data.statuses.add(PartyFinderStatus.LOW_ROLE)
				CANNOT_JOIN_REGEX.matches(line) -> data.statuses.add(PartyFinderStatus.CANNOT_JOIN)
				data.floor < 0 -> {
					val floor = FLOOR_REGEX.matchEntire(line)?.groupValues?.getOrNull(1) ?: continue
					data.floor = parseRoman(floor)
				}
				else -> {
					val match = USER_ROLE_REGEX.matchEntire(line)?.groupValues ?: continue
					val role = match[2]
					rolesInParty += role
					data.members += PartyFinderMember(match[1], role, match[3].toIntOrNull() ?: 0)
				}
			}
		}
		val result = data ?: return null
		if (result.members.any { it.role == currentRole }) {
			result.statuses.add(PartyFinderStatus.DUPE_CLASS)
		}
		result.missingRoles += ROLES.filter { it !in rolesInParty }
		return result
	}

	private fun applyOverviewLore(screen: AbstractContainerScreen<*>) {
		for ((slotIndex, party) in parties) {
			val slot = screen.menu.slots.getOrNull(slotIndex) ?: continue
			val stack = slot.item
			val lore = stack.get(DataComponents.LORE) ?: continue
			val newLore = mutableListOf<Component>()
			var changed = false
			for (line in lore.lines()) {
				val text = line.string
				val memberMatch = USER_ROLE_REGEX.matchEntire(text)?.groupValues
				when {
					text.contains("Missing: ") -> {
						changed = true
						continue
					}
					text.contains("Click to join!") || text.contains("Requires ") -> {
						if (party.missingRoles.isNotEmpty()) {
							newLore += missingRolesText(party)
							changed = true
						}
						newLore += line
					}
					memberMatch != null && !text.contains("[") && !text.contains("]") -> {
						val stats = statsCache[memberMatch[1].lowercase(Locale.ROOT)]?.takeIf { it.ok }?.stats
						if (stats == null) {
							newLore += line
						} else {
							newLore += line.copy().append(memberStatsSuffix(party, stats))
							changed = true
						}
					}
					else -> newLore += line
				}
			}
			if (changed && newLore.isNotEmpty()) {
				stack.set(DataComponents.LORE, ItemLore(newLore))
			}
		}
	}

	private fun requestMissingStats(names: List<String>) {
		val generation = ClientSessionLifecycle.snapshot()
		val missing = names
			.map { it.lowercase(Locale.ROOT) }
			.filter { it.isNotBlank() && !statsCache.containsKey(it) && pendingStats.add(it) }
			.distinct()
		if (missing.isEmpty()) return

		executor.execute {
			val response = XclipsenIrcBridgeClient.instance?.backendBridge()?.fetchDungeonStats(missing)
			if (ClientSessionLifecycle.isCurrent(generation) && response?.ok == true) {
				response.players.forEach { (name, stats) ->
					statsCache[name.lowercase(Locale.ROOT)] = stats
					if (stats.username.isNotBlank()) {
						statsCache[stats.username.lowercase(Locale.ROOT)] = stats
					}
				}
			}
			if (ClientSessionLifecycle.isCurrent(generation)) {
				pendingStats.removeAll(missing.toSet())
			}
		}
	}

	private fun memberStatsSuffix(party: PartyFinderData, stats: BackendDungeonStats): Component {
		val floors = if (party.isMasterMode) stats.floors.master else stats.floors.normal
		val floorStats = floors[party.floor.toString()]
		val pb = when {
			floorStats?.sPlusPbMs?.takeIf { it > 0L } != null -> formatTime(floorStats.sPlusPbMs)
			floorStats?.sPbMs?.takeIf { it > 0L } != null -> formatTime(floorStats.sPbMs)
			else -> null
		}
		return Component.literal(buildString {
			append(" §8(§6${formatLevel(stats.catacombsLevel)}§8)")
			append(" §8[§3${formatShort(stats.secrets)} §7| §b${formatFixed(stats.averageSecrets, 1)}§8]")
			if (pb == null) append(" §8[§cNO PB§8]") else append(" §8[§a$pb§8]")
		})
	}

	private fun missingRolesText(party: PartyFinderData): Component {
		return Component.literal(buildString {
			append("§eMissing: ")
			party.missingRoles.forEachIndexed { index, role ->
				if (index > 0) append("§7, ")
				append(if (role == currentRole) "§a$role" else "§7$role")
			}
		})
	}

	private fun parseCurrentRole(stack: ItemStack?) {
		stack ?: return
		for (line in loreLines(stack)) {
			val role = CURRENTLY_SELECTED_REGEX.matchEntire(line)?.groupValues?.getOrNull(1) ?: continue
			currentRole = role
			return
		}
	}

	private fun loreLines(stack: ItemStack): List<String> {
		return stack.get(DataComponents.LORE)?.lines()?.map { ChatFormatting.stripFormatting(it.string).orEmpty() } ?: emptyList()
	}

	private fun isPartyFinderScreen(screen: AbstractContainerScreen<*>): Boolean = screen.title.string == "Party Finder"

	private fun isEnabled(): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		return config.dungeonAutoKickModuleEnabled && (config.partyFinderGuiStatsEnabled || config.partyFinderHighlightsEnabled || config.partyFinderMemberCountEnabled || config.partyFinderRightClickEnabled)
	}

	private fun isGuiStatsEnabled(): Boolean = XclipsenIrcBridgeClient.instance?.config()?.partyFinderGuiStatsEnabled == true

	private fun isHighlightsEnabled(): Boolean = XclipsenIrcBridgeClient.instance?.config()?.partyFinderHighlightsEnabled == true

	private fun isMemberCountEnabled(): Boolean = XclipsenIrcBridgeClient.instance?.config()?.partyFinderMemberCountEnabled == true

	private fun isRightClickEnabled(): Boolean = XclipsenIrcBridgeClient.instance?.config()?.partyFinderRightClickEnabled == true

	private fun reset() {
		parties = emptyMap()
		inPartyFinder = false
		inCatacombsGate = false
		currentRole = null
		activeContainerId = -1
		activeRevision = -1
	}

	private fun parseRoman(value: String): Int {
		var result = 0
		var previous = 0
		for (char in value.reversed()) {
			val current = ROMAN_VALUES[char] ?: 0
			if (current < previous) result -= current else result += current
			previous = current
		}
		return result
	}

	private fun formatShort(value: Long): String {
		val abs = kotlin.math.abs(value)
		return when {
			abs >= 1_000_000L -> "%.1fM".format(Locale.US, value / 1_000_000.0)
			abs >= 1_000L -> "%.1fK".format(Locale.US, value / 1_000.0)
			else -> value.toString()
		}
	}

	private fun formatFixed(value: Double, decimals: Int): String = "%.${decimals}f".format(Locale.US, value)

	private fun formatLevel(value: Double): String = kotlin.math.floor(value.coerceAtLeast(0.0)).toInt().toString()

	private fun formatTime(ms: Long): String {
		val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
		return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
	}

	private data class PartyFinderMember(val name: String, val role: String, val level: Int)

	private data class PartyFinderData(
		val slot: Int,
		var floor: Int = -1,
		val isMasterMode: Boolean,
		val members: MutableList<PartyFinderMember> = mutableListOf(),
		val missingRoles: MutableList<String> = mutableListOf(),
		val statuses: EnumSet<PartyFinderStatus> = EnumSet.noneOf(PartyFinderStatus::class.java),
	)

	private enum class PartyFinderStatus {
		CANNOT_JOIN,
		DUPE_CLASS,
		LOW_CATA,
		LOW_ROLE,
	}

	private val PARTY_SLOT_RANGE = 0..45
	private val TYPE_REGEX = "^Dungeon: (Master Mode )?(The Catacombs)$".toRegex()
	private val FLOOR_REGEX = "^Floor: Floor ([IV]+)$".toRegex()
	private val USER_ROLE_REGEX = "^ (\\w{1,16}): (Healer|Tank|Mage|Berserk|Archer) \\((\\d+)\\)(?: .*)?$".toRegex()
	private val LOW_CATA_REGEX = "^Requires Catacombs Level \\d+!$".toRegex()
	private val LOW_ROLE_REGEX = "^Requires a Class at Level \\d+!$".toRegex()
	private val CANNOT_JOIN_REGEX = "^Complete previous floor first!$".toRegex()
	private val CURRENTLY_SELECTED_REGEX = "^Currently Selected: (Healer|Tank|Mage|Berserk|Archer)$".toRegex()
	private val LEADER_NAME_PATTERN = "^(\\w{1,16})'s Party$".toRegex()
	private val ROLES = listOf("Healer", "Tank", "Mage", "Berserk", "Archer")
	private val ROMAN_VALUES = mapOf('I' to 1, 'V' to 5, 'X' to 10)
	private const val JOINABLE_SLOT_COLOR = 0x6600FF00
	private const val BLOCKED_SLOT_COLOR = 0x66FF0000
}
