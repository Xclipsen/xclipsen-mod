package de.xclipsen.ircbridge

import de.xclipsen.ircbridge.mixin.EntityRendererInvoker
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.state.AllayEntityRenderState
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.EntityPose
import net.minecraft.entity.EntityType
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.passive.PassiveEntity
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier
import net.minecraft.util.Hand
import net.minecraft.util.math.Vec3d
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
		LOGGER.warn("MOB_MODEL_BUILD_MARKER local-catalog-v2")
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

	fun onTick(client: MinecraftClient) {
		val playerName = currentPlayerName(client) ?: return
		val now = System.currentTimeMillis()

		if (!fetchInFlight && now - lastFetchAt >= FETCH_INTERVAL_MS) {
			fetchInFlight = true
			syncExecutor.execute {
				try {
					fetchRemoteStates(playerName)
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
			syncExecutor.execute {
				try {
					uploadLocalState(playerName)
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
		state: PlayerEntityRenderState,
		matrices: MatrixStack,
		queue: OrderedRenderCommandQueue,
		cameraState: CameraRenderState,
	): Boolean {
		if (consumeReplacementBypass(state.id)) {
			return false
		}

		val client = MinecraftClient.getInstance()
		val world = client.world ?: return false
		val player = world.getEntityById(state.id) as? AbstractClientPlayerEntity ?: return false
		val selection = selectionForPlayer(player) ?: return false
		val entityType = resolveEntityType(selection.entityType) ?: return false
		val renderEntity = prepareRenderEntity(player, selection, entityType) ?: return false
		return runCatching {
			val tickProgress = client.renderTickCounter.getTickProgress(false)
			val renderState = client.entityRenderDispatcher.getAndUpdateRenderState(renderEntity, tickProgress)
			val renderer = client.entityRenderDispatcher.getRenderer(renderState)
			adjustRenderState(renderState, state, renderEntity, selection)
			val displayName = renderState.displayName
			val nameLabelPos = renderState.nameLabelPos
			matrices.push()
			try {
				matrices.scale(selection.scale, selection.scale, selection.scale)
				renderState.displayName = null
				renderer.render(renderState, matrices, queue, cameraState)
			} finally {
				matrices.pop()
			}
			renderState.displayName = displayName
			renderState.nameLabelPos = adjustedNameLabelPos(nameLabelPos, renderState.height, selection.scale)
			(renderer as? EntityRendererInvoker)?.invokeRenderLabelIfPresent(renderState, matrices, queue, cameraState)
			renderState.nameLabelPos = nameLabelPos
			true
		}.getOrElse { failure ->
			LOGGER.warn("Failed to render mob model replacement for {}", selection.entityType, failure)
			false
		}
	}

	fun inventoryPreviewEntity(player: AbstractClientPlayerEntity, useMobModel: Boolean): LivingEntity {
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

	private fun fetchRemoteStates(playerName: String) {
		lastFetchAt = System.currentTimeMillis()
		val backend = XclipsenIrcBridgeClient.instance?.backendBridge() ?: return
		val response = backend.fetchMobModelStates(playerName) ?: return
		syncedStates = response.states
			.mapNotNull(::normalizeRemoteState)
			.associateBy { normalizePlayerName(it.minecraftUsername) }
		clearRenderCache()
	}

	private fun uploadLocalState(playerName: String) {
		val backend = XclipsenIrcBridgeClient.instance?.backendBridge() ?: return
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
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

		if (!backend.uploadMobModelState(playerName, payload)) {
			return
		}

		lastUploadAt = payload.updatedAt
		lastUploadedSignature = localStateSignature(config)
		uploadDirty = false
		syncedStates = syncedStates.toMutableMap().also {
			it[normalizePlayerName(playerName)] = payload
		}
	}

	private fun selectionForPlayer(player: AbstractClientPlayerEntity): BackendMobModelState? {
		val client = MinecraftClient.getInstance()
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
		player: AbstractClientPlayerEntity,
		selection: BackendMobModelState,
		entityType: EntityType<*>,
	): LivingEntity? {
		val world = player.entityWorld
		val cacheKey = "${player.gameProfile.name.lowercase(Locale.ROOT)}|${selection.entityType}|${selection.variant}|${selection.baby}"
		val existing = renderEntityCache[cacheKey]
		val renderEntity = if (existing != null && existing.type == entityType && existing.entityWorld == world) {
			existing
		} else {
			val created = entityType.create(world, SpawnReason.COMMAND) as? LivingEntity ?: return null
			created.limbAnimator.reset()
			renderEntityCache.clear()
			renderEntityCache[cacheKey] = created
			created
		}

		val previousAge = renderEntity.age
		renderEntity.setId(player.id)
		renderEntity.setLastPositionAndAngles(Vec3d(player.lastX, player.lastY, player.lastZ), player.lastYaw, player.lastPitch)
		renderEntity.copyPositionAndRotation(player)
		renderEntity.refreshPositionAndAngles(player.x, player.y, player.z, player.yaw, player.pitch)
		renderEntity.setYaw(player.yaw)
		renderEntity.setPitch(player.pitch)
		renderEntity.setBodyYaw(player.bodyYaw)
		renderEntity.setHeadYaw(player.headYaw)
		renderEntity.lastYaw = player.lastYaw
		renderEntity.lastPitch = player.lastPitch
		renderEntity.bodyYaw = player.bodyYaw
		renderEntity.lastBodyYaw = player.lastBodyYaw
		renderEntity.headYaw = player.headYaw
		renderEntity.lastHeadYaw = player.lastHeadYaw
		renderEntity.setPose(EntityPose.STANDING)
		renderEntity.setSneaking(player.isSneaking)
		renderEntity.setSprinting(player.isSprinting)
		renderEntity.setInvisible(player.isInvisible)
		renderEntity.setOnGround(player.isOnGround)
		renderEntity.setVelocity(player.velocity)
		renderEntity.age = player.age
		renderEntity.sidewaysSpeed = player.sidewaysSpeed
		renderEntity.forwardSpeed = player.forwardSpeed
		renderEntity.upwardSpeed = player.upwardSpeed
		renderEntity.handSwinging = player.handSwinging
		renderEntity.handSwingTicks = player.handSwingTicks
		renderEntity.handSwingProgress = player.handSwingProgress
		renderEntity.lastHandSwingProgress = player.lastHandSwingProgress
		if (previousAge != player.age) {
			renderEntity.limbAnimator.updateLimbs(player.limbAnimator.getSpeed(), 1.0f, 1.0f)
		}
		renderEntity.lastRenderX = player.lastRenderX
		renderEntity.lastRenderY = player.lastRenderY
		renderEntity.lastRenderZ = player.lastRenderZ
		renderEntity.setCustomName(player.displayName)
		renderEntity.setCustomNameVisible(true)
		applyBabyState(renderEntity, selection.baby)
		applyEquipmentState(renderEntity, player, selection)
		MobModelVariantCatalog.apply(renderEntity, selection.entityType, selection.variant)
		return renderEntity
	}

	private fun applyEquipmentState(
		renderEntity: LivingEntity,
		player: AbstractClientPlayerEntity,
		selection: BackendMobModelState,
	) {
		val head = if (selection.showArmor) player.getEquippedStack(EquipmentSlot.HEAD).copy() else ItemStack.EMPTY
		val chest = if (selection.showArmor) player.getEquippedStack(EquipmentSlot.CHEST).copy() else ItemStack.EMPTY
		val legs = if (selection.showArmor) player.getEquippedStack(EquipmentSlot.LEGS).copy() else ItemStack.EMPTY
		val feet = if (selection.showArmor) player.getEquippedStack(EquipmentSlot.FEET).copy() else ItemStack.EMPTY
		renderEntity.equipStack(EquipmentSlot.HEAD, head)
		renderEntity.equipStack(EquipmentSlot.CHEST, chest)
		renderEntity.equipStack(EquipmentSlot.LEGS, legs)
		renderEntity.equipStack(EquipmentSlot.FEET, feet)

		val mainHand = if (selection.showHeldItems) player.getStackInHand(Hand.MAIN_HAND).copy() else ItemStack.EMPTY
		val offHand = if (selection.showHeldItems) player.getStackInHand(Hand.OFF_HAND).copy() else ItemStack.EMPTY
		renderEntity.setStackInHand(Hand.MAIN_HAND, mainHand)
		renderEntity.setStackInHand(Hand.OFF_HAND, offHand)

		if (selection.showHeldItems && player.isUsingItem) {
			renderEntity.setCurrentHand(player.activeHand)
		} else {
			renderEntity.clearActiveItem()
		}
	}

	private fun adjustRenderState(
		renderState: Any,
		playerState: PlayerEntityRenderState,
		renderEntity: LivingEntity,
		selection: BackendMobModelState,
	) {
		if (renderState is LivingEntityRenderState) {
			renderState.bodyYaw = playerState.bodyYaw
			renderState.relativeHeadYaw = playerState.relativeHeadYaw
			renderState.pitch = playerState.pitch
			renderState.pose = EntityPose.STANDING
			renderState.sleepingDirection = null
			renderState.deathTime = 0.0f
			renderState.usingRiptide = false
			renderState.flipUpsideDown = false
			if (selection.entityType in aquaticModelIds) {
				renderState.touchingWater = true
			}
		}

		if (renderState is AllayEntityRenderState) {
			val isHoldingItem = selection.showHeldItems && (
				!renderEntity.getStackInHand(Hand.MAIN_HAND).isEmpty || !renderEntity.getStackInHand(Hand.OFF_HAND).isEmpty
			)
			renderState.itemHoldAnimationTicks = if (isHoldingItem) 1.0f else 0.0f
		}
	}

	private fun adjustedNameLabelPos(base: Vec3d?, entityHeight: Float, scale: Float): Vec3d? {
		val current = base ?: return null
		if (scale == 1.0f) {
			return current
		}

		val extraY = entityHeight * (scale - 1.0f)
		return current.add(0.0, extraY.toDouble(), 0.0)
	}

	private fun applyBabyState(entity: LivingEntity, baby: Boolean) {
		if (entity is PassiveEntity) {
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

	private fun currentPlayerName(client: MinecraftClient): String? {
		val username = client.session?.username?.trim().orEmpty()
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

	private fun localStateSignature(client: MinecraftClient): String {
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
		val client = MinecraftClient.getInstance()
		val playerName = currentPlayerName(client) ?: return
		if (!uploadInFlight) {
			uploadInFlight = true
			syncExecutor.execute {
				try {
					uploadLocalState(playerName)
				} finally {
					uploadInFlight = false
				}
			}
		}
		if (!fetchInFlight) {
			fetchInFlight = true
			syncExecutor.execute {
				try {
					fetchRemoteStates(playerName)
				} finally {
					fetchInFlight = false
				}
			}
		}
	}
}
