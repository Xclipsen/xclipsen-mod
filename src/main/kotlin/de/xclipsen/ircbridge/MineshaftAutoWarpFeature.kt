package de.xclipsen.ircbridge

import com.autocroesus.util.ColorUtil
import net.minecraft.client.MinecraftClient
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import java.util.EnumMap
import java.util.Locale
import kotlin.jvm.optionals.getOrNull

object MineshaftAutoWarpFeature {
	private var tickCounter = 0
	private var mineshaftActive = false
	private var enteredMineshaftAt = 0L
	private var caveInDetected = false
	private var warpIssued = false
	private var awaitingWarpResultUntil = 0L
	private var retryWarpAt = 0L
	private var lastPartyListRequestAt = 0L
	private var lastPtmeRequestAt = 0L
	private var lastCommandSentAt = 0L
	private var partyListRefreshPending = false
	private var recentPartySummonAt = 0L
	private var recentPartySummoner: String? = null
	private var foreignMineshaftOwner: String? = null
	private val detectedCorpses = linkedMapOf<BlockPos, DetectedCorpse>()

	private val partyMembers = linkedSetOf<String>()
	private var partyLeader: String? = null
	private var previousPartyLeader: String? = null

	fun onTick(client: MinecraftClient) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		val player = client.player
		if (player == null || client.world == null || !LocationTracker.isOnHypixelSkyBlock) {
			resetMineshaftState()
			return
		}

		if (!isInMineshaft()) {
			resetMineshaftState()
			return
		}

		if (!config.mineshaftAutoWarpModuleEnabled) {
			resetMineshaftState()
			return
		}

		if (!mineshaftActive) {
			mineshaftActive = true
			enteredMineshaftAt = System.currentTimeMillis()
			caveInDetected = false
			warpIssued = false
			awaitingWarpResultUntil = 0L
			retryWarpAt = 0L
			foreignMineshaftOwner = recentPartySummoner
				?.takeIf { enteredMineshaftAt - recentPartySummonAt in 0..FOREIGN_MINESHAFT_SUMMON_WINDOW_MS }
			detectedCorpses.clear()
			recentPartySummonAt = 0L
			recentPartySummoner = null
		}

		if (++tickCounter >= ENTITY_SCAN_INTERVAL_TICKS) {
			tickCounter = 0
			scanForCorpses(client)
		}

		attemptWarpFlow(client, config)
	}

	fun onDisconnect() {
		resetMineshaftState()
		resetPartyState()
	}

	fun onIncomingMessage(message: Text?) {
		val normalized = normalize(message?.string ?: return)
		if (normalized.isBlank()) {
			return
		}

		handlePartyMessage(normalized)
		handleMineshaftMessage(normalized)
	}

	fun statusLine(): String {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return "Unavailable"
		if (!config.mineshaftAutoWarpModuleEnabled) {
			return "Disabled"
		}
		if (!LocationTracker.isOnHypixelSkyBlock) {
			return "Not on Hypixel SkyBlock"
		}
		if (!isInMineshaft()) {
			return leaderStatePrefix() + "Waiting for mineshaft"
		}

		val validationError = validateCorpseRule(config.mineshaftAutoWarpCorpseRule)
		if (validationError != null) {
			return leaderStatePrefix() + validationError
		}

		val rule = parseCorpseRule(config.mineshaftAutoWarpCorpseRule)
		if (rule == null) {
			return leaderStatePrefix() + "Set a corpse rule"
		}

		val totalCounts = totalCounts()
		val openCounts = availableCounts()
		val countsText = "Shaft ${formatCounts(totalCounts)} | Open ${formatCounts(openCounts)}"
		val now = System.currentTimeMillis()
		val action = when {
			caveInDetected -> "Entrance caved in"
			foreignMineshaftOwner != null -> "Foreign shaft from $foreignMineshaftOwner"
			warpIssued -> "Warp sent"
			!rule.matches(totalCounts) -> "Found $countsText"
			partyMembers.isEmpty() -> "No party"
			isLocalPlayerLeader() -> {
				val remaining = (config.mineshaftAutoWarpDelayMs - (now - enteredMineshaftAt)).coerceAtLeast(0L)
				if (remaining > 0L) {
					"Waiting ${remaining}ms | $countsText"
				} else {
					"Ready to warp | $countsText"
				}
			}
			partyLeader == null || partyListRefreshPending -> "Checking party leader | $countsText"
			now - lastPtmeRequestAt < PTME_REPEAT_MS -> "Waiting for !ptme | $countsText"
			else -> "Need party lead from ${partyLeader ?: "unknown"} | $countsText"
		}
		return leaderStatePrefix() + action
	}

	fun validateCorpseRule(raw: String): String? {
		val normalized = raw.trim()
		if (normalized.isBlank()) {
			return null
		}
		return parseCorpseRuleInternal(normalized).error
	}

	private fun attemptWarpFlow(client: MinecraftClient, config: BridgeConfig) {
		if (caveInDetected || warpIssued || foreignMineshaftOwner != null) {
			return
		}

		val now = System.currentTimeMillis()
		if (now - enteredMineshaftAt > config.mineshaftAutoWarpWindowMs) {
			return
		}

		val rule = parseCorpseRule(config.mineshaftAutoWarpCorpseRule) ?: return
		if (!rule.matches(totalCounts())) {
			return
		}

		if (partyMembers.isEmpty()) {
			return
		}

		if (now - enteredMineshaftAt < config.mineshaftAutoWarpDelayMs) {
			return
		}

		if (now < retryWarpAt) {
			return
		}

		if (partyLeader == null) {
			if (now - lastPartyListRequestAt >= PARTY_LIST_REFRESH_MS) {
				if (sendCommand(client, "party list")) {
					lastPartyListRequestAt = now
					partyListRefreshPending = true
				}
			}
			return
		}

		if (!isLocalPlayerLeader()) {
			if (now - lastPtmeRequestAt >= PTME_REPEAT_MS && sendCommand(client, "pc !ptme")) {
				lastPtmeRequestAt = now
			}
			return
		}

		if (sendCommand(client, "party warp")) {
			warpIssued = true
			awaitingWarpResultUntil = now + WARP_FAILURE_WINDOW_MS
		}
	}

	private fun scanForCorpses(client: MinecraftClient) {
		val world = client.world ?: return
		for (entity in world.entities) {
			val armorStand = entity as? ArmorStandEntity ?: continue
			if (!looksLikeCorpseStand(armorStand)) {
				continue
			}

			val corpseKind = resolveCorpseKind(armorStand.getEquippedStack(EquipmentSlot.HEAD)) ?: continue
			val pos = armorStand.blockPos
			val existing = detectedCorpses[pos]
			if (existing == null) {
				detectedCorpses[pos] = DetectedCorpse(corpseKind, pos, looted = false)
			} else if (existing.kind != corpseKind) {
				detectedCorpses[pos] = existing.copy(kind = corpseKind)
			}
		}
	}

	private fun looksLikeCorpseStand(armorStand: ArmorStandEntity): Boolean {
		if (!armorStand.isAlive || armorStand.isRemoved || armorStand.isInvisible) {
			return false
		}
		if (armorStand.shouldShowBasePlate()) {
			return false
		}
		if (!armorStand.shouldShowArms()) {
			return false
		}
		val customName = armorStand.customName?.string?.trim().orEmpty()
		return customName.isEmpty()
	}

	private fun resolveCorpseKind(stack: ItemStack): CorpseKind? {
		if (stack.isEmpty) {
			return null
		}
		val customData = stack.get(DataComponentTypes.CUSTOM_DATA) as? NbtComponent ?: return null
		val id = customData.copyNbt().getString("id").getOrNull()?.trim().orEmpty()
		if (id.isEmpty()) {
			return null
		}
		return CorpseKind.byInternalId(id)
	}

	private fun handleMineshaftMessage(message: String) {
		if (message.equals(CAVE_IN_MESSAGE, ignoreCase = true)) {
			caveInDetected = true
			return
		}

		LOOT_PATTERN.matchEntire(message)?.let { match ->
			val corpseKind = CorpseKind.byName(match.groupValues[1]) ?: return
			markClosestCorpseLooted(corpseKind)
			return
		}

		if (System.currentTimeMillis() <= awaitingWarpResultUntil && isWarpFailureMessage(message)) {
			warpIssued = false
			awaitingWarpResultUntil = 0L
			retryWarpAt = System.currentTimeMillis() + WARP_RETRY_DELAY_MS
		}
	}

	private fun markClosestCorpseLooted(kind: CorpseKind) {
		val playerPos = MinecraftClient.getInstance().player?.blockPos ?: return
		detectedCorpses.values
			.asSequence()
			.filter { it.kind == kind && !it.looted }
			.minByOrNull { it.pos.getSquaredDistance(playerPos) }
			?.looted = true
	}

	private fun handlePartyMessage(message: String) {
		YOU_JOINED_PARTY_PATTERN.matchEntire(message)?.let { match ->
			resetPartyState()
			val leader = cleanPlayerName(match.groupValues[1])
			partyLeader = leader
			addPartyMember(leader)
			return
		}

		OTHER_JOINED_PARTY_PATTERN.matchEntire(message)?.let { match ->
			if (partyLeader == null) {
				partyLeader = localPlayerName()
			}
			addPartyMember(cleanPlayerName(match.groupValues[1]))
			return
		}

		OTHERS_IN_PARTY_PATTERN.matchEntire(message)?.let { match ->
			match.groupValues[1].split(", ").forEach { addPartyMember(cleanPlayerName(it)) }
			return
		}

		TRANSFER_ON_LEAVE_PATTERN.matchEntire(message)?.let { match ->
			partyLeader = cleanPlayerName(match.groupValues[1])
			removePartyMember(cleanPlayerName(match.groupValues[2]))
			partyListRefreshPending = false
			return
		}

		TRANSFER_BY_PATTERN.matchEntire(message)?.let { match ->
			partyLeader = cleanPlayerName(match.groupValues[1])
			previousPartyLeader = cleanPlayerName(match.groupValues[2])
			addPartyMember(partyLeader ?: "")
			partyListRefreshPending = false
			return
		}

		SUMMON_PATTERN.matchEntire(message)?.let { match ->
			val summoner = cleanPlayerName(match.groupValues[1])
			partyLeader = summoner
			addPartyMember(partyLeader ?: "")
			recentPartySummoner = summoner
			recentPartySummonAt = System.currentTimeMillis()
			return
		}

		PARTY_MEMBERS_START_PATTERN.matchEntire(message)?.let {
			partyMembers.clear()
			partyListRefreshPending = true
			return
		}

		PARTY_LIST_LINE_PATTERN.matchEntire(message)?.let { match ->
			val kind = match.groupValues[1]
			val names = match.groupValues[2]
			val parsedNames = names.split(" ● ").map(::cleanPlayerName).filter(USERNAME_PATTERN::matches)
			parsedNames.forEach(::addPartyMember)
			if (kind.equals("Leader", ignoreCase = true) && parsedNames.isNotEmpty()) {
				partyLeader = parsedNames.first()
			}
			partyListRefreshPending = false
			return
		}

		OTHER_LEFT_PARTY_PATTERN.matchEntire(message)?.let { match ->
			removePartyMember(cleanPlayerName(match.groupValues[1]))
			return
		}

		OTHER_REMOVED_PATTERN.matchEntire(message)?.let { match ->
			removePartyMember(cleanPlayerName(match.groupValues[1]))
			return
		}

		OTHER_DISCONNECTED_PATTERN.matchEntire(message)?.let { match ->
			removePartyMember(cleanPlayerName(match.groupValues[1]))
			return
		}

		if (PARTY_LEFT_MESSAGES.contains(message)) {
			resetPartyState()
		}
	}

	private fun addPartyMember(name: String) {
		if (!USERNAME_PATTERN.matches(name)) {
			return
		}
		if (name.equals(localPlayerName(), ignoreCase = true)) {
			return
		}
		partyMembers += name
	}

	private fun removePartyMember(name: String) {
		if (name.isBlank()) {
			return
		}
		partyMembers.remove(name)
		if (partyLeader.equals(name, ignoreCase = true)) {
			partyLeader = null
		}
		if (previousPartyLeader.equals(name, ignoreCase = true)) {
			previousPartyLeader = null
		}
	}

	private fun resetPartyState() {
		partyMembers.clear()
		partyLeader = null
		previousPartyLeader = null
		partyListRefreshPending = false
		lastPartyListRequestAt = 0L
		lastPtmeRequestAt = 0L
	}

	private fun resetMineshaftState() {
		tickCounter = 0
		mineshaftActive = false
		enteredMineshaftAt = 0L
		caveInDetected = false
		warpIssued = false
		awaitingWarpResultUntil = 0L
		retryWarpAt = 0L
		foreignMineshaftOwner = null
		detectedCorpses.clear()
	}

	private fun isInMineshaft(): Boolean {
		return LocationTracker.isOnHypixelSkyBlock && LocationTracker.currentArea.contains("mineshaft", ignoreCase = true)
	}

	private fun isLocalPlayerLeader(): Boolean {
		val leader = partyLeader ?: return false
		return leader.equals(localPlayerName(), ignoreCase = true)
	}

	private fun leaderStatePrefix(): String {
		val leader = partyLeader ?: "unknown"
		return "Leader: $leader | "
	}

	private fun totalCounts(): EnumMap<CorpseKind, Int> {
		val counts = EnumMap<CorpseKind, Int>(CorpseKind::class.java)
		for (corpse in detectedCorpses.values) {
			counts[corpse.kind] = (counts[corpse.kind] ?: 0) + 1
		}
		return counts
	}

	private fun availableCounts(): EnumMap<CorpseKind, Int> {
		val counts = EnumMap<CorpseKind, Int>(CorpseKind::class.java)
		for (corpse in detectedCorpses.values) {
			if (corpse.looted) {
				continue
			}
			counts[corpse.kind] = (counts[corpse.kind] ?: 0) + 1
		}
		return counts
	}

	private fun formatCounts(counts: Map<CorpseKind, Int>): String {
		val summary = CorpseKind.entries
			.mapNotNull { kind ->
				val amount = counts[kind] ?: 0
				if (amount <= 0) null else "${amount}${kind.shortCode}"
			}
			.joinToString(", ")
		return if (summary.isBlank()) "none" else summary
	}

	private fun isWarpFailureMessage(message: String): Boolean {
		if (message.contains("too fast", ignoreCase = true)) {
			return true
		}
		if (message.contains("party leader", ignoreCase = true)) {
			return true
		}
		if (message.contains("not in a party", ignoreCase = true)) {
			return true
		}
		return false
	}

	private fun sendCommand(client: MinecraftClient, command: String): Boolean {
		val networkHandler = client.player?.networkHandler ?: return false
		val now = System.currentTimeMillis()
		if (now - lastCommandSentAt < COMMAND_GAP_MS) {
			return false
		}
		networkHandler.sendChatCommand(command)
		lastCommandSentAt = now
		return true
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
			if (closing <= 0) {
				break
			}
			current = current.substring(closing + 1).trimStart()
		}
		return USERNAME_PATTERN.findAll(current).lastOrNull()?.value ?: current.trim()
	}

	private fun localPlayerName(): String {
		return MinecraftClient.getInstance().session?.username.orEmpty()
	}

	private fun parseCorpseRule(raw: String): CorpseRule? {
		val normalized = raw.trim()
		if (normalized.isBlank()) {
			return null
		}
		return parseCorpseRuleInternal(normalized).rule
	}

	private fun parseCorpseRuleInternal(raw: String): RuleParseResult {
		val alternatives = raw.split(OR_RULE_SPLIT_PATTERN).map { it.trim() }.filter { it.isNotEmpty() }
		if (alternatives.isEmpty()) {
			return RuleParseResult(null, null)
		}

		val parsedAlternatives = mutableListOf<CorpseClause>()
		for (alternative in alternatives) {
			val clause = parseClause(alternative) ?: return RuleParseResult(
				null,
				"Invalid rule clause: $alternative",
			)
			parsedAlternatives += clause
		}

		if (parsedAlternatives.isEmpty()) {
			return RuleParseResult(null, "Set at least one corpse requirement")
		}

		return RuleParseResult(CorpseRule(parsedAlternatives), null)
	}

	private fun parseClause(raw: String): CorpseClause? {
		val requirements = EnumMap<CorpseKind, Int>(CorpseKind::class.java)
		var totalRequired: Int? = null
		val segments = raw.split(AND_RULE_SPLIT_PATTERN).map { it.trim() }.filter { it.isNotEmpty() }
		if (segments.isEmpty()) {
			return null
		}

		for (segment in segments) {
			val requirement = parseRequirement(segment) ?: return null
			if (requirement.totalRequired != null) {
				totalRequired = requirement.totalRequired
			}
			requirement.kind?.let { kind ->
				requirements[kind] = (requirements[kind] ?: 0) + requirement.amount
			}
		}

		if (requirements.isEmpty() && totalRequired == null) {
			return null
		}

		return CorpseClause(requirements, totalRequired)
	}

	private fun parseRequirement(raw: String): ParsedRequirement? {
		val namedMatch = NAMED_RULE_PATTERN.matchEntire(raw)
		if (namedMatch != null) {
			val key = namedMatch.groupValues[1].uppercase(Locale.ROOT)
			val amount = namedMatch.groupValues[2].toIntOrNull() ?: return null
			if (key == "TOTAL" || key == "CORPSE" || key == "CORPSES") {
				return ParsedRequirement(null, amount, amount)
			}
			val kind = CorpseKind.byAlias(key) ?: return null
			return ParsedRequirement(kind, amount, null)
		}

		val leadingAmountMatch = LEADING_AMOUNT_RULE_PATTERN.matchEntire(raw)
		if (leadingAmountMatch != null) {
			val amount = leadingAmountMatch.groupValues[1].toIntOrNull() ?: return null
			val key = leadingAmountMatch.groupValues[2].uppercase(Locale.ROOT)
			val kind = CorpseKind.byAlias(key) ?: return null
			return ParsedRequirement(kind, amount, null)
		}

		val trailingAmountMatch = TRAILING_AMOUNT_RULE_PATTERN.matchEntire(raw)
		if (trailingAmountMatch != null) {
			val key = trailingAmountMatch.groupValues[1].uppercase(Locale.ROOT)
			val amount = trailingAmountMatch.groupValues[2].toIntOrNull() ?: return null
			val kind = CorpseKind.byAlias(key) ?: return null
			return ParsedRequirement(kind, amount, null)
		}

		return null
	}

	private data class RuleParseResult(
		val rule: CorpseRule?,
		val error: String?,
	)

	private data class ParsedRequirement(
		val kind: CorpseKind?,
		val amount: Int,
		val totalRequired: Int?,
	)

	private data class DetectedCorpse(
		val kind: CorpseKind,
		val pos: BlockPos,
		var looted: Boolean,
	)

	private data class CorpseRule(val alternatives: List<CorpseClause>) {
		fun matches(counts: Map<CorpseKind, Int>): Boolean = alternatives.any { it.matches(counts) }
	}

	private data class CorpseClause(
		val requirements: EnumMap<CorpseKind, Int>,
		val totalRequired: Int?,
	) {
		fun matches(counts: Map<CorpseKind, Int>): Boolean {
			if (totalRequired != null && counts.values.sum() < totalRequired) {
				return false
			}
			return requirements.all { (kind, amount) -> (counts[kind] ?: 0) >= amount }
		}
	}

	private enum class CorpseKind(
		val shortCode: String,
		val aliases: Set<String>,
		val internalId: String,
	) {
		LAPIS("L", setOf("L", "LAPIS"), "LAPIS_ARMOR_HELMET"),
		UMBER("U", setOf("U", "UMBER"), "ARMOR_OF_YOG_HELMET"),
		TUNGSTEN("T", setOf("T", "TUNGSTEN"), "MINERAL_HELMET"),
		VANGUARD("V", setOf("V", "VANGUARD"), "VANGUARD_HELMET"),
		;

		companion object {
			fun byInternalId(id: String): CorpseKind? = entries.firstOrNull { it.internalId == id }

			fun byAlias(alias: String): CorpseKind? {
				val normalized = alias.uppercase(Locale.ROOT)
				return entries.firstOrNull { normalized in it.aliases }
			}

			fun byName(name: String): CorpseKind? = byAlias(name)
		}
	}

	private val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")
	private val WHITESPACE_PATTERN = Regex("\\s+")
	private val OR_RULE_SPLIT_PATTERN = Regex("(?:\\r?\\n)+|\\|+|;+")
	private val AND_RULE_SPLIT_PATTERN = Regex(",+")
	private val NAMED_RULE_PATTERN = Regex("(?i)^(lapis|umber|tungsten|vanguard|l|u|t|v|total|corpses?|corpse)\\s*[:= ]\\s*(\\d+)$")
	private val LEADING_AMOUNT_RULE_PATTERN = Regex("(?i)^(\\d+)\\s*(lapis|umber|tungsten|vanguard|l|u|t|v)$")
	private val TRAILING_AMOUNT_RULE_PATTERN = Regex("(?i)^(lapis|umber|tungsten|vanguard|l|u|t|v)(\\d+)$")
	private val LOOT_PATTERN = Regex("^(LAPIS|UMBER|TUNGSTEN|VANGUARD) CORPSE LOOT!$")
	private val YOU_JOINED_PARTY_PATTERN = Regex("^You have joined (.+?)'?s party!$")
	private val OTHER_JOINED_PARTY_PATTERN = Regex("^(.+?) joined the party\\.$")
	private val OTHERS_IN_PARTY_PATTERN = Regex("^You'll be partying with: (.+)$")
	private val OTHER_LEFT_PARTY_PATTERN = Regex("^(.+?) has left the party\\.$")
	private val OTHER_REMOVED_PATTERN = Regex("^(.+?) has been removed from the party\\.$")
	private val OTHER_DISCONNECTED_PATTERN = Regex("^(.+?) was removed from your party because they disconnected\\.$")
	private val TRANSFER_ON_LEAVE_PATTERN = Regex("^The party was transferred to (.+?) because (.+?) left$")
	private val TRANSFER_BY_PATTERN = Regex("^The party was transferred to (.+?) by (.+)$")
	private val PARTY_MEMBERS_START_PATTERN = Regex("^Party Members \\(\\d+\\)$")
	private val PARTY_LIST_LINE_PATTERN = Regex("^Party (Leader|Moderators|Members): (.+)$")
	private val SUMMON_PATTERN = Regex("^Party Leader, (.+?), summoned you to their server\\.$")
	private val PARTY_LEFT_MESSAGES = setOf(
		"You left the party.",
		"The party was disbanded because all invites expired and the party was empty.",
		"You are not currently in a party.",
		"You are not in a party.",
		"The party was disbanded because the party leader disconnected.",
	)
	private const val CAVE_IN_MESSAGE = "The mineshaft entrance has caved in... it doesn't look like anyone else will be able to get in here."
	private const val ENTITY_SCAN_INTERVAL_TICKS = 5
	private const val PARTY_LIST_REFRESH_MS = 5_000L
	private const val PTME_REPEAT_MS = 8_000L
	private const val COMMAND_GAP_MS = 450L
	private const val WARP_FAILURE_WINDOW_MS = 2_500L
	private const val WARP_RETRY_DELAY_MS = 3_000L
	private const val FOREIGN_MINESHAFT_SUMMON_WINDOW_MS = 15_000L
}
