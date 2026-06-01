package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.render.entity.state.EntityRenderState
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.particle.DustParticleEffect
import net.minecraft.particle.ParticleEffect
import net.minecraft.registry.Registries
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.Vec3d
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

object FireFreezeFeature {
	private val fireFreezes = ConcurrentHashMap<PositionKey, FireFreezeArea>()
	private val frozenMobs = ConcurrentHashMap<UUID, FrozenMob>()
	private val suppressedEffectAreas = ConcurrentHashMap<PositionKey, SuppressedEffectArea>()
	private var currentAlertText = ""
	private var alertVisibleUntil = 0L
	private var tickCounter = 0

	fun onTick(client: MinecraftClient) {
		val config = XclipsenIrcBridgeClient.instance?.config()
		if (config?.fireFreezeModuleEnabled != true || client.world == null || client.player == null || !LocationTracker.isOnHypixelSkyBlock) {
			clear()
			return
		}

		if (++tickCounter < CLEANUP_INTERVAL_TICKS) {
			return
		}
		tickCounter = 0
		val now = System.currentTimeMillis()
		for (frozenMob in frozenMobs.values) {
			val remainingMs = frozenMob.freezeEndsAtMs - now
			if (!frozenMob.alertTriggered && remainingMs in 1..REFREEZE_THRESHOLD_MS) {
				frozenMob.alertTriggered = true
				if (config.fireFreezeRefreezeAlertEnabled) {
					playAlertSound(config)
					showAlert("Refreeze now!")
				}
			}
		}
		fireFreezes.values.removeIf { it.frozen || it.hasFinished(now) }
		frozenMobs.values.removeIf { it.freezeEndsAtMs <= now || client.world?.getEntityById(it.entityId) == null }
		suppressedEffectAreas.values.removeIf { it.expiresAtMs <= now }
	}

	fun onWorldChange() = clear()

	fun onSound(
		x: Double,
		y: Double,
		z: Double,
		sound: RegistryEntry<SoundEvent>,
		category: SoundCategory,
		volume: Float,
		pitch: Float,
	) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.fireFreezeModuleEnabled || !LocationTracker.isOnHypixelSkyBlock) {
			return
		}

		val soundId = Registries.SOUND_EVENT.getId(sound.value()).toString()
		val pos = Vec3d(x, y, z)
		when (soundId) {
			"minecraft:entity.elder_guardian.ambient" -> handleActiveSound(pos, volume, pitch)
			"minecraft:block.anvil.land" -> handleAnvilSound(pos, volume, pitch)
		}
	}

	fun shouldSuppressParticle(effect: ParticleEffect, x: Double, y: Double, z: Double, velocityX: Double, velocityY: Double, velocityZ: Double): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		if (!config.fireFreezeModuleEnabled || !config.fireFreezeCustomCircleEnabled || !LocationTracker.isOnHypixelSkyBlock) {
			return false
		}
		if (effect !is DustParticleEffect || velocityX != PARTICLE_OFFSET || velocityY != PARTICLE_OFFSET || velocityZ != PARTICLE_OFFSET) {
			return false
		}

		return isInsideSuppressedEffectArea(Vec3d(x, y, z), System.currentTimeMillis())
	}

	fun render(context: WorldRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.fireFreezeModuleEnabled || MinecraftClient.getInstance().options.hudHidden || !LocationTracker.isOnHypixelSkyBlock) {
			return
		}

		val now = System.currentTimeMillis()
		val cameraPos = context.gameRenderer().camera.cameraPos
		val matrices = context.matrices()
		val consumers = context.consumers()
		matrices.push()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.peek()
		val circleLineWidth = config.fireFreezeCircleLineWidth.coerceIn(1.0f, 8.0f)
		val circleLineLayer = XclipsenRenderLayers.getXrayLine(circleLineWidth.toDouble())
		val boxLineLayer = XclipsenRenderLayers.getXrayLine(2.0)
		val circleLineConsumer = consumers.getBuffer(circleLineLayer)
		val boxLineConsumer = consumers.getBuffer(boxLineLayer)
		val fillConsumer = consumers.getBuffer(XclipsenRenderLayers.getXrayFill())

		if (config.fireFreezeCustomCircleEnabled) {
			val color = parseColor(config.fireFreezeCircleColorHex) ?: DEFAULT_CIRCLE_COLOR
			for (fireFreeze in fireFreezes.values) {
				if (!fireFreeze.hasFinished(now)) {
					drawCircle(circleLineConsumer, entry, fireFreeze.center.add(0.0, 1.0, 0.0), RADIUS, circleLineWidth, color)
				}
			}
		}

		if (config.fireFreezeBoxFrozenMobsEnabled) {
			val world = MinecraftClient.getInstance().world
			for (frozenMob in frozenMobs.values) {
				val entity = world?.getEntityById(frozenMob.entityId) as? LivingEntity ?: continue
				val remainingMs = frozenMob.freezeEndsAtMs - now
				if (remainingMs <= 0L) continue
				val color = refreezeColor(remainingMs)
				drawBoundingBox(entity, fillConsumer, boxLineConsumer, entry, matrices, color)
			}
		}

		(consumers as? VertexConsumerProvider.Immediate)?.draw(XclipsenRenderLayers.getXrayFill())
		(consumers as? VertexConsumerProvider.Immediate)?.draw(circleLineLayer)
		(consumers as? VertexConsumerProvider.Immediate)?.draw(boxLineLayer)
		matrices.pop()
	}

	fun shouldDrawAlert(config: BridgeConfig): Boolean {
		return config.fireFreezeModuleEnabled &&
			config.fireFreezeRefreezeAlertEnabled &&
			currentAlertText.isNotBlank() &&
			System.currentTimeMillis() <= alertVisibleUntil
	}

	fun currentAlertText(): String = currentAlertText

	fun activeFreezeTimers(): List<String> {
		val now = System.currentTimeMillis()
		return fireFreezes.values
			.asSequence()
			.filter { !it.hasFinished(now) }
			.sortedBy { it.freezeAtMs }
			.take(3)
			.map { "Freeze ${formatRemainingMs(it.freezeAtMs - now)}" }
			.toList()
	}

	fun activeMobTimers(): List<String> {
		val now = System.currentTimeMillis()
		return frozenMobs.values
			.asSequence()
			.filter { it.freezeEndsAtMs > now }
			.sortedBy { it.freezeEndsAtMs }
			.take(4)
			.map { "Mob ${formatRemainingMs(it.freezeEndsAtMs - now)}" }
			.toList()
	}

	fun playAlertPreview(config: BridgeConfig) {
		playAlertSound(config)
		showAlert("Refreeze now!")
	}

	fun shouldSuppressEntityRender(state: LivingEntityRenderState): Boolean {
		return shouldSuppressEntityRender(state as EntityRenderState)
	}

	fun shouldSuppressEntityRender(state: EntityRenderState): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		if (!config.fireFreezeModuleEnabled || !config.fireFreezeCustomCircleEnabled || !LocationTracker.isOnHypixelSkyBlock) {
			return false
		}
		if (!isFireFreezeVisualEntity(state)) {
			return false
		}

		val pos = Vec3d(state.x, state.y, state.z)
		return isInsideSuppressedEffectArea(pos, System.currentTimeMillis())
	}

	private fun isFireFreezeVisualEntity(state: EntityRenderState): Boolean {
		return (state.entityType == EntityType.ARMOR_STAND && state.invisible) || state.entityType == EntityType.WITHER_SKULL
	}

	private fun handleActiveSound(pos: Vec3d, volume: Float, pitch: Float) {
		if (volume != 0.2f || pitch !in 0.0f..2.0f) {
			return
		}
		val key = PositionKey.of(pos)
		val current = fireFreezes[key]
		if (current == null) {
			fireFreezes[key] = FireFreezeArea(pos, pitch)
		} else {
			current.updatePitch(pitch)
		}
	}

	private fun handleAnvilSound(pos: Vec3d, volume: Float, pitch: Float) {
		if (volume != 0.6f || pitch != 0.4920635f) {
			return
		}
		val key = PositionKey.of(pos)
		val fireFreeze = fireFreezes[key] ?: return
		suppressedEffectAreas[key] = SuppressedEffectArea(fireFreeze.center, System.currentTimeMillis() + EFFECT_SUPPRESSION_GRACE_MS)
		freezeMobs(fireFreeze)
		fireFreeze.frozen = true
		fireFreezes.remove(key)
	}

	private fun freezeMobs(fireFreeze: FireFreezeArea) {
		val world = MinecraftClient.getInstance().world ?: return
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		val now = System.currentTimeMillis()
		for (entity in world.entities) {
			if (!isFreezableMob(entity, config) || !fireFreeze.isInside(Vec3d(entity.x, entity.y, entity.z), extra = 0.0)) {
				continue
			}
			val living = entity as LivingEntity
			val existing = frozenMobs[living.uuid]
			if (existing != null && existing.freezeEndsAtMs > now) {
				continue
			}
			frozenMobs[living.uuid] = FrozenMob(living.uuid, living.id, now + FREEZE_DURATION_MS, false)
		}
	}

	private fun isFreezableMob(entity: Entity, config: BridgeConfig): Boolean {
		if (entity !is LivingEntity || entity is ArmorStandEntity || !entity.isAlive || entity.isRemoved) {
			return false
		}
		if (entity is PlayerEntity) {
			return if (config.fireFreezeStrongMobsOnly) isStrongMythologicalMob(entity) else isMythologicalMob(entity)
		}
		return !config.fireFreezeStrongMobsOnly
	}

	private fun isMythologicalMob(entity: PlayerEntity): Boolean {
		val name = normalizeEntityName(entity.displayName?.string ?: entity.name.string)
		return MYTHOLOGICAL_MOB_NAMES.any { name.contains(it) }
	}

	private fun isStrongMythologicalMob(entity: PlayerEntity): Boolean {
		val name = normalizeEntityName(entity.displayName?.string ?: entity.name.string)
		return STRONG_MYTHOLOGICAL_MOB_NAMES.any { name.contains(it) }
	}

	private fun normalizeEntityName(name: String): String {
		return SECTION_FORMATTING_PATTERN.replace(name, "")
			.lowercase(Locale.ROOT)
			.replace("[^a-z0-9 ]".toRegex(), " ")
			.replace("\\s+".toRegex(), " ")
			.trim()
	}

	private fun drawBoundingBox(
		entity: LivingEntity,
		fillConsumer: VertexConsumer,
		lineConsumer: VertexConsumer,
		entry: net.minecraft.client.util.math.MatrixStack.Entry,
		matrices: net.minecraft.client.util.math.MatrixStack,
		color: Int,
	) {
		val red = (color shr 16 and 0xFF) / 255.0f
		val green = (color shr 8 and 0xFF) / 255.0f
		val blue = (color and 0xFF) / 255.0f
		val box = entity.boundingBox.expand(0.1)
		XclipsenWorldRenderUtils.drawFilledBox(
			matrices.peek(),
			fillConsumer,
			box.minX.toFloat(),
			box.minY.toFloat(),
			box.minZ.toFloat(),
			box.maxX.toFloat(),
			box.maxY.toFloat(),
			box.maxZ.toFloat(),
			red,
			green,
			blue,
			BOX_FILL_ALPHA,
		)
		XclipsenWorldRenderUtils.drawBox(entry, lineConsumer, box, red, green, blue, BOX_OUTLINE_ALPHA)
	}

	private fun drawCircle(
		consumer: VertexConsumer,
		entry: net.minecraft.client.util.math.MatrixStack.Entry,
		center: Vec3d,
		radius: Double,
		lineWidth: Float,
		color: Int,
	) {
		val red = color shr 16 and 0xFF
		val green = color shr 8 and 0xFF
		val blue = color and 0xFF
		val ringCount = ceil(lineWidth.toDouble()).toInt().coerceIn(1, 8)
		val startOffset = -((ringCount - 1) * CIRCLE_THICKNESS_STEP) / 2.0
		for (ring in 0 until ringCount) {
			drawCircleRing(consumer, entry, center, radius + startOffset + (ring * CIRCLE_THICKNESS_STEP), red, green, blue)
		}
	}

	private fun drawCircleRing(
		consumer: VertexConsumer,
		entry: net.minecraft.client.util.math.MatrixStack.Entry,
		center: Vec3d,
		radius: Double,
		red: Int,
		green: Int,
		blue: Int,
	) {
		var previous = center.add(radius, 0.0, 0.0)
		for (index in 1..CIRCLE_SEGMENTS) {
			val angle = (index.toDouble() / CIRCLE_SEGMENTS) * PI * 2.0
			val next = center.add(cos(angle) * radius, 0.0, sin(angle) * radius)
			drawLineSegment(consumer, entry, previous, next, red, green, blue)
			previous = next
		}
	}

	private fun drawLineSegment(
		consumer: VertexConsumer,
		entry: net.minecraft.client.util.math.MatrixStack.Entry,
		start: Vec3d,
		end: Vec3d,
		red: Int,
		green: Int,
		blue: Int,
	) {
		consumer.vertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat()).color(red, green, blue, 230).normal(entry, 0.0f, 1.0f, 0.0f)
		consumer.vertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat()).color(red, green, blue, 230).normal(entry, 0.0f, 1.0f, 0.0f)
	}

	private fun refreezeColor(remainingMs: Long): Int {
		val percent = (1.0 - ((remainingMs - REFREEZE_THRESHOLD_MS).toDouble() / REFREEZE_THRESHOLD_MS)).coerceIn(0.0, 1.0)
		return blend(YELLOW, RED, percent)
	}

	private fun blend(from: Int, to: Int, percent: Double): Int {
		val inverse = 1.0 - percent
		val red = (((from shr 16 and 0xFF) * inverse) + ((to shr 16 and 0xFF) * percent)).toInt().coerceIn(0, 255)
		val green = (((from shr 8 and 0xFF) * inverse) + ((to shr 8 and 0xFF) * percent)).toInt().coerceIn(0, 255)
		val blue = (((from and 0xFF) * inverse) + ((to and 0xFF) * percent)).toInt().coerceIn(0, 255)
		return (red shl 16) or (green shl 8) or blue
	}

	private fun playAlertSound(config: BridgeConfig) {
		MinecraftClient.getInstance().soundManager.play(
			SoundCatalog.masterSound(
				config.fireFreezeRefreezeAlertSoundId,
				config.fireFreezeRefreezeAlertSoundPitch.coerceIn(0.1f, 2.0f),
				config.fireFreezeRefreezeAlertSoundVolume.coerceIn(0.0f, 2.0f),
			),
		)
	}

	private fun showAlert(text: String) {
		currentAlertText = text
		alertVisibleUntil = System.currentTimeMillis() + ALERT_VISIBLE_MS
	}

	private fun clear() {
		fireFreezes.clear()
		frozenMobs.clear()
		currentAlertText = ""
		alertVisibleUntil = 0L
		tickCounter = 0
		suppressedEffectAreas.clear()
	}

	private fun isInsideSuppressedEffectArea(pos: Vec3d, now: Long): Boolean {
		return fireFreezes.values.any { !it.hasFinished(now) && it.isInside(pos, extra = EFFECT_SUPPRESSION_EXTRA_RADIUS) } ||
			suppressedEffectAreas.values.any { it.expiresAtMs > now && it.isInside(pos) }
	}

	private fun parseColor(hex: String): Int? {
		val candidate = hex.trim().removePrefix("#")
		return if (HEX_COLOR_PATTERN.matches(candidate)) candidate.toInt(16) else null
	}

	private fun formatRemainingMs(remainingMs: Long): String {
		val seconds = remainingMs.coerceAtLeast(0L) / 1000.0
		return if (seconds >= 10.0) "${ceil(seconds).toInt()}s" else String.format(java.util.Locale.ROOT, "%.1fs", ceil(seconds * 10.0) / 10.0)
	}

	private data class PositionKey(val x: Int, val y: Int, val z: Int) {
		companion object {
			fun of(pos: Vec3d): PositionKey = PositionKey((pos.x * 100.0).toInt(), (pos.y * 100.0).toInt(), (pos.z * 100.0).toInt())
		}
	}

	private data class FireFreezeArea(
		val center: Vec3d,
		var lastPitch: Float,
		var startAtMs: Long = System.currentTimeMillis(),
		var freezeAtMs: Long = timeFromPitch(lastPitch),
		var knownTime: Boolean = false,
		var frozen: Boolean = false,
	) {
		fun updatePitch(pitch: Float) {
			if (knownTime || lastPitch == pitch) return
			lastPitch = pitch
			freezeAtMs = timeFromPitch(pitch)
			knownTime = true
		}

		fun hasFinished(now: Long): Boolean = frozen || now > freezeAtMs + 500L

		fun isInside(pos: Vec3d, extra: Double = 0.5): Boolean {
			val dx = center.x - pos.x
			val dz = center.z - pos.z
			return ((dx * dx) + (dz * dz)) < ((RADIUS + extra) * (RADIUS + extra))
		}
	}

	private data class FrozenMob(
		val uuid: UUID,
		val entityId: Int,
		val freezeEndsAtMs: Long,
		var alertTriggered: Boolean,
	)

	private data class SuppressedEffectArea(
		val center: Vec3d,
		val expiresAtMs: Long,
	) {
		fun isInside(pos: Vec3d): Boolean {
			val dx = center.x - pos.x
			val dz = center.z - pos.z
			return ((dx * dx) + (dz * dz)) < ((RADIUS + EFFECT_SUPPRESSION_EXTRA_RADIUS) * (RADIUS + EFFECT_SUPPRESSION_EXTRA_RADIUS))
		}
	}

	private fun timeFromPitch(pitch: Float): Long = System.currentTimeMillis() + ((2.0 * pitch) + 1.0).times(1000.0).toLong()

	private const val PARTICLE_OFFSET = 3.921568568330258E-4
	private const val RADIUS = 5.0
	private const val FREEZE_DURATION_MS = 10_000L
	private const val REFREEZE_THRESHOLD_MS = 5_000L
	private const val EFFECT_SUPPRESSION_GRACE_MS = 1_500L
	private const val EFFECT_SUPPRESSION_EXTRA_RADIUS = 1.5
	private const val CLEANUP_INTERVAL_TICKS = 5
	private const val ALERT_VISIBLE_MS = 2_800L
	private const val CIRCLE_SEGMENTS = 96
	private const val CIRCLE_THICKNESS_STEP = 0.08
	private const val BOX_FILL_ALPHA = 0.18f
	private const val BOX_OUTLINE_ALPHA = 0.9f
	private const val DEFAULT_CIRCLE_COLOR = 0x00F5FF
	private const val YELLOW = 0xFFE066
	private const val RED = 0xFF3030
	private val HEX_COLOR_PATTERN = Regex("[0-9a-fA-F]{6}")
	private val SECTION_FORMATTING_PATTERN = Regex("§.")
	private val MYTHOLOGICAL_MOB_NAMES = listOf(
		"minos hunter",
		"siamese lynx",
		"stranded nymph",
		"cretan bull",
		"harpy",
		"sphinx",
		"minotaur",
		"gaia construct",
		"minos champion",
		"minos inquisitor",
		"manticore",
		"king minos",
	)
	private val STRONG_MYTHOLOGICAL_MOB_NAMES = listOf(
		"minos inquisitor",
		"king minos",
	)
}

object FireFreezeTimersHudElement : XclipsenHudElement(
	id = "fire_freeze_timers",
	displayName = "Fire Freeze Timers",
) {
	override fun isEnabled(config: BridgeConfig): Boolean =
		config.fireFreezeModuleEnabled && (config.fireFreezeMobTimerEnabled || config.fireFreezeFreezeTimerEnabled)

	override fun shouldDraw(config: BridgeConfig): Boolean {
		return isEnabled(config) && (FireFreezeFeature.activeFreezeTimers().isNotEmpty() || FireFreezeFeature.activeMobTimers().isNotEmpty())
	}

	override fun defaultX(context: DrawContext): Float = 20f

	override fun defaultY(context: DrawContext): Float = 112f

	override fun draw(context: DrawContext, example: Boolean): Pair<Float, Float> {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: BridgeConfig()
		val lines = if (example) {
			listOf("Freeze 2.3s", "Mob 4.8s")
		} else {
			buildList {
				if (config.fireFreezeFreezeTimerEnabled) addAll(FireFreezeFeature.activeFreezeTimers())
				if (config.fireFreezeMobTimerEnabled) addAll(FireFreezeFeature.activeMobTimers())
			}
		}
		if (lines.isEmpty()) return 110f to 22f
		val renderer = MinecraftClient.getInstance().textRenderer
		val width = lines.maxOf { renderer.getWidth(it) }.coerceAtLeast(98) + 12
		val height = 8 + (lines.size * (renderer.fontHeight + 2))
		context.fill(0, 0, width, height, 0xB4121212.toInt())
		context.fill(0, 0, width, 1, 0xFF00F5FF.toInt())
		var y = 5
		for (line in lines) {
			context.drawTextWithShadow(renderer, line, 6, y, 0xFFE8FFFF.toInt())
			y += renderer.fontHeight + 2
		}
		return width.toFloat() to height.toFloat()
	}
}

object FireFreezeRefreezeAlertHudElement : XclipsenHudElement(
	id = "fire_freeze_refreeze_alert",
	displayName = "Fire Freeze Refreeze Alert",
) {
	override fun isEnabled(config: BridgeConfig): Boolean = config.fireFreezeModuleEnabled && config.fireFreezeRefreezeAlertEnabled

	override fun shouldDraw(config: BridgeConfig): Boolean = isEnabled(config) && FireFreezeFeature.shouldDrawAlert(config)

	override fun defaultX(context: DrawContext): Float = ((context.scaledWindowWidth - 170) / 2f).coerceAtLeast(4f)

	override fun defaultY(context: DrawContext): Float = (context.scaledWindowHeight * 0.28f).coerceAtLeast(24f)

	override fun draw(context: DrawContext, example: Boolean): Pair<Float, Float> {
		val renderer = MinecraftClient.getInstance().textRenderer
		val text = if (example) "Refreeze now!" else FireFreezeFeature.currentAlertText()
		val width = (renderer.getWidth(text) + 18).coerceAtLeast(170)
		val height = renderer.fontHeight + 12
		context.fill(0, 0, width, height, 0xC0181818.toInt())
		context.fill(0, 0, width, 1, 0xFFFF3030.toInt())
		context.fill(0, height - 1, width, height, 0xFFFF3030.toInt())
		context.fill(3, 3, width - 3, height - 3, 0x40FF3030)
		context.drawCenteredTextWithShadow(renderer, text, width / 2, 6, 0xFFFFFFFF.toInt())
		return width.toFloat() to height.toFloat()
	}
}
