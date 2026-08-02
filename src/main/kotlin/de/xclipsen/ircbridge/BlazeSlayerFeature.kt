package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.Font.DisplayMode
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.entity.monster.Guardian
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.roundToLong

object BlazeSlayerFeature {
	private var featureTickCounter = 0
	private var tickCounter = 0
	private var trackedBosses: Map<UUID, BlazeBossInfo> = emptyMap()
	private var trackedShields: Map<UUID, HellionShieldInfo> = emptyMap()
	private var ownBlazeBossUuid: UUID? = null
	private var ownBlazeBossLastPosition: Vec3? = null
	private var ownBlazeBossLastSeenTick = 0

	fun statusLine(): String {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return "Unavailable"
		return "enabled=${config.slayerModuleEnabled && (config.slayerBlazePhaseDisplayEnabled || config.slayerBlazeColoredMobsEnabled)}, " +
			"skyblock=${LocationTracker.isOnHypixelSkyBlock}, bosses=${trackedBosses.size}, shields=${trackedShields.size}, ownBoss=${ownBlazeBossUuid != null}"
	}

	fun onTick(client: Minecraft) {
		val config = XclipsenIrcBridgeClient.instance?.config()
		val world = client.level
		val player = client.player
		if (config?.slayerModuleEnabled != true ||
			(!config.slayerBlazePhaseDisplayEnabled && !config.slayerBlazeColoredMobsEnabled) ||
			world == null ||
			player == null ||
			!LocationTracker.isOnHypixelSkyBlock
		) {
			clear()
			return
		}

		featureTickCounter++

		if (++tickCounter >= SCAN_INTERVAL_TICKS) {
			tickCounter = 0

			val armorStands = world.entitiesForRendering().filterIsInstance<ArmorStand>()
			val candidates = mutableListOf<BlazeBossInfo>()

			for (entity in world.entitiesForRendering().filterIsInstance<LivingEntity>()) {
				if (!isRelevantLivingEntity(entity)) {
					continue
				}

				val boss = resolveBossInfo(entity, armorStands) ?: continue
				candidates += boss
			}

			if (candidates.isEmpty()) {
				expireOwnBlazeBossMemory()
				trackedBosses = emptyMap()
				trackedShields = emptyMap()
			} else {
				val ownedCandidates = filterOwnBlazeCandidates(candidates, player)
				if (ownedCandidates.isEmpty()) {
					trackedBosses = emptyMap()
					trackedShields = emptyMap()
				} else {
					val visibleCandidates = applyDemonHealthVisibility(ownedCandidates)
					val shields = if (config.slayerBlazeColoredMobsEnabled) {
						resolveHellionShieldsForBosses(visibleCandidates)
					} else {
						emptyMap()
					}
					trackedBosses = visibleCandidates.associateBy { it.entity.uuid }
					trackedShields = shields
				}
			}
		}
	}

	fun render(context: LevelRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.slayerModuleEnabled || !config.slayerBlazePhaseDisplayEnabled || trackedBosses.isEmpty()) {
			return
		}

		val client = Minecraft.getInstance()
		if (client.options.hideGui) {
			return
		}
		if (client.player == null) {
			return
		}
		val textRenderer = client.font
		val camera = context.gameRenderer().mainCamera
		val cameraPos = camera.position()
		val consumers = context.bufferSource()

		for (info in trackedBosses.values) {
			val entity = info.entity
			if (!entity.isAlive || entity.isRemoved) {
				continue
			}
			val baseLocation = Vec3(entity.x - 0.5, entity.y, entity.z - 0.5)
			val lineDefinitions = mutableListOf<Tuple<List<TextSegment>, Double>>()
			info.hellionShield?.let { shield ->
				lineDefinitions += listOf(TextSegment(shield.labelWithNumber(), shield.color)) to SHIELD_TEXT_SCALE
			}
			if (info.showPhase) {
				lineDefinitions += listOf(TextSegment("${info.phase}/${info.phaseCount}", info.phaseRgb)) to PHASE_TEXT_SCALE
			}
			if (info.showHealth) {
				lineDefinitions += listOf(TextSegment(formatHealth(info.phaseHealth), info.healthRgb)) to HEALTH_TEXT_SCALE
			}

			for ((index, line) in lineDefinitions.withIndex()) {
				drawDynamicText(
					textRenderer,
					consumers,
					cameraPos,
					camera.rotation(),
					baseLocation,
					line.first,
					line.second,
					-9f * (lineDefinitions.lastIndex - index),
				)
			}
			if (info.twilightActive) {
				drawDynamicText(
					textRenderer,
					consumers,
					cameraPos,
					camera.rotation(),
					baseLocation,
					listOf(TextSegment("Twilight", TWILIGHT_TEXT_COLOR)),
					TWILIGHT_TEXT_SCALE,
					TWILIGHT_TEXT_OFFSET,
				)
			}
		}

		consumers.endBatch()
	}

	fun shouldGlow(entity: Entity): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		return config.slayerModuleEnabled &&
			config.slayerBlazeColoredMobsEnabled &&
			entity is LivingEntity &&
			!entity.isInvisible &&
			entity !is Guardian &&
			trackedShields.containsKey(entity.uuid)
	}

	fun colorValue(entity: Entity): Int? = trackedShields[entity.uuid]?.color

	private fun filterOwnBlazeCandidates(candidates: List<BlazeBossInfo>, player: Player): List<BlazeBossInfo> {
		val playerName = Minecraft.getInstance().user.name.takeIf { it.isNotBlank() }
			?: cleanName(player.name.string)
			?: return emptyList()
		val ownDemonlord = candidates
			.asSequence()
			.filter { info -> info.kind == InfernoKind.DEMONLORD && isSpawnedByPlayer(info.spawnedByPlayer, playerName) }
			.minByOrNull { info -> info.entity.distanceToSqr(player) }

		if (ownDemonlord != null) {
			ownBlazeBossUuid = ownDemonlord.entity.uuid
			ownBlazeBossLastPosition = entityPosition(ownDemonlord.entity)
			ownBlazeBossLastSeenTick = featureTickCounter
		} else {
			expireOwnBlazeBossMemory()
		}

		val ownBossPositions = candidates
			.asSequence()
			.filter { info ->
				info.kind == InfernoKind.DEMONLORD &&
					(isSpawnedByPlayer(info.spawnedByPlayer, playerName) ||
						(info.entity.uuid == ownBlazeBossUuid && isOwnBlazeBossMemoryFresh()))
			}
			.map { info -> entityPosition(info.entity) }
			.toMutableList()
		if (ownBossPositions.isEmpty() && isOwnBlazeBossMemoryFresh()) {
			ownBlazeBossLastPosition?.let { ownBossPositions += it }
		}

		return candidates.filter { info ->
			when {
				isSpawnedByPlayer(info.spawnedByPlayer, playerName) -> true
				info.kind == InfernoKind.DEMONLORD -> info.entity.uuid == ownBlazeBossUuid && isOwnBlazeBossMemoryFresh()
				else -> ownBossPositions.any { position ->
					squaredDistanceTo(info.entity, position) <= DEMON_BOSS_DISTANCE_SQUARED
				}
			}
		}
	}

	private fun isSpawnedByPlayer(spawnedBy: String?, playerName: String): Boolean {
		return spawnedBy != null && spawnedBy.equals(playerName, ignoreCase = true)
	}

	private fun resolveSpawnedByPlayer(names: List<String>): String? {
		for (name in names) {
			val match = SPAWNED_BY_PATTERN.find(name) ?: continue
			return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
		}
		return null
	}

	private fun isOwnBlazeBossMemoryFresh(): Boolean {
		return ownBlazeBossLastSeenTick > 0 &&
			featureTickCounter - ownBlazeBossLastSeenTick <= OWN_BLAZE_BOSS_MEMORY_TICKS
	}

	private fun expireOwnBlazeBossMemory() {
		if (ownBlazeBossLastSeenTick == 0 || isOwnBlazeBossMemoryFresh()) {
			return
		}
		clearOwnBlazeBossMemory()
	}

	private fun clearOwnBlazeBossMemory() {
		ownBlazeBossUuid = null
		ownBlazeBossLastPosition = null
		ownBlazeBossLastSeenTick = 0
	}

	private fun entityPosition(entity: LivingEntity): Vec3 = Vec3(entity.x, entity.y, entity.z)

	private fun squaredDistanceTo(entity: LivingEntity, position: Vec3): Double {
		val dx = entity.x - position.x
		val dy = entity.y - position.y
		val dz = entity.z - position.z
		return dx * dx + dy * dy + dz * dz
	}

	private fun applyDemonHealthVisibility(candidates: List<BlazeBossInfo>): List<BlazeBossInfo> {
		val demons = candidates.filter { info ->
			(info.kind == InfernoKind.QUAZII || info.kind == InfernoKind.TYPHOEUS) &&
				info.phaseHealth > 0L &&
				info.entity.isAlive &&
				!info.entity.isRemoved
		}

		return candidates.map { info ->
			if (info.kind != InfernoKind.QUAZII && info.kind != InfernoKind.TYPHOEUS) {
				return@map info
			}

			val firstActiveKind = resolveFirstActiveDemonKind(info, candidates)
			val firstActiveDemonAlive = demons.any { demon ->
				demon.kind == firstActiveKind &&
					demon.entity.distanceToSqr(info.entity) <= DEMON_PAIR_DISTANCE_SQUARED
			}
			info.copy(showHealth = info.kind == firstActiveKind || !firstActiveDemonAlive)
		}
	}

	private fun resolveFirstActiveDemonKind(info: BlazeBossInfo, candidates: List<BlazeBossInfo>): InfernoKind {
		val nearestDemonlord = candidates
			.asSequence()
			.filter { candidate -> candidate.kind == InfernoKind.DEMONLORD }
			.minByOrNull { candidate -> candidate.entity.distanceToSqr(info.entity) }
		val isSecondDemonPhase = nearestDemonlord != null &&
			nearestDemonlord.entity.distanceToSqr(info.entity) <= DEMON_BOSS_DISTANCE_SQUARED &&
			nearestDemonlord.phase >= SECOND_DEMON_PHASE
		return if (isSecondDemonPhase) InfernoKind.TYPHOEUS else InfernoKind.QUAZII
	}

	private fun resolveHellionShieldsForBosses(bosses: List<BlazeBossInfo>): Map<UUID, HellionShieldInfo> {
		val result = mutableMapOf<UUID, HellionShieldInfo>()
		for (boss in bosses) {
			boss.hellionShield?.let { result[boss.entity.uuid] = it }
		}
		return result
	}

	private fun resolveBossInfo(entity: LivingEntity, armorStands: List<ArmorStand>): BlazeBossInfo? {
		if (!entity.isAlive || entity.isRemoved) {
			return null
		}

		val names = resolveDisplayNames(entity, armorStands)
		val kind = resolveInfernoKind(entity, names) ?: return null
		val hellionShield = resolveBossHellionShield(entity, armorStands, kind)
		val spawnedByPlayer = resolveSpawnedByPlayer(names)
		val twilightActive = names.any { it.contains(TWILIGHT_ARROW_POISON_MARKER) }

		val rawHealth = resolveVisibleHealth(names)?.takeIf { it > 0L } ?: entity.health.roundToLong().coerceAtLeast(1L)
		if (kind != InfernoKind.DEMONLORD) {
			val tier = resolveDemonTier(entity.maxHealth.toDouble(), rawHealth) ?: return null
			val rawMaxHealth = max(knownDemonMaxHealth(tier), entity.maxHealth.roundToLong().coerceAtLeast(rawHealth))
			val totalHealth = rawHealth.coerceIn(0L, rawMaxHealth)
			return BlazeBossInfo(
				entity = entity,
				tier = tier,
				displayName = kind.displayName,
				kind = kind,
				spawnedByPlayer = spawnedByPlayer,
				showPhase = false,
				showHealth = true,
				phase = 0,
				phaseCount = 0,
				phaseHealth = totalHealth,
				phaseMaxHealth = rawMaxHealth,
				phaseRgb = 0xFFFFFFFF.toInt(),
				healthRgb = percentageColor(totalHealth, rawMaxHealth),
				hellionShield = hellionShield,
				twilightActive = twilightActive,
			)
		}

		val tier = resolveTier(names, entity.maxHealth.toDouble(), rawHealth) ?: return null
		val phaseCount = if (tier <= 2) 2 else 3
		val rawMaxHealth = max(
			knownMaxHealth(tier),
			entity.maxHealth.roundToLong().coerceAtLeast(rawHealth),
		)
		val totalHealth = rawHealth.coerceIn(0L, rawMaxHealth)
		val step = max(1L, rawMaxHealth / phaseCount)
		val phase: Int
		val phaseHealth: Long

		if (phaseCount == 2) {
			if (totalHealth > step) {
				phase = 1
				phaseHealth = totalHealth - step
			} else {
				phase = 2
				phaseHealth = totalHealth
			}
		} else {
			if (totalHealth > step * 2) {
				phase = 1
				phaseHealth = totalHealth - step * 2
			} else if (totalHealth > step) {
				phase = 2
				phaseHealth = totalHealth - step
			} else {
				phase = 3
				phaseHealth = totalHealth
			}
		}

		val healthRgb = percentageColor(phaseHealth, step)
		val phaseRgb = when (phase) {
			1 -> 0xFFFF5555.toInt()
			2 -> if (phaseCount == 2) 0xFF55FF55.toInt() else 0xFFFFE066.toInt()
			else -> 0xFF55FF55.toInt()
		}

		return BlazeBossInfo(
			entity = entity,
			tier = tier,
			displayName = "Inferno Demonlord ${romanTier(tier)}",
			kind = kind,
			spawnedByPlayer = spawnedByPlayer,
			showPhase = true,
			showHealth = true,
			phase = phase,
			phaseCount = phaseCount,
			phaseHealth = phaseHealth.coerceAtLeast(0L),
			phaseMaxHealth = step,
			phaseRgb = phaseRgb,
			healthRgb = healthRgb,
			hellionShield = hellionShield,
			twilightActive = twilightActive,
		)
	}

	private fun resolveInfernoKind(entity: LivingEntity, names: List<String>): InfernoKind? {
		return when {
			entity is Blaze && names.any { it.contains("Inferno Demonlord", ignoreCase = true) } -> InfernoKind.DEMONLORD
			entity is WitherSkeleton && names.any { it.contains(QUAZII_MARKER) || it.contains("Quazii", ignoreCase = true) } -> InfernoKind.QUAZII
			entity is ZombifiedPiglin && names.any { it.contains(TYPHOEUS_MARKER) || it.contains("Typhoeus", ignoreCase = true) } -> InfernoKind.TYPHOEUS
			else -> null
		}
	}

	private fun resolveBossHellionShield(
		entity: LivingEntity,
		armorStands: List<ArmorStand>,
		kind: InfernoKind,
	): HellionShieldInfo? {
		val ownNames = entityCleanNames(entity)
		resolveHellionShieldFromNames(ownNames)?.let { return it }

		val nearbyStands = armorStands
			.asSequence()
			.filter { stand -> stand.isAlive && !stand.isRemoved }
			.filter { stand -> isNearbyNameStand(entity, stand) }
			.toList()
		val identityStands = nearbyStands.filter { stand ->
			armorStandCleanNames(stand).any { name -> isInfernoIdentityLine(kind, name) }
		}
		val shieldNames = nearbyStands
			.asSequence()
			.filter { stand -> armorStandCleanNames(stand).any(::containsHellionShield) }
			.filter { stand -> isOwnBossShieldStand(entity, stand, identityStands) }
			.sortedWith(compareBy<ArmorStand> { stand ->
				if (identityStands.isEmpty()) kotlin.math.abs(stand.x - entity.x) + kotlin.math.abs(stand.z - entity.z)
				else identityStands.minOf { identity -> stand.distanceToSqr(identity) }
			}.thenBy { stand -> -stand.y })
			.flatMap { stand -> armorStandCleanNames(stand).asSequence() }
			.toList()

		return resolveHellionShieldFromNames(shieldNames)
	}

	private fun isInfernoIdentityLine(kind: InfernoKind, name: String): Boolean {
		return when (kind) {
			InfernoKind.DEMONLORD -> name.contains("Inferno Demonlord", ignoreCase = true)
			InfernoKind.QUAZII -> name.contains(QUAZII_MARKER) || name.contains("Quazii", ignoreCase = true)
			InfernoKind.TYPHOEUS -> name.contains(TYPHOEUS_MARKER) || name.contains("Typhoeus", ignoreCase = true)
		}
	}

	private fun containsHellionShield(name: String): Boolean {
		return HellionShield.entries.any { shield -> name.contains(shield.displayName, ignoreCase = true) }
	}

	private fun isOwnBossShieldStand(
		entity: LivingEntity,
		stand: ArmorStand,
		identityStands: List<ArmorStand>,
	): Boolean {
		if (identityStands.isNotEmpty()) {
			return identityStands.any { identity ->
				kotlin.math.abs(stand.x - identity.x) <= BOSS_NAME_COLUMN_XZ &&
					kotlin.math.abs(stand.z - identity.z) <= BOSS_NAME_COLUMN_XZ &&
					kotlin.math.abs(stand.y - identity.y) <= BOSS_NAME_COLUMN_Y
			}
		}

		return kotlin.math.abs(stand.x - entity.x) <= BOSS_NAME_COLUMN_XZ &&
			kotlin.math.abs(stand.z - entity.z) <= BOSS_NAME_COLUMN_XZ &&
			stand.y >= entity.y + BOSS_SHIELD_MIN_Y_OFFSET &&
			stand.y <= entity.y + MAX_NAME_OFFSET_ABOVE
	}

	private fun drawDynamicText(
		textRenderer: Font,
		consumers: MultiBufferSource,
		cameraPos: Vec3,
		cameraRotation: org.joml.Quaternionf,
		location: Vec3,
		segments: List<TextSegment>,
		scaleMultiplier: Double,
		yOffset: Float,
	) {
		val x = location.x
		val y = location.y
		val z = location.z
		val deltaX = x - cameraPos.x
		val deltaY = y - cameraPos.y
		val deltaZ = z - cameraPos.z
		var distance = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).coerceAtLeast(SMALLEST_VIEW_DISTANCE)
		if (distance < HIDE_TOO_CLOSE_AT) {
			return
		}

		val renderDistance = distance.coerceAtMost(MAX_DYNAMIC_DISTANCE)
		val scale = renderDistance / 12.0 * scaleMultiplier
		val divisor = distance / renderDistance
		val renderX = cameraPos.x + (x + 0.5 - cameraPos.x) / divisor
		val renderY = cameraPos.y + (y + DYNAMIC_Y_LIFT * distance / 300.0 - cameraPos.y) / divisor
		val renderZ = cameraPos.z + (z + 0.5 - cameraPos.z) / divisor
		val adjustedScale = minOf(scale * WORLD_TEXT_SCALE, MAX_WORLD_TEXT_SCALE).toFloat()

		val matrix = Matrix4f()
			.translate(
				(renderX - cameraPos.x).toFloat(),
				(renderY - cameraPos.y).toFloat(),
				(renderZ - cameraPos.z).toFloat(),
			)
			.rotate(cameraRotation)
			.translate(0f, -yOffset * adjustedScale, 0f)
			.scale(adjustedScale, -adjustedScale, adjustedScale)

		val totalWidth = segments.sumOf { textRenderer.width(it.text) }
		var cursor = -totalWidth / 2f
		for (segment in segments) {
			textRenderer.drawInBatch(
				segment.text,
				cursor,
				0f,
				segment.color,
				true,
				matrix,
				consumers,
				DisplayMode.SEE_THROUGH,
				NO_BACKGROUND,
				FULL_BRIGHT_LIGHT,
			)
			cursor += textRenderer.width(segment.text)
		}
	}

	private fun resolveHellionShieldFromNames(names: List<String>): HellionShieldInfo? {
		for (shield in HellionShield.entries) {
			val line = names.firstOrNull { it.contains(shield.displayName, ignoreCase = true) } ?: continue
			val number = HELLION_NUMBER_PATTERN.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
			return HellionShieldInfo(shield, number ?: 0)
		}
		return null
	}

	private fun resolveDisplayNames(entity: LivingEntity, armorStands: List<ArmorStand>): List<String> {
		val names = mutableListOf<String>()
		names += entityCleanNames(entity)

		armorStands.asSequence()
			.filter { stand -> stand.isAlive && !stand.isRemoved }
			.filter { stand -> isNearbyNameStand(entity, stand) }
			.sortedBy { it.distanceToSqr(entity) }
			.forEach { stand ->
				names += armorStandCleanNames(stand)
			}
		return names.distinct()
	}

	private fun isNearbyNameStand(entity: LivingEntity, stand: ArmorStand): Boolean {
		return stand.distanceToSqr(entity) <= NAME_SEARCH_DISTANCE_SQUARED &&
			kotlin.math.abs(stand.x - entity.x) <= MAX_NAME_OFFSET_XZ &&
			kotlin.math.abs(stand.z - entity.z) <= MAX_NAME_OFFSET_XZ &&
			stand.y >= entity.y - MAX_NAME_OFFSET_BELOW &&
			stand.y <= entity.y + MAX_NAME_OFFSET_ABOVE
	}

	private fun entityCleanNames(entity: LivingEntity): List<String> {
		return listOfNotNull(
			cleanName(entity.customName?.string),
			cleanName(entity.displayName?.string),
			cleanName(entity.name.string),
		).distinct()
	}

	private fun armorStandCleanNames(stand: ArmorStand): List<String> {
		return listOfNotNull(
			cleanName(stand.customName?.string),
			cleanName(stand.displayName?.string),
			cleanName(stand.name.string),
		).distinct()
	}

	private fun cleanName(raw: String?): String? {
		val clean = stripMinecraftFormatting(raw ?: return null)
			.replace('\r', ' ')
			.replace('\n', ' ')
			.replace("\\s+".toRegex(), " ")
			.trim()
		return clean.takeIf { it.isNotEmpty() }
	}

	private fun resolveTier(names: List<String>, maxHealth: Double, visibleHealth: Long): Int? {
		for (name in names) {
			val match = DEMONLORD_TIER_PATTERN.find(name) ?: continue
			val token = match.groupValues[1]
			romanToInt(token)?.let { return it.coerceIn(1, 4) }
			token.toIntOrNull()?.let { return it.coerceIn(1, 4) }
		}

		return when {
			visibleHealth > 60_000_000L -> 4
			visibleHealth > 15_000_000L -> 3
			visibleHealth > 2_500_000L -> 2
			visibleHealth > 1_000L -> 1
			maxHealth <= 0.0 -> null
			maxHealth <= 2_500_000.0 -> 1
			maxHealth <= 15_000_000.0 -> 2
			maxHealth <= 60_000_000.0 -> 3
			else -> 4
		}
	}

	private fun resolveVisibleHealth(names: List<String>): Long? {
		for (name in names) {
			val match = HEALTH_PATTERN.find(name) ?: continue
			val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
			val suffix = match.groupValues[2].lowercase(Locale.ROOT)
			val multiplier = when (suffix) {
				"k" -> 1_000.0
				"m" -> 1_000_000.0
				"b" -> 1_000_000_000.0
				else -> 1.0
			}
			return (value * multiplier).roundToLong()
		}
		return null
	}

	private fun knownMaxHealth(tier: Int): Long = when (tier) {
		1 -> 2_500_000L
		2 -> 15_000_000L
		3 -> 60_000_000L
		else -> 150_000_000L
	}

	private fun knownDemonMaxHealth(tier: Int): Long = when (tier) {
		1 -> 500_000L
		2 -> 1_750_000L
		3 -> 5_000_000L
		else -> 10_000_000L
	}

	private fun resolveDemonTier(maxHealth: Double, visibleHealth: Long): Int? {
		val health = max(maxHealth.roundToLong(), visibleHealth)
		return when {
			health >= 7_500_000L -> 4
			health >= 3_000_000L -> 3
			health >= 1_000_000L -> 2
			health >= 100_000L -> 1
			else -> null
		}
	}

	private fun romanTier(tier: Int): String = when (tier) {
		1 -> "I"
		2 -> "II"
		3 -> "III"
		else -> "IV"
	}

	private fun romanToInt(token: String): Int? = when (token.uppercase(Locale.ROOT)) {
		"I" -> 1
		"II" -> 2
		"III" -> 3
		"IV" -> 4
		else -> null
	}

	private fun percentageColor(value: Long, maxValue: Long): Int {
		val percentage = if (maxValue <= 0L) 0.0 else value.toDouble() / maxValue.toDouble()
		return when {
			percentage >= 0.66 -> 0xFF55FF55.toInt()
			percentage >= 0.33 -> 0xFFFFE066.toInt()
			else -> 0xFFFF5555.toInt()
		}
	}

	private fun formatHealth(value: Long): String {
		val abs = kotlin.math.abs(value.toDouble())
		val pair = when {
			abs >= 1_000_000_000.0 -> value / 1_000_000_000.0 to "B"
			abs >= 1_000_000.0 -> value / 1_000_000.0 to "M"
			abs >= 1_000.0 -> value / 1_000.0 to "K"
			else -> return value.toString()
		}
		val formatted = String.format(Locale.ROOT, "%.1f", pair.first).removeSuffix(".0")
		return formatted + pair.second
	}

	private fun isRelevantLivingEntity(entity: LivingEntity): Boolean =
		entity.isAlive && !entity.isRemoved && entity !is Player && entity !is ArmorStand

	private fun stripMinecraftFormatting(input: String): String {
		if (!input.contains('§')) {
			return input
		}
		val builder = StringBuilder(input.length)
		var skip = false
		for (character in input) {
			if (skip) {
				skip = false
				continue
			}
			if (character == '§') {
				skip = true
				continue
			}
			builder.append(character)
		}
		return builder.toString()
	}

	fun onWorldChange() = clear()

	private fun clear() {
		featureTickCounter = 0
		tickCounter = 0
		trackedBosses = emptyMap()
		trackedShields = emptyMap()
		clearOwnBlazeBossMemory()
	}

	private data class BlazeBossInfo(
		val entity: LivingEntity,
		val tier: Int,
		val displayName: String,
		val kind: InfernoKind,
		val spawnedByPlayer: String?,
		val showPhase: Boolean,
		val showHealth: Boolean,
		val phase: Int,
		val phaseCount: Int,
		val phaseHealth: Long,
		val phaseMaxHealth: Long,
		val phaseRgb: Int,
		val healthRgb: Int,
		val hellionShield: HellionShieldInfo?,
		val twilightActive: Boolean,
	)

	private data class TextSegment(val text: String, val color: Int)

	private data class HellionShieldInfo(
		val shield: HellionShield,
		val number: Int,
	) {
		val displayName: String get() = shield.displayName
		val color: Int get() = shield.color
		fun labelWithNumber(): String = if (number > 0) "$displayName $number" else displayName
	}

	private enum class HellionShield(val displayName: String, val color: Int) {
		AURIC("AURIC", 0xFFFFFF55.toInt()),
		ASHEN("ASHEN", 0xFF555555.toInt()),
		SPIRIT("SPIRIT", 0xFFFFFFFF.toInt()),
		CRYSTAL("CRYSTAL", 0xFF55FFFF.toInt()),
	}

	private enum class InfernoKind(val displayName: String) {
		DEMONLORD("Inferno Demonlord"),
		QUAZII("Inferno Quazii"),
		TYPHOEUS("Inferno Typhoeus"),
	}

	private val SPAWNED_BY_PATTERN = Regex("(?i)\\bSpawned by:\\s*(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{1,16})\\b")
	private const val SCAN_INTERVAL_TICKS = 5
	private const val OWN_BLAZE_BOSS_MEMORY_TICKS = 400
	private const val NAME_SEARCH_DISTANCE_SQUARED = 25.0
	private const val DEMON_PAIR_DISTANCE_SQUARED = 256.0
	private const val DEMON_BOSS_DISTANCE_SQUARED = 400.0
	private const val SECOND_DEMON_PHASE = 3
	private const val MAX_NAME_OFFSET_XZ = 2.0
	private const val MAX_NAME_OFFSET_BELOW = 1.0
	private const val MAX_NAME_OFFSET_ABOVE = 4.5
	private const val BOSS_NAME_COLUMN_XZ = 0.85
	private const val BOSS_NAME_COLUMN_Y = 3.5
	private const val BOSS_SHIELD_MIN_Y_OFFSET = 0.6
	private const val SHIELD_TEXT_SCALE = 2.2
	private const val PHASE_TEXT_SCALE = 2.1
	private const val HEALTH_TEXT_SCALE = 1.9
	private const val TWILIGHT_TEXT_SCALE = 1.8
	private const val TWILIGHT_TEXT_OFFSET = 9f
	private const val SMALLEST_VIEW_DISTANCE = 6.0
	private const val HIDE_TOO_CLOSE_AT = 4.5
	private const val MAX_DYNAMIC_DISTANCE = 36.0
	private const val MAX_WORLD_TEXT_SCALE = 0.34
	private const val DYNAMIC_Y_LIFT = 20.0
	private const val WORLD_TEXT_SCALE = 0.05
	private const val FULL_BRIGHT_LIGHT = 0xF000F0
	private const val NO_BACKGROUND = 0
	private const val TWILIGHT_TEXT_COLOR = 0xFFC020FF.toInt()
	private const val TWILIGHT_ARROW_POISON_MARKER = "ᛤ"
	private const val QUAZII_MARKER = "ⓆⓊⒶⓏⒾⒾ"
	private const val TYPHOEUS_MARKER = "ⓉⓎⓅⒽⓄⒺⓊⓈ"
	private val DEMONLORD_TIER_PATTERN = Regex("Inferno Demonlord\\s+([IVX]+|\\d+)", RegexOption.IGNORE_CASE)
	private val HEALTH_PATTERN = Regex("([0-9][0-9,.]*)([kKmMbB]?)\\s*(?:❤|HP|Health)")
	private val HELLION_NUMBER_PATTERN = Regex("(\\d+)\\s*(?:$|[^0-9])")
}
