package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.font.TextRenderer.TextLayerType
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.entity.mob.BlazeEntity
import net.minecraft.entity.mob.GuardianEntity
import net.minecraft.entity.mob.WitherSkeletonEntity
import net.minecraft.entity.mob.ZombifiedPiglinEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket
import net.minecraft.text.Text
import net.minecraft.util.Hand
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import java.util.Locale
import java.util.UUID
import kotlin.jvm.optionals.getOrNull
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.roundToLong
import kotlin.random.Random

object BlazeSlayerFeature {
	private var featureTickCounter = 0
	private var tickCounter = 0
	private var trackedBosses: Map<UUID, BlazeBossInfo> = emptyMap()
	private var trackedShields: Map<UUID, HellionShieldInfo> = emptyMap()
	private var ownBlazeBossUuid: UUID? = null
	private var ownBlazeBossLastPosition: Vec3d? = null
	private var ownBlazeBossLastSeenTick = 0
	private var autoTickCounter = 0
	private var autoActionCooldownTicks = 0
	private var autoFightActive = false
	private var autoFightEmptyTicks = 0
	private var autoLastShield: HellionShield? = null
	private var autoSeenOpeningAshen = false
	private var autoTerminatorShotDone = false
	private var autoInMobPhase = false
	private var autoMobPhaseCount = 0
	private var autoFirstMobRagQueued = false
	private var autoFirstMobRagCast = false
	private var autoFirstMobTyphoeusSeen = false
	private var autoFirstMobTyphoeusAliveLastTick = false
	private var autoFirstMobPredictionQueued = false
	private var autoLastBossDaggerTarget: DaggerTarget? = null
	private var autoPendingDaggerPrediction: PendingDaggerPrediction? = null
	private var autoPendingAttunement: DaggerAttunement? = null
	private var autoPendingAttunementUntilTick = 0
	private var autoActionDelayMaxTicks = AUTO_ACTION_DELAY_MIN_TICKS
	private var autoLastObservedSelectedSlot: Int? = null
	private var autoManualOverrideUntilTick = 0
	private var autoLastBossUuid: UUID? = null
	private var autoBossGroundY: Double? = null
	private var autoBossAirMobPhaseQueued = false
	private var autoRagnarockCastWaitUntilTick = 0
	private var autoRagnarockCastFallbackUntilTick = 0
	private var autoRagnarockCastSeen = false
	private var autoRagnarockCastSeenTick = 0
	private var autoPostBossResetQueued = false
	private var autoPostBossResetRestoreSlot: Int? = null
	private var autoLastRagnarockRightClickTick = 0
	private var autoCocoonSuppressionUntilTick = 0
	private var autoLastCocoonedName: String? = null
	private var autoLastDebugMobKind: InfernoKind? = null
	private val autoQueuedActions = ArrayDeque<AutoAction>()
	private val autoWarnLastTicks = mutableMapOf<String, Int>()
	private val autoDebugLastTicks = mutableMapOf<String, Int>()

	fun onTick(client: MinecraftClient) {
		val config = XclipsenIrcBridgeClient.instance?.config()
		val world = client.world
		val player = client.player
		if (config?.slayerModuleEnabled != true ||
			(!config.slayerBlazePhaseDisplayEnabled && !config.slayerBlazeColoredMobsEnabled && !config.slayerBlazeAutoDaggerEnabled) ||
			world == null ||
			player == null ||
			!LocationTracker.isOnHypixelSkyBlock
		) {
			clear()
			clearAutoState()
			return
		}

		featureTickCounter++
		autoTickCounter++
		if (autoActionCooldownTicks > 0) {
			autoActionCooldownTicks--
		}

		if (++tickCounter >= SCAN_INTERVAL_TICKS) {
			tickCounter = 0

			val armorStands = world.entities.filterIsInstance<ArmorStandEntity>()
			val candidates = mutableListOf<BlazeBossInfo>()

			for (entity in world.entities.filterIsInstance<LivingEntity>()) {
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

		if (config.slayerBlazeAutoDaggerEnabled) {
			autoActionDelayMaxTicks = config.slayerBlazeAutoDaggerDelayMaxTicks.coerceIn(
				AUTO_ACTION_DELAY_MIN_TICKS,
				AUTO_ACTION_DELAY_MAX_TICKS,
			)
			handleAutoDagger(client, player)
		} else {
			clearAutoState()
		}
	}

	fun render(context: WorldRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.slayerModuleEnabled || !config.slayerBlazePhaseDisplayEnabled || trackedBosses.isEmpty()) {
			return
		}

		val client = MinecraftClient.getInstance()
		if (client.options.hudHidden) {
			return
		}
		if (client.player == null) {
			return
		}
		val textRenderer = client.textRenderer
		val camera = context.gameRenderer().camera
		val cameraPos = camera.cameraPos
		val consumers = context.consumers()

		for (info in trackedBosses.values) {
			val entity = info.entity
			if (!entity.isAlive || entity.isRemoved) {
				continue
			}
			val baseLocation = Vec3d(entity.x - 0.5, entity.y, entity.z - 0.5)
			val lineDefinitions = mutableListOf<Pair<List<TextSegment>, Double>>()
			info.hellionShield?.let { shield ->
				lineDefinitions += listOf(TextSegment(shield.labelWithNumber(), shield.color)) to DAGGER_TEXT_SCALE
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
					camera.rotation,
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
					camera.rotation,
					baseLocation,
					listOf(TextSegment("Twilight", TWILIGHT_TEXT_COLOR)),
					TWILIGHT_TEXT_SCALE,
					TWILIGHT_TEXT_OFFSET,
				)
			}
		}

		(consumers as? VertexConsumerProvider.Immediate)?.draw()
	}

	fun shouldGlow(entity: Entity): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		return config.slayerModuleEnabled &&
			config.slayerBlazeColoredMobsEnabled &&
			entity is LivingEntity &&
			!entity.isInvisible &&
			entity !is GuardianEntity &&
			trackedShields.containsKey(entity.uuid)
	}

	fun colorValue(entity: Entity): Int? = trackedShields[entity.uuid]?.color

	fun onIncomingGameMessage(message: Text, overlay: Boolean) {
		if (!overlay || autoRagnarockCastWaitUntilTick <= 0) {
			return
		}

		val clean = cleanName(message.string) ?: return
		if (AUTO_RAGNAROCK_FINISHED_ACTIONBAR_PATTERN.containsMatchIn(clean)) {
			autoRagnarockCastSeen = true
			autoRagnarockCastSeenTick = autoTickCounter
		}
	}

	fun onIncomingChatMessage(message: Text) {
		val clean = cleanName(message.string) ?: return
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		val player = MinecraftClient.getInstance().player

		AUTO_COCOON_CHAT_PATTERN.find(clean)?.let { match ->
			val mobName = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return@let
			if (isBlazeSlayerCocoonName(mobName)) {
				autoCocoonSuppressionUntilTick = autoTickCounter + AUTO_COCOON_SUPPRESSION_TICKS
				autoLastCocoonedName = mobName
				clearPostBossReset(player, "cocooned $mobName")
				debugAutoDagger(player, "cocoon detected: $mobName, suppressing post-boss reset")
			}
			return
		}

		AUTO_RAGNAROCK_COOLDOWN_CHAT_PATTERN.find(clean)?.let { match ->
			if (autoLastRagnarockRightClickTick > 0 &&
				autoTickCounter - autoLastRagnarockRightClickTick <= AUTO_RAGNAROCK_COOLDOWN_MATCH_TICKS
			) {
				val seconds = match.groupValues.getOrNull(1)?.ifBlank { "?" } ?: "?"
				debugAutoDagger(player, "ragnarock server cooldown: ${seconds}s")
			}
			return
		}

		if (clean.contains(SLAYER_QUEST_COMPLETE_MARKER, ignoreCase = true)) {
			if (config.slayerModuleEnabled && config.slayerBlazeAutoDaggerEnabled && config.slayerBlazeAutoDaggerResetAfterBossEnabled) {
				queuePostBossReset(player)
			}
		}
	}

	private fun handleAutoDagger(client: MinecraftClient, player: PlayerEntity) {
		if (client.currentScreen != null || client.world == null || client.interactionManager == null) {
			clearAutoQueue(player, "screen/world unavailable")
			clearPendingPrediction(player, "screen/world unavailable")
			clearPostBossReset(player, "screen/world unavailable")
			clearRagnarockCastWait()
			acceptCurrentHotbarSelection(player)
			return
		}

		val snapshot = resolveAutoSnapshot(player)
		if (snapshot.hasTarget && !autoFightActive) {
			autoFightActive = true
			resetAutoFightProgress()
			debugAutoDagger(player, "fight started")
		}

		if (autoFightActive && (detectManualHotbarSelection(player) || autoTickCounter < autoManualOverrideUntilTick)) {
			return
		}

		if (autoFightActive) {
			updateFirstMobPrediction(player, snapshot)
		}

		if (!snapshot.hasTarget) {
			if (shouldKeepRagnarockQueueWithoutTarget()) {
				if (autoActionCooldownTicks > 0) {
					return
				}
				if (autoQueuedActions.isNotEmpty()) {
					runNextAutoAction(client, player)
				}
				return
			}
			if (autoPendingDaggerPrediction != null) {
				if (autoActionCooldownTicks > 0) {
					return
				}
				if (autoQueuedActions.isNotEmpty()) {
					runNextAutoAction(client, player)
					return
				}
				if (queueReadyPrediction(player, snapshot) && autoQueuedActions.isNotEmpty()) {
					runNextAutoAction(client, player)
				}
				return
			}
			if (autoPostBossResetQueued) {
				if (autoActionCooldownTicks > 0) {
					return
				}
				if (autoQueuedActions.isNotEmpty()) {
					runNextAutoAction(client, player)
					return
				}
				completePostBossReset(player)
				return
			}
			if (autoTickCounter < autoCocoonSuppressionUntilTick) {
				debugAutoDagger(player, "no target during cocoon suppression", AUTO_DEBUG_STABLE_REPEAT_THROTTLE_TICKS)
				autoFightEmptyTicks++
				return
			}

			clearAutoQueue(player, "no target")
			clearRagnarockCastWait()
			acceptCurrentHotbarSelection(player)
			autoFightEmptyTicks++
			if (autoFightEmptyTicks >= AUTO_FIGHT_RESET_TICKS) {
				debugAutoDagger(player, "fight reset after $autoFightEmptyTicks empty ticks")
				clearAutoState()
			}
			return
		}

		autoFightEmptyTicks = 0

		if (autoActionCooldownTicks > 0) {
			return
		}

		if (detectFirstMobPhaseAirStart(snapshot) && queueFirstMobRagnarock(player)) {
			runNextAutoAction(client, player)
			return
		}

		if (autoQueuedActions.isNotEmpty()) {
			runNextAutoAction(client, player)
			return
		}

		if (snapshot.inMobPhase) {
			handleAutoMobPhase(player, snapshot)
		} else {
			if (snapshot.bossShield != null) {
				clearPendingPrediction(player, "boss shield visible")
			}
			handleAutoBossPhase(player, snapshot.bossShield)
		}

		if (autoQueuedActions.isEmpty()) {
			queueReadyPrediction(player, snapshot)
		}

		if (autoActionCooldownTicks <= 0 && autoQueuedActions.isNotEmpty()) {
			runNextAutoAction(client, player)
		}
	}

	private fun resolveAutoSnapshot(player: PlayerEntity): AutoSnapshot {
		val relevant = trackedBosses.values.filter { info ->
			info.entity.isAlive &&
				!info.entity.isRemoved &&
				info.entity.squaredDistanceTo(player) <= AUTO_TARGET_DISTANCE_SQUARED
		}
		val boss = relevant
			.asSequence()
			.filter { info -> info.kind == InfernoKind.DEMONLORD }
			.minByOrNull { info -> info.entity.squaredDistanceTo(player) }
		val quazii = relevant
			.asSequence()
			.filter { info -> info.kind == InfernoKind.QUAZII && info.phaseHealth > 0L }
			.minByOrNull { info -> info.entity.squaredDistanceTo(player) }
		val typhoeus = relevant
			.asSequence()
			.filter { info -> info.kind == InfernoKind.TYPHOEUS && info.phaseHealth > 0L }
			.minByOrNull { info -> info.entity.squaredDistanceTo(player) }
		return AutoSnapshot(
			boss = boss,
			bossShield = boss?.hellionShield?.shield,
			quazii = quazii,
			typhoeus = typhoeus,
		)
	}

	private fun detectFirstMobPhaseAirStart(snapshot: AutoSnapshot): Boolean {
		val boss = snapshot.boss ?: return false
		if (autoFirstMobRagQueued || autoFirstMobRagCast || autoMobPhaseCount > 0 || autoBossAirMobPhaseQueued) {
			return false
		}

		val bossUuid = boss.entity.uuid
		if (autoLastBossUuid != bossUuid) {
			autoLastBossUuid = bossUuid
			autoBossGroundY = boss.entity.y
			autoBossAirMobPhaseQueued = false
			return false
		}

		val groundY = autoBossGroundY?.let { minOf(it, boss.entity.y) } ?: boss.entity.y
		autoBossGroundY = groundY
		if (snapshot.inMobPhase) {
			return false
		}

		if (boss.entity.y - groundY < AUTO_BOSS_AIR_MOB_Y_DELTA) {
			return false
		}

		autoBossAirMobPhaseQueued = true
		return true
	}

	private fun updateFirstMobPrediction(player: PlayerEntity, snapshot: AutoSnapshot) {
		val inFirstMobPhase = autoMobPhaseCount == 1
		val typhoeusAlive = snapshot.typhoeusAlive

		if (inFirstMobPhase && typhoeusAlive) {
			autoFirstMobTyphoeusSeen = true
		}

		val typhoeusJustDisappeared =
			inFirstMobPhase &&
				autoFirstMobTyphoeusSeen &&
				autoFirstMobTyphoeusAliveLastTick &&
				!typhoeusAlive &&
				!autoFirstMobPredictionQueued

		if (typhoeusJustDisappeared) {
			val delayTicks = randomAutoActionDelayTicks()
			autoFirstMobPredictionQueued = true
			autoPendingDaggerPrediction = PendingDaggerPrediction(
				readyTick = autoTickCounter + delayTicks,
				expireTick = autoTickCounter + AUTO_DAGGER_PREDICTION_EXPIRE_TICKS,
				fallbackTarget = autoLastBossDaggerTarget,
			)
			debugAutoDagger(player, "prediction queued after first Typhoeus disappeared delay=${delayTicks}t")
		}

		autoFirstMobTyphoeusAliveLastTick = inFirstMobPhase && typhoeusAlive
	}

	private fun queueReadyPrediction(player: PlayerEntity, snapshot: AutoSnapshot): Boolean {
		val prediction = autoPendingDaggerPrediction ?: return false

		if (autoTickCounter > prediction.expireTick) {
			autoPendingDaggerPrediction = null
			debugAutoDagger(player, "prediction expired")
			return false
		}

		if (autoTickCounter < prediction.readyTick) {
			return true
		}

		val target = snapshot.bossShield?.let(::daggerTargetForShield)
			?: prediction.fallbackTarget
			?: run {
				debugAutoDagger(player, "prediction skipped: no visible shield or fallback target")
				return true
			}

		autoPendingDaggerPrediction = null
		debugAutoDagger(player, "prediction selecting ${target.attunement}")
		queueDaggerSelection(player, target.family, target.attunement)
		return autoQueuedActions.isNotEmpty()
	}

	private fun clearPendingPrediction(player: PlayerEntity, reason: String) {
		if (autoPendingDaggerPrediction != null) {
			autoPendingDaggerPrediction = null
			debugAutoDagger(player, "prediction cleared: $reason")
		}
	}

	private fun handleAutoBossPhase(player: PlayerEntity, shield: HellionShield?) {
		if (autoInMobPhase) {
			debugAutoDagger(player, "mob phase exited")
		}
		autoInMobPhase = false
		autoLastDebugMobKind = null
		if (shield == null) {
			return
		}

		val target = daggerTargetForShield(shield)
		autoLastBossDaggerTarget = target
		if (shield != autoLastShield) {
			debugAutoDagger(player, "boss shield $shield selecting ${target.attunement}")
		}
		if (shield == HellionShield.ASHEN && !autoTerminatorShotDone) {
			autoSeenOpeningAshen = true
		}

		if (shield == HellionShield.SPIRIT &&
			autoLastShield == HellionShield.ASHEN &&
			autoSeenOpeningAshen &&
			!autoTerminatorShotDone
		) {
			autoTerminatorShotDone = true
			autoLastShield = shield
			queueTerminatorIntoSpirit(player)
			return
		}

		autoLastShield = shield
		queueDaggerSelection(player, target.family, target.attunement)
	}

	private fun queueTerminatorIntoSpirit(player: PlayerEntity) {
		val terminatorSlot = findHotbarSlot(player, TERMINATOR_IDS)
		if (terminatorSlot == null) {
			debugAutoDagger(player, "terminator missing, selecting Spirit dagger")
			queueDaggerSelection(player, DaggerFamily.SPIRIT, DaggerAttunement.SPIRIT)
			return
		}

		debugAutoDagger(player, "terminator queued before Spirit dagger")
		queueSelectSlot(player, terminatorSlot)
		autoQueuedActions += AutoAction.RightClick(RightClickKind.TERMINATOR)
		queueDaggerSelection(player, DaggerFamily.SPIRIT, DaggerAttunement.SPIRIT)
	}

	private fun handleAutoMobPhase(player: PlayerEntity, snapshot: AutoSnapshot) {
		if (!autoInMobPhase) {
			autoInMobPhase = true
			autoMobPhaseCount++
			debugAutoDagger(player, "mob phase entered #$autoMobPhaseCount")
			if (autoMobPhaseCount == 1 && queueFirstMobRagnarock(player)) {
				return
			}
		}

		val desiredMob = if (autoMobPhaseCount <= 1) {
			when {
				snapshot.quazii != null -> snapshot.quazii
				snapshot.typhoeus != null -> snapshot.typhoeus
				else -> null
			}
		} else {
			when {
				snapshot.typhoeus != null -> snapshot.typhoeus
				snapshot.quazii != null -> snapshot.quazii
				else -> null
			}
		}

		if (desiredMob != null) {
			val target = resolveMobDaggerTarget(desiredMob)
			if (desiredMob.kind != autoLastDebugMobKind) {
				autoLastDebugMobKind = desiredMob.kind
				debugAutoDagger(player, "mob target ${desiredMob.kind} selecting ${target.attunement}")
			}
			queueDaggerSelection(player, target.family, target.attunement)
		}
	}

	private fun queueFirstMobRagnarock(player: PlayerEntity): Boolean {
		if (autoFirstMobRagQueued || autoFirstMobRagCast) {
			debugAutoDagger(player, "ragnarock skipped: already ${if (autoFirstMobRagCast) "cast" else "queued"}")
			return false
		}

		val ragnarockSlot = findHotbarSlot(player, RAGNAROCK_IDS)
		if (ragnarockSlot == null) {
			debugAutoDagger(player, "ragnarock skipped: axe missing from hotbar")
			return false
		}

		autoFirstMobRagQueued = true
		debugAutoDagger(player, "ragnarock queued from slot ${ragnarockSlot + 1}")
		queueSelectSlot(player, ragnarockSlot)
		autoQueuedActions += AutoAction.RightClick(RightClickKind.RAGNAROCK)
		autoQueuedActions += AutoAction.WaitForRagnarockCast
		return true
	}

	private fun queuePostBossReset(player: PlayerEntity?) {
		if (player == null) {
			return
		}
		if (!autoFightActive && autoLastBossDaggerTarget == null && autoMobPhaseCount <= 0) {
			debugAutoDagger(player, "post-boss dagger reset skipped: no recent blaze fight")
			return
		}
		if (autoTickCounter < autoCocoonSuppressionUntilTick) {
			debugAutoDagger(player, "post-boss reset suppressed: recent cocoon ${autoLastCocoonedName ?: "unknown"}")
			return
		}

		val restoreSlot = player.inventory.getSelectedSlot().takeIf { it in HOTBAR_SLOT_RANGE }
		clearAutoQueue(player, "post-boss reset setup")
		clearPendingPrediction(player, "post-boss reset setup")
		clearRagnarockCastWait()
		autoPendingAttunement = null
		autoPendingAttunementUntilTick = 0
		autoPostBossResetQueued = true
		autoPostBossResetRestoreSlot = restoreSlot
		acceptCurrentHotbarSelection(player)
		debugAutoDagger(player, "post-boss dagger reset queued")

		queuePostBossDaggerReset(player, DaggerFamily.ASHEN, DaggerAttunement.ASHEN)
		queuePostBossDaggerReset(player, DaggerFamily.SPIRIT, DaggerAttunement.SPIRIT)
		if (restoreSlot != null) {
			debugAutoDagger(player, "post-boss restoring slot ${restoreSlot + 1}")
			queueSelectSlot(player, restoreSlot)
		}
	}

	private fun queuePostBossDaggerReset(player: PlayerEntity, family: DaggerFamily, attunement: DaggerAttunement) {
		val slot = findHotbarSlot(player, family.ids)
		if (slot == null) {
			debugAutoDagger(player, "post-boss dagger reset skipped: missing ${family.displayName}")
			return
		}

		debugAutoDagger(player, "post-boss resetting $attunement dagger")
		queueSelectSlot(player, slot)
		val stack = player.inventory.getStack(slot)
		if (!stack.isOf(attunement.materialItem)) {
			autoQueuedActions += AutoAction.RightClick(RightClickKind.DAGGER_ATTUNE, attunement)
		}
	}

	private fun completePostBossReset(player: PlayerEntity?) {
		if (!autoPostBossResetQueued) {
			return
		}
		autoPostBossResetQueued = false
		autoPostBossResetRestoreSlot = null
		debugAutoDagger(player, "post-boss dagger reset complete")
	}

	private fun clearPostBossReset(player: PlayerEntity?, reason: String) {
		if (!autoPostBossResetQueued && autoPostBossResetRestoreSlot == null) {
			return
		}
		autoPostBossResetQueued = false
		autoPostBossResetRestoreSlot = null
		autoQueuedActions.clear()
		debugAutoDagger(player, "post-boss dagger reset cleared: $reason")
	}

	private fun resolveMobDaggerTarget(info: BlazeBossInfo): DaggerTarget {
		info.hellionShield?.shield?.let { shield ->
			return daggerTargetForShield(shield)
		}

		return when (info.kind) {
			InfernoKind.QUAZII -> DaggerTarget(DaggerFamily.SPIRIT, DaggerAttunement.SPIRIT)
			InfernoKind.TYPHOEUS -> DaggerTarget(DaggerFamily.ASHEN, DaggerAttunement.ASHEN)
			InfernoKind.DEMONLORD -> DaggerTarget(DaggerFamily.ASHEN, DaggerAttunement.ASHEN)
		}
	}

	private fun daggerTargetForShield(shield: HellionShield): DaggerTarget {
		return when (shield) {
			HellionShield.ASHEN -> DaggerTarget(DaggerFamily.ASHEN, DaggerAttunement.ASHEN)
			HellionShield.AURIC -> DaggerTarget(DaggerFamily.ASHEN, DaggerAttunement.AURIC)
			HellionShield.SPIRIT -> DaggerTarget(DaggerFamily.SPIRIT, DaggerAttunement.SPIRIT)
			HellionShield.CRYSTAL -> DaggerTarget(DaggerFamily.SPIRIT, DaggerAttunement.CRYSTAL)
		}
	}

	private fun queueDaggerSelection(player: PlayerEntity, family: DaggerFamily, attunement: DaggerAttunement?) {
		val slot = findHotbarSlot(player, family.ids)
		if (slot == null) {
			debugAutoDagger(player, "dagger missing: ${family.displayName}")
			warnMissingDagger(player, family)
			return
		}

		queueSelectSlot(player, slot)
		if (attunement == null) {
			return
		}

		val stack = player.inventory.getStack(slot)
		if (stack.isOf(attunement.materialItem)) {
			if (autoPendingAttunement == attunement) {
				autoPendingAttunement = null
			}
			debugAutoDagger(player, "dagger already attuned: $attunement", AUTO_DEBUG_STABLE_REPEAT_THROTTLE_TICKS)
			return
		}
		if (autoPendingAttunement == attunement && autoTickCounter < autoPendingAttunementUntilTick) {
			debugAutoDagger(player, "attunement retry skipped: $attunement pending", AUTO_DEBUG_STABLE_REPEAT_THROTTLE_TICKS)
			return
		}
		debugAutoDagger(player, "attunement queued: $attunement")
		autoQueuedActions += AutoAction.RightClick(RightClickKind.DAGGER_ATTUNE, attunement)
	}

	private fun queueSelectSlot(player: PlayerEntity, slot: Int) {
		if (slot !in HOTBAR_SLOT_RANGE || queuedSelectedSlot(player) == slot) {
			return
		}
		autoQueuedActions += AutoAction.SelectSlot(slot)
	}

	private fun queuedSelectedSlot(player: PlayerEntity): Int {
		var selectedSlot = player.inventory.getSelectedSlot()
		for (action in autoQueuedActions) {
			if (action is AutoAction.SelectSlot) {
				selectedSlot = action.slot
			}
		}
		return selectedSlot
	}

	private fun runNextAutoAction(client: MinecraftClient, player: PlayerEntity) {
		val action = autoQueuedActions.firstOrNull() ?: return
		when (action) {
			is AutoAction.SelectSlot -> {
				autoQueuedActions.removeFirst()
				if (selectHotbarSlot(client, player, action.slot)) {
					debugAutoDagger(player, "selected hotbar slot ${action.slot + 1}")
					autoActionCooldownTicks = randomAutoActionDelayTicks()
				}
			}
			is AutoAction.RightClick -> {
				autoQueuedActions.removeFirst()
				if (isRightClickAllowed(player, action)) {
					client.interactionManager?.interactItem(player, Hand.MAIN_HAND)
					if (action.kind == RightClickKind.DAGGER_ATTUNE && action.attunement != null) {
						autoPendingAttunement = action.attunement
						autoPendingAttunementUntilTick = autoTickCounter + AUTO_ATTUNE_RETRY_TICKS
						debugAutoDagger(player, "attunement right-click sent: ${action.attunement}")
					}
					if (action.kind == RightClickKind.RAGNAROCK) {
						autoFirstMobRagCast = true
						autoFirstMobRagQueued = false
						autoLastRagnarockRightClickTick = autoTickCounter
						debugAutoDagger(player, "ragnarock right-click sent")
						beginRagnarockCastWait()
					}
					if (action.kind == RightClickKind.TERMINATOR) {
						debugAutoDagger(player, "terminator right-click sent")
					}
					autoActionCooldownTicks = randomAutoActionDelayTicks()
				} else {
					if (action.kind == RightClickKind.RAGNAROCK) {
						autoFirstMobRagQueued = false
						debugAutoDagger(player, "ragnarock right-click skipped: current item is not Ragnarok Axe")
					} else {
						debugAutoDagger(player, "${action.kind} right-click skipped: held item mismatch")
					}
				}
			}
			is AutoAction.Wait -> {
				autoQueuedActions.removeFirst()
				autoActionCooldownTicks = action.ticks.coerceAtLeast(1)
			}
			AutoAction.WaitForRagnarockCast -> {
				val waitingForRagnarock = autoRagnarockCastWaitUntilTick > 0
				val sawSignal = autoRagnarockCastSeen
				if (isRagnarockCastWaitComplete()) {
					autoQueuedActions.removeFirst()
					if (waitingForRagnarock) {
						debugAutoDagger(
							player,
							if (sawSignal) "ragnarock wait completed by actionbar signal" else "ragnarock wait completed by fallback timeout",
						)
					}
				} else {
					debugAutoDagger(player, "waiting for ragnarock cast signal", AUTO_DEBUG_STABLE_REPEAT_THROTTLE_TICKS)
					autoActionCooldownTicks = 1
				}
			}
		}
	}

	private fun shouldKeepRagnarockQueueWithoutTarget(): Boolean {
		return (autoBossAirMobPhaseQueued && autoQueuedActions.isNotEmpty()) ||
			autoRagnarockCastWaitUntilTick > 0 ||
			autoQueuedActions.firstOrNull() == AutoAction.WaitForRagnarockCast
	}

	private fun beginRagnarockCastWait() {
		autoRagnarockCastWaitUntilTick = autoTickCounter + AUTO_RAGNAROCK_MIN_WAIT_TICKS
		autoRagnarockCastFallbackUntilTick = autoTickCounter + AUTO_RAGNAROCK_SIGNAL_FALLBACK_TICKS
		autoRagnarockCastSeen = false
		autoRagnarockCastSeenTick = 0
	}

	private fun clearAutoQueue(player: PlayerEntity?, reason: String) {
		if (autoQueuedActions.isNotEmpty()) {
			debugAutoDagger(player, "queue cleared: $reason")
		}
		autoQueuedActions.clear()
		autoFirstMobRagQueued = false
		if (autoPostBossResetQueued || autoPostBossResetRestoreSlot != null) {
			autoPostBossResetQueued = false
			autoPostBossResetRestoreSlot = null
			debugAutoDagger(player, "post-boss dagger reset cleared: $reason")
		}
	}

	private fun isRagnarockCastWaitComplete(): Boolean {
		if (autoRagnarockCastWaitUntilTick <= 0) {
			return true
		}
		if (autoTickCounter < autoRagnarockCastWaitUntilTick) {
			return false
		}
		if (autoRagnarockCastSeen && autoTickCounter < autoRagnarockCastSeenTick + AUTO_RAGNAROCK_POST_CAST_SETTLE_TICKS) {
			return false
		}
		if (!autoRagnarockCastSeen && autoTickCounter < autoRagnarockCastFallbackUntilTick) {
			return false
		}

		clearRagnarockCastWait()
		return true
	}

	private fun clearRagnarockCastWait() {
		autoRagnarockCastWaitUntilTick = 0
		autoRagnarockCastFallbackUntilTick = 0
		autoRagnarockCastSeen = false
		autoRagnarockCastSeenTick = 0
	}

	private fun selectHotbarSlot(client: MinecraftClient, player: PlayerEntity, slot: Int): Boolean {
		if (slot !in HOTBAR_SLOT_RANGE || player.inventory.getSelectedSlot() == slot) {
			return false
		}
		player.inventory.setSelectedSlot(slot)
		client.networkHandler?.sendPacket(UpdateSelectedSlotC2SPacket(slot))
		autoLastObservedSelectedSlot = slot
		return true
	}

	private fun detectManualHotbarSelection(player: PlayerEntity): Boolean {
		val selectedSlot = player.inventory.getSelectedSlot()
		val lastObserved = autoLastObservedSelectedSlot
		if (lastObserved == null) {
			autoLastObservedSelectedSlot = selectedSlot
			return false
		}
		if (selectedSlot == lastObserved) {
			return false
		}

		autoLastObservedSelectedSlot = selectedSlot
		clearAutoQueue(player, "manual hotbar override")
		autoPendingAttunement = null
		autoPendingAttunementUntilTick = 0
		clearPendingPrediction(player, "manual hotbar override")
		clearRagnarockCastWait()
		autoActionCooldownTicks = max(autoActionCooldownTicks, AUTO_MANUAL_SWAP_PAUSE_TICKS)
		autoManualOverrideUntilTick = autoTickCounter + AUTO_MANUAL_SWAP_PAUSE_TICKS
		debugAutoDagger(player, "manual hotbar override detected")
		return true
	}

	private fun acceptCurrentHotbarSelection(player: PlayerEntity) {
		autoLastObservedSelectedSlot = player.inventory.getSelectedSlot()
	}

	private fun randomAutoActionDelayTicks(): Int {
		val maxTicks = autoActionDelayMaxTicks.coerceIn(AUTO_ACTION_DELAY_MIN_TICKS, AUTO_ACTION_DELAY_MAX_TICKS)
		return if (maxTicks <= AUTO_ACTION_DELAY_MIN_TICKS) {
			AUTO_ACTION_DELAY_MIN_TICKS
		} else {
			Random.nextInt(AUTO_ACTION_DELAY_MIN_TICKS, maxTicks + 1)
		}
	}

	private fun isRightClickAllowed(player: PlayerEntity, action: AutoAction.RightClick): Boolean {
		val stack = player.mainHandStack
		val id = skyBlockItemId(stack) ?: return false
		return when (action.kind) {
			RightClickKind.TERMINATOR -> id in TERMINATOR_IDS
			RightClickKind.RAGNAROCK -> id in RAGNAROCK_IDS
			RightClickKind.DAGGER_ATTUNE -> {
				val attunement = action.attunement ?: return false
				id in attunement.family.ids && !stack.isOf(attunement.materialItem)
			}
		}
	}

	private fun findHotbarSlot(player: PlayerEntity, ids: List<String>): Int? {
		for (id in ids) {
			for (slot in HOTBAR_SLOT_RANGE) {
				if (skyBlockItemId(player.inventory.getStack(slot)) == id) {
					return slot
				}
			}
		}
		return null
	}

	private fun skyBlockItemId(stack: ItemStack): String? {
		if (stack.isEmpty) {
			return null
		}
		val customData = stack.get(DataComponentTypes.CUSTOM_DATA) as? NbtComponent ?: return null
		return customData.copyNbt()
			.getString("id")
			.getOrNull()
			?.trim()
			?.uppercase(Locale.ROOT)
			?.takeIf { it.isNotEmpty() }
	}

	private fun warnMissingDagger(player: PlayerEntity, family: DaggerFamily) {
		val key = "missing:${family.name}"
		val lastTick = autoWarnLastTicks[key]
		if (lastTick != null && autoTickCounter - lastTick < AUTO_WARNING_THROTTLE_TICKS) {
			return
		}
		autoWarnLastTicks[key] = autoTickCounter
		player.sendMessage(
			Text.literal("[Xclipsen] Auto Dagger missing ${family.displayName} in hotbar."),
			false,
		)
	}

	private fun filterOwnBlazeCandidates(candidates: List<BlazeBossInfo>, player: PlayerEntity): List<BlazeBossInfo> {
		val playerName = MinecraftClient.getInstance().session.username.takeIf { it.isNotBlank() }
			?: cleanName(player.name.string)
			?: return emptyList()
		val ownDemonlord = candidates
			.asSequence()
			.filter { info -> info.kind == InfernoKind.DEMONLORD && isSpawnedByPlayer(info.spawnedByPlayer, playerName) }
			.minByOrNull { info -> info.entity.squaredDistanceTo(player) }

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

	private fun entityPosition(entity: LivingEntity): Vec3d = Vec3d(entity.x, entity.y, entity.z)

	private fun squaredDistanceTo(entity: LivingEntity, position: Vec3d): Double {
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
					demon.entity.squaredDistanceTo(info.entity) <= DEMON_PAIR_DISTANCE_SQUARED
			}
			info.copy(showHealth = info.kind == firstActiveKind || !firstActiveDemonAlive)
		}
	}

	private fun resolveFirstActiveDemonKind(info: BlazeBossInfo, candidates: List<BlazeBossInfo>): InfernoKind {
		val nearestDemonlord = candidates
			.asSequence()
			.filter { candidate -> candidate.kind == InfernoKind.DEMONLORD }
			.minByOrNull { candidate -> candidate.entity.squaredDistanceTo(info.entity) }
		val isSecondDemonPhase = nearestDemonlord != null &&
			nearestDemonlord.entity.squaredDistanceTo(info.entity) <= DEMON_BOSS_DISTANCE_SQUARED &&
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

	private fun resolveBossInfo(entity: LivingEntity, armorStands: List<ArmorStandEntity>): BlazeBossInfo? {
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
			entity is BlazeEntity && names.any { it.contains("Inferno Demonlord", ignoreCase = true) } -> InfernoKind.DEMONLORD
			entity is WitherSkeletonEntity && names.any { it.contains(QUAZII_MARKER) || it.contains("Quazii", ignoreCase = true) } -> InfernoKind.QUAZII
			entity is ZombifiedPiglinEntity && names.any { it.contains(TYPHOEUS_MARKER) || it.contains("Typhoeus", ignoreCase = true) } -> InfernoKind.TYPHOEUS
			else -> null
		}
	}

	private fun resolveBossHellionShield(
		entity: LivingEntity,
		armorStands: List<ArmorStandEntity>,
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
			.sortedWith(compareBy<ArmorStandEntity> { stand ->
				if (identityStands.isEmpty()) kotlin.math.abs(stand.x - entity.x) + kotlin.math.abs(stand.z - entity.z)
				else identityStands.minOf { identity -> stand.squaredDistanceTo(identity) }
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
		stand: ArmorStandEntity,
		identityStands: List<ArmorStandEntity>,
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
		textRenderer: TextRenderer,
		consumers: VertexConsumerProvider,
		cameraPos: Vec3d,
		cameraRotation: org.joml.Quaternionf,
		location: Vec3d,
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

		val totalWidth = segments.sumOf { textRenderer.getWidth(it.text) }
		var cursor = -totalWidth / 2f
		for (segment in segments) {
			textRenderer.draw(
				segment.text,
				cursor,
				0f,
				segment.color,
				true,
				matrix,
				consumers,
				TextLayerType.SEE_THROUGH,
				NO_BACKGROUND,
				FULL_BRIGHT_LIGHT,
			)
			cursor += textRenderer.getWidth(segment.text)
		}
	}

	private fun resolveHellionShield(entity: LivingEntity, armorStands: List<ArmorStandEntity>): HellionShieldInfo? {
		if (!entity.isAlive ||
			entity.isRemoved ||
			entity.isInvisible ||
			entity is PlayerEntity ||
			entity is ArmorStandEntity ||
			entity is GuardianEntity
		) {
			return null
		}
		val names = resolveDisplayNames(entity, armorStands)
		return resolveHellionShieldFromNames(names)
	}

	private fun resolveHellionShieldFromNames(names: List<String>): HellionShieldInfo? {
		for (shield in HellionShield.entries) {
			val line = names.firstOrNull { it.contains(shield.displayName, ignoreCase = true) } ?: continue
			val number = HELLION_NUMBER_PATTERN.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
			return HellionShieldInfo(shield, number ?: 0)
		}
		return null
	}

	private fun resolveDisplayNames(entity: LivingEntity, armorStands: List<ArmorStandEntity>): List<String> {
		val names = mutableListOf<String>()
		names += entityCleanNames(entity)

		armorStands.asSequence()
			.filter { stand -> stand.isAlive && !stand.isRemoved }
			.filter { stand -> isNearbyNameStand(entity, stand) }
			.sortedBy { it.squaredDistanceTo(entity) }
			.forEach { stand ->
				names += armorStandCleanNames(stand)
			}
		return names.distinct()
	}

	private fun isNearbyNameStand(entity: LivingEntity, stand: ArmorStandEntity): Boolean {
		return stand.squaredDistanceTo(entity) <= NAME_SEARCH_DISTANCE_SQUARED &&
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

	private fun armorStandCleanNames(stand: ArmorStandEntity): List<String> {
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

	private fun isBlazeSlayerCocoonName(name: String): Boolean {
		val normalized = name.lowercase(Locale.ROOT)
		return BLAZE_SLAYER_COCOON_NAME_MARKERS.any { marker -> normalized.contains(marker) }
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
		entity.isAlive && !entity.isRemoved && entity !is PlayerEntity && entity !is ArmorStandEntity

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

	private fun debugAutoDagger(
		player: PlayerEntity?,
		message: String,
		repeatThrottleTicks: Int = AUTO_DEBUG_REPEAT_THROTTLE_TICKS,
	) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.slayerBlazeAutoDaggerDebugEnabled) {
			return
		}
		val lastTick = autoDebugLastTicks[message]
		if (lastTick != null && autoTickCounter - lastTick < repeatThrottleTicks) {
			return
		}
		autoDebugLastTicks[message] = autoTickCounter
		player?.sendMessage(Text.literal("[AutoDaggerDebug] $message"), false)
	}

	private fun clear() {
		featureTickCounter = 0
		tickCounter = 0
		trackedBosses = emptyMap()
		trackedShields = emptyMap()
		clearOwnBlazeBossMemory()
	}

	private fun clearAutoState() {
		autoTickCounter = 0
		autoFightActive = false
		autoFightEmptyTicks = 0
		autoWarnLastTicks.clear()
		resetAutoFightProgress()
	}

	private fun resetAutoFightProgress() {
		autoActionCooldownTicks = 0
		autoLastShield = null
		autoSeenOpeningAshen = false
		autoTerminatorShotDone = false
		autoInMobPhase = false
		autoMobPhaseCount = 0
		autoFirstMobRagQueued = false
		autoFirstMobRagCast = false
		autoFirstMobTyphoeusSeen = false
		autoFirstMobTyphoeusAliveLastTick = false
		autoFirstMobPredictionQueued = false
		autoLastBossDaggerTarget = null
		autoPendingDaggerPrediction = null
		autoPendingAttunement = null
		autoPendingAttunementUntilTick = 0
		autoActionDelayMaxTicks = AUTO_ACTION_DELAY_MIN_TICKS
		autoLastObservedSelectedSlot = null
		autoManualOverrideUntilTick = 0
		autoLastBossUuid = null
		autoBossGroundY = null
		autoBossAirMobPhaseQueued = false
		autoPostBossResetQueued = false
		autoPostBossResetRestoreSlot = null
		autoLastRagnarockRightClickTick = 0
		autoCocoonSuppressionUntilTick = 0
		autoLastCocoonedName = null
		autoLastDebugMobKind = null
		autoDebugLastTicks.clear()
		clearRagnarockCastWait()
		autoQueuedActions.clear()
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

	private data class AutoSnapshot(
		val boss: BlazeBossInfo?,
		val bossShield: HellionShield?,
		val quazii: BlazeBossInfo?,
		val typhoeus: BlazeBossInfo?,
	) {
		val quaziiAlive: Boolean = quazii != null
		val typhoeusAlive: Boolean = typhoeus != null
		val inMobPhase: Boolean = quaziiAlive || typhoeusAlive
		val hasTarget: Boolean = boss != null || inMobPhase
	}

	private data class DaggerTarget(
		val family: DaggerFamily,
		val attunement: DaggerAttunement,
	)

	private data class PendingDaggerPrediction(
		val readyTick: Int,
		val expireTick: Int,
		val fallbackTarget: DaggerTarget?,
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

	private sealed interface AutoAction {
		data class SelectSlot(val slot: Int) : AutoAction
		data class RightClick(val kind: RightClickKind, val attunement: DaggerAttunement? = null) : AutoAction
		data class Wait(val ticks: Int) : AutoAction
		data object WaitForRagnarockCast : AutoAction
	}

	private enum class RightClickKind {
		TERMINATOR,
		RAGNAROCK,
		DAGGER_ATTUNE,
	}

	private enum class HellionShield(val displayName: String, val color: Int) {
		AURIC("AURIC", 0xFFFFFF55.toInt()),
		ASHEN("ASHEN", 0xFF555555.toInt()),
		SPIRIT("SPIRIT", 0xFFFFFFFF.toInt()),
		CRYSTAL("CRYSTAL", 0xFF55FFFF.toInt()),
	}

	private enum class DaggerFamily(val displayName: String, val ids: List<String>) {
		ASHEN("Ashen/Auric dagger", listOf("HEARTFIRE_DAGGER", "BURSTFIRE_DAGGER", "FIREDUST_DAGGER")),
		SPIRIT("Spirit/Crystal dagger", listOf("HEARTMAW_DAGGER", "BURSTMAW_DAGGER", "MAWDUST_DAGGER")),
	}

	private enum class DaggerAttunement(val family: DaggerFamily, val materialItem: Item) {
		ASHEN(DaggerFamily.ASHEN, Items.STONE_SWORD),
		SPIRIT(DaggerFamily.SPIRIT, Items.IRON_SWORD),
		AURIC(DaggerFamily.ASHEN, Items.GOLDEN_SWORD),
		CRYSTAL(DaggerFamily.SPIRIT, Items.DIAMOND_SWORD),
	}

	private enum class InfernoKind(val displayName: String) {
		DEMONLORD("Inferno Demonlord"),
		QUAZII("Inferno Quazii"),
		TYPHOEUS("Inferno Typhoeus"),
	}

	private val HOTBAR_SLOT_RANGE = 0..8
	private val TERMINATOR_IDS = listOf("TERMINATOR")
	private val RAGNAROCK_IDS = listOf("RAGNAROCK_AXE")
	private val AUTO_RAGNAROCK_FINISHED_ACTIONBAR_PATTERN = Regex("(?i)(?:^|\\s)CASTING\\s*$")
	private val AUTO_RAGNAROCK_COOLDOWN_CHAT_PATTERN = Regex("(?i)This ability is on cooldown for\\s+([0-9]+)s")
	private val AUTO_COCOON_CHAT_PATTERN = Regex("(?i)^CAUGHT!\\s+You\\s+cocooned\\s+(?:a|an)\\s+(.+?)!$")
	private val BLAZE_SLAYER_COCOON_NAME_MARKERS = listOf(
		"inferno demonlord",
		"inferno quazii",
		"inferno typhoeus",
		"quazii",
		"typhoeus",
		"kindleheart demon",
		"burningsoul demon",
	)
	private val SPAWNED_BY_PATTERN = Regex("(?i)\\bSpawned by:\\s*(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{1,16})\\b")
	private const val SLAYER_QUEST_COMPLETE_MARKER = "SLAYER QUEST COMPLETE!"
	private const val SCAN_INTERVAL_TICKS = 5
	private const val AUTO_ACTION_DELAY_MIN_TICKS = 2
	private const val AUTO_ACTION_DELAY_MAX_TICKS = 5
	private const val AUTO_MANUAL_SWAP_PAUSE_TICKS = 12
	private const val AUTO_BOSS_AIR_MOB_Y_DELTA = 2.0
	private const val AUTO_RAGNAROCK_MIN_WAIT_TICKS = 60
	private const val AUTO_RAGNAROCK_SIGNAL_FALLBACK_TICKS = 100
	private const val AUTO_RAGNAROCK_POST_CAST_SETTLE_TICKS = 5
	private const val AUTO_RAGNAROCK_COOLDOWN_MATCH_TICKS = 40
	private const val AUTO_ATTUNE_RETRY_TICKS = 8
	private const val AUTO_WARNING_THROTTLE_TICKS = 120
	private const val AUTO_FIGHT_RESET_TICKS = 80
	private const val AUTO_DAGGER_PREDICTION_EXPIRE_TICKS = 20
	private const val AUTO_COCOON_SUPPRESSION_TICKS = 120
	private const val AUTO_DEBUG_REPEAT_THROTTLE_TICKS = 10
	private const val AUTO_DEBUG_STABLE_REPEAT_THROTTLE_TICKS = 80
	private const val AUTO_TARGET_DISTANCE_SQUARED = 2500.0
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
	private const val DAGGER_TEXT_SCALE = 2.2
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
