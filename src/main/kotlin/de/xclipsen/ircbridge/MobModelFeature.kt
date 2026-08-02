package de.xclipsen.ircbridge

import de.xclipsen.ircbridge.mixin.EntityRendererInvoker
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.state.AllayRenderState
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.item.ItemStack
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object MobModelFeature {
	private val LOGGER = LoggerFactory.getLogger("xclipsen_mob_model")
	private val syncExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "xclipsen-mob-model-sync").apply { isDaemon = true }
	}
	private val queuedReplacementBypasses = ConcurrentHashMap<Int, Int>()
	private val renderEntityCache = mutableMapOf<String, LivingEntity>()
	private val babySetterCache = ConcurrentHashMap<Class<*>, Method?>()
	// Fish-like renderers intentionally roll onto their side on land; player replacements should stay upright.
	private val aquaticModelIds = setOf(
		"minecraft:axolotl",
		"minecraft:cod",
		"minecraft:dolphin",
		"minecraft:elder_guardian",
		"minecraft:glow_squid",
		"minecraft:guardian",
		"minecraft:nautilus",
		"minecraft:pufferfish",
		"minecraft:salmon",
		"minecraft:squid",
		"minecraft:tadpole",
		"minecraft:tropical_fish",
		"minecraft:turtle",
		"minecraft:zombie_nautilus",
	)

	private const val FETCH_INTERVAL_MS = 5_000L
	private const val UPLOAD_INTERVAL_MS = 15_000L

	@Volatile
	private var syncedStates: Map<String, BackendMobModelState> = emptyMap()

	@Volatile
	private var fetchInFlight = false

	@Volatile
	private var uploadInFlight = false

	@Volatile
	private var uploadDirty = true

	@Volatile
	private var lastFetchAt = 0L

	@Volatile
	private var lastUploadAt = 0L

	@Volatile
	private var lastUploadedSignature = ""

	fun onStartup() {
		uploadDirty = true
		MobModelCatalog.logDiagnostics("startup")
		requestImmediateSync()
	}

	fun onConfigChanged() {
		uploadDirty = true
		clearRenderCache()
		MobModelCatalog.logDiagnostics("config_changed")
		requestImmediateSync()
	}

	fun onDisconnect() {
		clearRenderCache()
		queuedReplacementBypasses.clear()
		syncedStates = emptyMap()
		lastFetchAt = 0L
		lastUploadAt = 0L
		fetchInFlight = false
		uploadInFlight = false
	}

	fun shutdown() {
		onDisconnect()
		syncExecutor.shutdownNow()
	}

	fun onTick(client: Minecraft) {
		val playerName = currentPlayerName(client) ?: return
		val now = System.currentTimeMillis()

		if (!fetchInFlight && now - lastFetchAt >= FETCH_INTERVAL_MS) {
			fetchInFlight = true
			val generation = ClientSessionLifecycle.snapshot()
			syncExecutor.execute {
				try {
					fetchRemoteStates(generation)
				} finally {
					fetchInFlight = false
				}
			}
		}

		val signature = localStateSignature(client)
		if (signature != lastUploadedSignature) {
			uploadDirty = true
		}

		if (!uploadInFlight && (uploadDirty || now - lastUploadAt >= UPLOAD_INTERVAL_MS)) {
			uploadInFlight = true
			val generation = ClientSessionLifecycle.snapshot()
			val configSnapshot = XclipsenIrcBridgeClient.instance?.config()?.copy() ?: return
			syncExecutor.execute {
				try {
					uploadLocalState(playerName, configSnapshot, generation)
				} finally {
					uploadInFlight = false
				}
			}
		}
	}

	fun statusLine(): String {
		val config = XclipsenIrcBridgeClient.instance?.config()
		val syncedCount = syncedStates.values.count { it.enabled }
		if (config == null) {
			return "Waiting for client"
		}

		val local = localSelection(config)
		if (local == null) {
			return if (syncedCount > 0) "Disabled locally, synced $syncedCount player(s)" else "Disabled"
		}

		return when {
			resolveEntityType(local.entityType) == null -> "Invalid mob id: ${local.entityType}"
			MobModelVariantCatalog.validate(local.entityType, local.variant) != null -> MobModelVariantCatalog.validate(local.entityType, local.variant).orEmpty()
			else -> {
				val variant = MobModelVariantCatalog.normalize(local.variant)
				val scale = String.format(Locale.ROOT, "%.2fx", local.scale)
				val variantLabel = if (variant.isBlank()) "" else ", $variant"
				if (syncedCount > 0) {
					"Rendering as ${local.entityType}$variantLabel, $scale ($syncedCount synced)"
				} else {
					"Rendering as ${local.entityType}$variantLabel, $scale"
				}
			}
		}
	}

	fun renderReplacement(
		state: AvatarRenderState,
		matrices: PoseStack,
		queue: SubmitNodeCollector,
		cameraState: CameraRenderState,
	): Boolean {
		if (consumeReplacementBypass(state.id)) {
			return false
		}

		val client = Minecraft.getInstance()
		val world = client.level ?: return false
		val player = world.getEntity(state.id) as? AbstractClientPlayer ?: return false
		val selection = selectionForPlayer(player) ?: return false
		val entityType = resolveEntityType(selection.entityType) ?: return false
		val renderEntity = prepareRenderEntity(player, selection, entityType) ?: return false
		return runCatching {
			val tickProgress = client.deltaTracker.getGameTimeDeltaPartialTick(false)
			val renderState = client.entityRenderDispatcher.extractEntity(renderEntity, tickProgress)
			val renderer = client.entityRenderDispatcher.getRenderer(renderState)
			adjustRenderState(renderState, state, renderEntity, selection)
			val displayName = renderState.nameTag
			val nameLabelPos = renderState.nameTagAttachment
			matrices.pushPose()
			try {
				matrices.scale(selection.scale, selection.scale, selection.scale)
				renderState.nameTag = null
				renderer.submit(renderState, matrices, queue, cameraState)
			} finally {
				matrices.popPose()
			}
			renderState.nameTag = displayName
			renderState.nameTagAttachment = adjustedNameLabelPos(nameLabelPos, renderState.boundingBoxHeight, selection.scale)
			(renderer as? EntityRendererInvoker)?.invokeRenderLabelIfPresent(renderState, matrices, queue, cameraState)
			renderState.nameTagAttachment = nameLabelPos
			true
		}.getOrElse { failure ->
			LOGGER.warn("Failed to render mob model replacement for {}", selection.entityType, failure)
			false
		}
	}

	fun inventoryPreviewEntity(player: AbstractClientPlayer, useMobModel: Boolean): LivingEntity {
		if (!useMobModel) {
			return player
		}

		val selection = localSelection(XclipsenIrcBridgeClient.instance?.config() ?: return player) ?: return player
		val entityType = resolveEntityType(selection.entityType) ?: return player
		return prepareRenderEntity(player, selection, entityType) ?: player
	}

	fun queueReplacementBypass(playerId: Int) {
		queuedReplacementBypasses.merge(playerId, 1, Int::plus)
	}

	private fun consumeReplacementBypass(playerId: Int): Boolean {
		var consumed = false
		queuedReplacementBypasses.compute(playerId) { _, count ->
			if (count == null || count <= 0) {
				return@compute count
			}
			consumed = true
			if (count == 1) null else count - 1
		}
		return consumed
	}

	private fun fetchRemoteStates(generation: Long) {
		lastFetchAt = System.currentTimeMillis()
		val backend = XclipsenIrcBridgeClient.instance?.backendBridge() ?: return
		val response = backend.fetchMobModelStates() ?: return
		if (!ClientSessionLifecycle.isCurrent(generation)) return
		syncedStates = response.states
			.mapNotNull(::normalizeRemoteState)
			.associateBy { normalizePlayerName(it.minecraftUsername) }
		clearRenderCache()
	}

	private fun uploadLocalState(playerName: String, config: BridgeConfig, generation: Long) {
		val backend = XclipsenIrcBridgeClient.instance?.backendBridge() ?: return
		val payload = BackendMobModelState().apply {
			minecraftUsername = playerName
			enabled = config.mobModelModuleEnabled
			entityType = normalizeEntityTypeId(config.mobModelEntityType)
			variant = MobModelVariantCatalog.normalize(config.mobModelVariant)
			baby = config.mobModelBaby
			showArmor = config.mobModelShowArmor
			showHeldItems = config.mobModelShowHeldItems
			scale = config.mobModelScale.coerceIn(0.25f, 4.0f)
			updatedAt = System.currentTimeMillis()
		}

		if (!backend.uploadMobModelState(payload, generation)) {
			return
		}
		if (!ClientSessionLifecycle.isCurrent(generation)) return

		lastUploadAt = payload.updatedAt
		lastUploadedSignature = localStateSignature(config)
		uploadDirty = false
		syncedStates = syncedStates.toMutableMap().also {
			it[normalizePlayerName(playerName)] = payload
		}
	}

	private fun selectionForPlayer(player: AbstractClientPlayer): BackendMobModelState? {
		val client = Minecraft.getInstance()
		if (player == client.player) {
			return localSelection(XclipsenIrcBridgeClient.instance?.config() ?: return null)
		}

		val username = normalizePlayerName(player.gameProfile.name)
		val remote = syncedStates[username] ?: return null
		return remote.takeIf { it.enabled }
	}

	private fun localSelection(config: BridgeConfig): BackendMobModelState? {
		if (!config.mobModelModuleEnabled) {
			return null
		}

		return BackendMobModelState().also {
			it.enabled = true
			it.entityType = normalizeEntityTypeId(config.mobModelEntityType)
			it.variant = MobModelVariantCatalog.normalize(config.mobModelVariant)
			it.baby = config.mobModelBaby
			it.showArmor = config.mobModelShowArmor
			it.showHeldItems = config.mobModelShowHeldItems
			it.scale = config.mobModelScale.coerceIn(0.25f, 4.0f)
		}
	}

	private fun normalizeRemoteState(raw: BackendMobModelState?): BackendMobModelState? {
		val incoming = raw ?: return null
		val playerName = normalizePlayerName(incoming.minecraftUsername)
		if (playerName.isBlank()) {
			return null
		}

		return BackendMobModelState().also {
			it.minecraftUsername = playerName
			it.enabled = incoming.enabled
			it.entityType = normalizeEntityTypeId(incoming.entityType)
			val normalizedVariant = MobModelVariantCatalog.normalize(incoming.variant)
			it.variant = if (MobModelVariantCatalog.validate(it.entityType, normalizedVariant) == null) normalizedVariant else ""
			it.baby = incoming.baby
			it.showArmor = incoming.showArmor
			it.showHeldItems = incoming.showHeldItems
			it.scale = incoming.scale.takeIf { scale -> scale.isFinite() }?.coerceIn(0.25f, 4.0f) ?: 1.0f
			it.updatedAt = incoming.updatedAt.coerceAtLeast(0L)
		}
	}

	private fun prepareRenderEntity(
		player: AbstractClientPlayer,
		selection: BackendMobModelState,
		entityType: EntityType<*>,
	): LivingEntity? {
		val world = player.level()
		val cacheKey = "${player.gameProfile.name.lowercase(Locale.ROOT)}|${selection.entityType}|${selection.variant}|${selection.baby}"
		val existing = renderEntityCache[cacheKey]
		val renderEntity = if (existing != null && existing.type == entityType && existing.level() == world) {
			existing
		} else {
			val created = entityType.create(world, EntitySpawnReason.COMMAND) as? LivingEntity ?: return null
			created.walkAnimation.stop()
			renderEntityCache.clear()
			renderEntityCache[cacheKey] = created
			created
		}

		val previousAge = renderEntity.tickCount
		renderEntity.setId(player.id)
		renderEntity.setOldPosAndRot(Vec3(player.xo, player.yo, player.zo), player.yRotO, player.xRotO)
		renderEntity.copyPosition(player)
		renderEntity.absSnapTo(player.x, player.y, player.z, player.yRot, player.xRot)
		renderEntity.setYRot(player.yRot)
		renderEntity.setXRot(player.xRot)
		renderEntity.setYBodyRot(player.yBodyRot)
		renderEntity.setYHeadRot(player.yHeadRot)
		renderEntity.yRotO = player.yRotO
		renderEntity.xRotO = player.xRotO
		renderEntity.yBodyRot = player.yBodyRot
		renderEntity.yBodyRotO = player.yBodyRotO
		renderEntity.yHeadRot = player.yHeadRot
		renderEntity.yHeadRotO = player.yHeadRotO
		renderEntity.setPose(Pose.STANDING)
		renderEntity.setShiftKeyDown(player.isShiftKeyDown)
		renderEntity.setSprinting(player.isSprinting)
		renderEntity.setInvisible(player.isInvisible)
		renderEntity.setOnGround(player.onGround())
		renderEntity.setDeltaMovement(player.deltaMovement)
		renderEntity.tickCount = player.tickCount
		renderEntity.xxa = player.xxa
		renderEntity.zza = player.zza
		renderEntity.yya = player.yya
		renderEntity.swinging = player.swinging
		renderEntity.swingTime = player.swingTime
		renderEntity.attackAnim = player.attackAnim
		renderEntity.oAttackAnim = player.oAttackAnim
		if (previousAge != player.tickCount) {
			renderEntity.walkAnimation.update(player.walkAnimation.speed(), 1.0f, 1.0f)
		}
		renderEntity.xOld = player.xOld
		renderEntity.yOld = player.yOld
		renderEntity.zOld = player.zOld
		renderEntity.setCustomName(player.displayName)
		renderEntity.setCustomNameVisible(true)
		applyBabyState(renderEntity, selection.baby)
		applyEquipmentState(renderEntity, player, selection)
		MobModelVariantCatalog.apply(renderEntity, selection.entityType, selection.variant)
		return renderEntity
	}

	private fun applyEquipmentState(
		renderEntity: LivingEntity,
		player: AbstractClientPlayer,
		selection: BackendMobModelState,
	) {
		val head = if (selection.showArmor) player.getItemBySlot(EquipmentSlot.HEAD).copy() else ItemStack.EMPTY
		val chest = if (selection.showArmor) player.getItemBySlot(EquipmentSlot.CHEST).copy() else ItemStack.EMPTY
		val legs = if (selection.showArmor) player.getItemBySlot(EquipmentSlot.LEGS).copy() else ItemStack.EMPTY
		val feet = if (selection.showArmor) player.getItemBySlot(EquipmentSlot.FEET).copy() else ItemStack.EMPTY
		renderEntity.setItemSlot(EquipmentSlot.HEAD, head)
		renderEntity.setItemSlot(EquipmentSlot.CHEST, chest)
		renderEntity.setItemSlot(EquipmentSlot.LEGS, legs)
		renderEntity.setItemSlot(EquipmentSlot.FEET, feet)

		val mainHand = if (selection.showHeldItems) player.getItemInHand(InteractionHand.MAIN_HAND).copy() else ItemStack.EMPTY
		val offHand = if (selection.showHeldItems) player.getItemInHand(InteractionHand.OFF_HAND).copy() else ItemStack.EMPTY
		renderEntity.setItemInHand(InteractionHand.MAIN_HAND, mainHand)
		renderEntity.setItemInHand(InteractionHand.OFF_HAND, offHand)

		if (selection.showHeldItems && player.isUsingItem) {
			renderEntity.startUsingItem(player.usedItemHand)
		} else {
			renderEntity.stopUsingItem()
		}
	}

	private fun adjustRenderState(
		renderState: Any,
		playerState: AvatarRenderState,
		renderEntity: LivingEntity,
		selection: BackendMobModelState,
	) {
		if (renderState is LivingEntityRenderState) {
			renderState.bodyRot = playerState.bodyRot
			renderState.yRot = playerState.yRot
			renderState.xRot = playerState.xRot
			renderState.pose = Pose.STANDING
			renderState.bedOrientation = null
			renderState.deathTime = 0.0f
			renderState.isAutoSpinAttack = false
			renderState.isUpsideDown = false
			if (selection.entityType in aquaticModelIds) {
				renderState.isInWater = true
			}
		}

		if (renderState is AllayRenderState) {
			val isHoldingItem = selection.showHeldItems && (
				!renderEntity.getItemInHand(InteractionHand.MAIN_HAND).isEmpty || !renderEntity.getItemInHand(InteractionHand.OFF_HAND).isEmpty
			)
			renderState.holdingAnimationProgress = if (isHoldingItem) 1.0f else 0.0f
		}
	}

	private fun adjustedNameLabelPos(base: Vec3?, entityHeight: Float, scale: Float): Vec3? {
		val current = base ?: return null
		if (scale == 1.0f) {
			return current
		}

		val extraY = entityHeight * (scale - 1.0f)
		return current.add(0.0, extraY.toDouble(), 0.0)
	}

	private fun applyBabyState(entity: LivingEntity, baby: Boolean) {
		if (entity is AgeableMob) {
			entity.setBaby(baby)
			return
		}

		val setter = babySetterCache.computeIfAbsent(entity.javaClass) { type ->
			runCatching { type.getMethod("setBaby", Boolean::class.javaPrimitiveType) }.getOrNull()
		} ?: return

		runCatching { setter.invoke(entity, baby) }
			.onFailure { LOGGER.debug("Failed to apply baby state to {}", entity.type, it) }
	}

	private fun resolveEntityType(rawId: String): EntityType<*>? {
		return MobModelCatalog.resolve(normalizeEntityTypeId(rawId))
	}

	private fun currentPlayerName(client: Minecraft): String? {
		val username = client.user?.name?.trim().orEmpty()
		return username.takeIf { it.isNotBlank() }
	}

	private fun normalizePlayerName(raw: String?): String {
		return raw.orEmpty().trim().lowercase(Locale.ROOT)
	}

	private fun normalizeEntityTypeId(raw: String?): String {
		val candidate = raw.orEmpty().trim().lowercase(Locale.ROOT)
		if (candidate.isBlank()) {
			return "minecraft:zombie"
		}

		val namespaced = if (':' in candidate) candidate else "minecraft:$candidate"
		val id = Identifier.tryParse(namespaced) ?: return "minecraft:zombie"
		return id.toString()
	}

	private fun localStateSignature(client: Minecraft): String {
		return localStateSignature(XclipsenIrcBridgeClient.instance?.config() ?: BridgeConfig())
	}

	private fun localStateSignature(config: BridgeConfig): String {
		return listOf(
			config.mobModelModuleEnabled.toString(),
			normalizeEntityTypeId(config.mobModelEntityType),
			MobModelVariantCatalog.normalize(config.mobModelVariant),
			config.mobModelBaby.toString(),
			config.mobModelShowArmor.toString(),
			config.mobModelShowHeldItems.toString(),
			config.mobModelScale.coerceIn(0.25f, 4.0f).toString(),
			FabricLoader.getInstance().gameDir.toString(),
		).joinToString("|")
	}

	private fun clearRenderCache() {
		renderEntityCache.clear()
	}

	private fun requestImmediateSync() {
		val client = Minecraft.getInstance()
		val playerName = currentPlayerName(client) ?: return
		val generation = ClientSessionLifecycle.snapshot()
		val configSnapshot = XclipsenIrcBridgeClient.instance?.config()?.copy() ?: return
		if (!uploadInFlight) {
			uploadInFlight = true
			syncExecutor.execute {
				try {
					uploadLocalState(playerName, configSnapshot, generation)
				} finally {
					uploadInFlight = false
				}
			}
		}
		if (!fetchInFlight) {
			fetchInFlight = true
			syncExecutor.execute {
				try {
					fetchRemoteStates(generation)
				} finally {
					fetchInFlight = false
				}
			}
		}
	}
}
