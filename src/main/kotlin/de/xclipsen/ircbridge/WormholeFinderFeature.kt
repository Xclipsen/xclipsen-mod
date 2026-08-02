package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.world.entity.Display
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.network.chat.Component
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

object WormholeFinderFeature {
	private const val RESCAN_INTERVAL_TICKS = 10
	private const val DIRECTION_TOLERANCE = 0.98
	private const val ARROW_SCAN_RADIUS = 3.0
	private const val DEFAULT_COLOR = 0xFF55FF
	private const val RING_SHADOW_COLOR = 0x260026
	private const val RING_LINE_WIDTH = 7.0
	private const val RING_SHADOW_LINE_WIDTH = 11.0
	private const val TRACER_LINE_WIDTH = 7.0
	private const val TRACER_SHADOW_LINE_WIDTH = 11.0
	private const val RING_RADIUS = 1.32
	private const val WATER_SURFACE_OFFSET = 0.04
	private const val ARRIVAL_RADIUS = 2.25
	private const val CIRCLE_SEGMENTS = 128
	private const val CIRCLE_LINE_ALPHA = 235
	private const val TRACER_LINE_ALPHA = 245
	private const val CROSSHAIR_OFFSET = 2.0
	private const val ALERT_VISIBLE_MS = 3_500L
	private const val ALERT_DEDUPE_MS = 1_500L
	private const val CLOSED_MESSAGE = "your wormhole closed up"

	private var activeWormhole: WormholeNode? = null
	private var tracerTarget: WormholeNode? = null
	private var arrivedTarget: BlockPos? = null
	private var ticksUntilRescan = 0
	private var alertVisibleUntil = 0L
	private var lastDepartureAlertAt = 0L

	fun onTick(client: Minecraft) {
		val config = XclipsenIrcBridgeClient.instance?.config()
		val world = client.level
		val player = client.player
		if (
			config == null ||
			world == null ||
			player == null ||
			!config.wormholeFinderModuleEnabled ||
			!LocationTracker.isOnHypixelSkyBlock
		) {
			clear()
			return
		}

		if (ticksUntilRescan-- > 0) {
			return
		}

		ticksUntilRescan = RESCAN_INTERVAL_TICKS
		val candidates = candidateWormholes(Vec3(player.x, player.y, player.z))
		if (candidates.isEmpty()) {
			return
		}

		val arrows = world.getEntities(
			EntityTypeTest.forClass(Display.TextDisplay::class.java),
			player.boundingBox.inflate(ARROW_SCAN_RADIUS),
		) { true }
		if (arrows.isEmpty()) {
			return
		}

		val matched = arrows.mapNotNull { matchArrow(it, candidates) }.distinct()
		val nearest = matched.minByOrNull {
			val dx = player.x - it.x
			val dy = player.y - it.y
			val dz = player.z - it.z
			dx * dx + dy * dy + dz * dz
		}
		if (nearest != null) {
			activeWormhole = nearest
			if (isArrivedAt(playerPos = Vec3(player.x, player.y, player.z), target = nearest)) {
				tracerTarget = null
				arrivedTarget = nearest.blockPos
			} else if (arrivedTarget != nearest.blockPos) {
				tracerTarget = nearest
			}
		}
	}

	fun onIncomingMessage(message: Component?) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.wormholeFinderModuleEnabled) {
			return
		}

		val normalized = normalizeMessage(message?.string ?: return)
		if (!normalized.contains(CLOSED_MESSAGE)) {
			return
		}

		val closedWormholes = setOfNotNull(activeWormhole?.blockPos)
		activeWormhole = null
		arrivedTarget = null
		tracerTarget = nearestWormholeAfterClose(closedWormholes)
		if (!config.wormholeDepartureAlertEnabled) {
			return
		}
		if (!LocationTracker.isOnHypixelSkyBlock) {
			return
		}

		val now = System.currentTimeMillis()
		if (now - lastDepartureAlertAt < ALERT_DEDUPE_MS) {
			return
		}
		lastDepartureAlertAt = now
		triggerDepartureAlert(config)
	}

	fun triggerDepartureAlert(config: BridgeConfig? = null) {
		val value = config ?: XclipsenIrcBridgeClient.instance?.config() ?: return
		val client = Minecraft.getInstance()
		client.execute {
			alertVisibleUntil = System.currentTimeMillis() + ALERT_VISIBLE_MS
			client.soundManager.play(
				SoundCatalog.masterSound(
					value.wormholeDepartureAlertSoundId,
					value.wormholeDepartureAlertSoundPitch.coerceIn(0.1f, 2.0f),
					value.wormholeDepartureAlertSoundVolume.coerceIn(0.0f, 2.0f),
				),
			)
		}
	}

	fun shouldDrawAlert(config: BridgeConfig): Boolean {
		return config.wormholeFinderModuleEnabled &&
			config.wormholeDepartureAlertEnabled &&
			System.currentTimeMillis() <= alertVisibleUntil
	}

	fun onWorldChange() {
		clear()
		ticksUntilRescan = 0
	}

	fun statusLine(): String {
		val enabled = XclipsenIrcBridgeClient.instance?.config()?.wormholeFinderModuleEnabled == true
		return "enabled=$enabled, skyblock=${LocationTracker.isOnHypixelSkyBlock}, active=${activeWormhole != null}, " +
			"tracerTarget=${tracerTarget != null}, arrived=${arrivedTarget != null}"
	}

	fun render(context: LevelRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		val active = activeWormhole
		val target = tracerTarget
		if (!config.wormholeFinderModuleEnabled || !LocationTracker.isOnHypixelSkyBlock || (active == null && target == null)) {
			return
		}

		val cameraPos = context.levelState().cameraRenderState.pos
		val matrices = context.poseStack()
		val consumers = context.bufferSource()

		matrices.pushPose()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.last()
		val shadowRingLayer = XclipsenRenderLayers.getXrayLine(RING_SHADOW_LINE_WIDTH)
		val ringLayer = XclipsenRenderLayers.getXrayLine(RING_LINE_WIDTH)
		val shadowTracerLayer = XclipsenRenderLayers.getXrayLine(TRACER_SHADOW_LINE_WIDTH)
		val tracerLayer = XclipsenRenderLayers.getXrayLine(TRACER_LINE_WIDTH)
		val shadowRingConsumer = consumers.getBuffer(shadowRingLayer)
		val ringConsumer = consumers.getBuffer(ringLayer)
		val shadowTracerConsumer = consumers.getBuffer(shadowTracerLayer)
		val tracerConsumer = consumers.getBuffer(tracerLayer)

		if (active != null) {
			drawWaterSurfaceRing(shadowRingConsumer, entry, active.waterSurfaceCenter, RING_SHADOW_COLOR, 210, RING_SHADOW_LINE_WIDTH.toFloat())
			drawWaterSurfaceRing(ringConsumer, entry, active.waterSurfaceCenter, DEFAULT_COLOR, CIRCLE_LINE_ALPHA, RING_LINE_WIDTH.toFloat())
		}
		if (target != null) {
			drawTracer(shadowTracerConsumer, entry, cameraPos, target.middleBlockCenter, RING_SHADOW_COLOR, 225, TRACER_SHADOW_LINE_WIDTH.toFloat())
			drawTracer(tracerConsumer, entry, cameraPos, target.middleBlockCenter, DEFAULT_COLOR, TRACER_LINE_ALPHA, TRACER_LINE_WIDTH.toFloat())
			if (target != active) {
				drawWaterSurfaceRing(shadowRingConsumer, entry, target.waterSurfaceCenter, RING_SHADOW_COLOR, 190, RING_SHADOW_LINE_WIDTH.toFloat())
				drawWaterSurfaceRing(ringConsumer, entry, target.waterSurfaceCenter, DEFAULT_COLOR, 220, RING_LINE_WIDTH.toFloat())
			}
		}

		consumers.endBatch(shadowRingLayer)
		consumers.endBatch(ringLayer)
		consumers.endBatch(shadowTracerLayer)
		consumers.endBatch(tracerLayer)
		matrices.popPose()
	}

	private fun clear() {
		activeWormhole = null
		tracerTarget = null
		arrivedTarget = null
		alertVisibleUntil = 0L
	}

	private fun normalizeMessage(raw: String): String {
		return raw.replace(SECTION_FORMATTING_PATTERN, "")
			.replace("&[0-9a-fk-or]".toRegex(RegexOption.IGNORE_CASE), "")
			.lowercase()
			.replace("\\s+".toRegex(), " ")
			.trim()
	}

	private fun matchArrow(arrow: Display.TextDisplay, candidates: List<WormholeNode>): WormholeNode? {
		val origin = Vec3(arrow.x, arrow.y, arrow.z)
		val forward = arrowForwardVec(arrow)
		return candidates.asSequence()
			.mapNotNull { node ->
				val horizontal = Vec3(node.x - origin.x, 0.0, node.z - origin.z).normalizeOrNull() ?: return@mapNotNull null
				node to forward.dot(horizontal)
			}
			.filter { (_, score) -> score >= DIRECTION_TOLERANCE }
			.maxByOrNull { (_, score) -> score }
			?.first
	}

	private fun arrowForwardVec(arrow: Display.TextDisplay): Vec3 {
		return try {
			val renderState = arrow.renderState() ?: return yawForwardVec(arrow.yRot)
			val transformation = renderState.transformation().get(0.0f)
			val localY = Vector3f(0.0f, 1.0f, 0.0f)
			transformation.leftRotation().transform(localY)
			Vec3(localY.x.toDouble(), 0.0, localY.z.toDouble()).normalizeOrNull()
		} catch (_: RuntimeException) {
			null
		} ?: yawForwardVec(arrow.yRot)
	}

	private fun yawForwardVec(yawDegrees: Float): Vec3 {
		val yaw = Math.toRadians(yawDegrees.toDouble())
		return Vec3(-kotlin.math.sin(yaw), 0.0, kotlin.math.cos(yaw)).normalizeOrNull() ?: Vec3(0.0, 0.0, 1.0)
	}

	private fun Vec3.normalizeOrNull(): Vec3? {
		val lengthSquared = lengthSqr()
		if (!x.isFinite() || !y.isFinite() || !z.isFinite() || lengthSquared < 0.000001) {
			return null
		}
		val length = sqrt(lengthSquared)
		return Vec3(x / length, y / length, z / length)
	}

	private fun candidateWormholes(playerPos: Vec3): List<WormholeNode> {
		val area = LocationTracker.currentArea
		if (area.contains("lotus", ignoreCase = true) || isNearAny(playerPos, LOTUS_ATOLL_WORMHOLES, 180.0)) {
			return LOTUS_ATOLL_WORMHOLES
		}
		if (area.contains("crimson", ignoreCase = true) || isNearAny(playerPos, CRIMSON_ISLE_WORMHOLES, 360.0)) {
			return CRIMSON_ISLE_WORMHOLES
		}
		return emptyList()
	}

	private fun nearestWormholeAfterClose(closedWormholes: Set<BlockPos>): WormholeNode? {
		val player = Minecraft.getInstance().player ?: return null
		val playerPos = Vec3(player.x, player.y, player.z)
		val candidates = candidateWormholes(playerPos)
			.filterNot { it.blockPos in closedWormholes }
		return candidates.minByOrNull { it.center.distanceToSqr(playerPos) }
	}

	private fun isArrivedAt(playerPos: Vec3, target: WormholeNode): Boolean {
		val dx = playerPos.x - target.middleBlockCenter.x
		val dz = playerPos.z - target.middleBlockCenter.z
		return dx * dx + dz * dz <= ARRIVAL_RADIUS * ARRIVAL_RADIUS
	}

	private fun isNearAny(playerPos: Vec3, nodes: List<WormholeNode>, maxHorizontalDistance: Double): Boolean {
		val maxDistanceSq = maxHorizontalDistance * maxHorizontalDistance
		return nodes.any { node ->
			val dx = playerPos.x - node.x
			val dz = playerPos.z - node.z
			(dx * dx + dz * dz) <= maxDistanceSq
		}
	}

	private fun drawWaterSurfaceRing(
		lineConsumer: VertexConsumer,
		entry: com.mojang.blaze3d.vertex.PoseStack.Pose,
		center: Vec3,
		color: Int,
		alpha: Int,
		lineWidth: Float,
	) {
		val (red, green, blue) = ClientColor.rgbChannels(color)
		drawCircleRing(lineConsumer, entry, center, RING_RADIUS, red, green, blue, alpha, lineWidth)
	}

	private fun drawCircleRing(
		consumer: VertexConsumer,
		entry: com.mojang.blaze3d.vertex.PoseStack.Pose,
		center: Vec3,
		radius: Double,
		red: Int,
		green: Int,
		blue: Int,
		alpha: Int,
		lineWidth: Float,
	) {
		var previous = center.add(radius, 0.0, 0.0)
		for (index in 1..CIRCLE_SEGMENTS) {
			val angle = (index.toDouble() / CIRCLE_SEGMENTS) * PI * 2.0
			val next = center.add(cos(angle) * radius, 0.0, sin(angle) * radius)
			drawLineSegment(consumer, entry, previous, next, red, green, blue, alpha, lineWidth)
			previous = next
		}
	}

	private fun drawLineSegment(
		consumer: VertexConsumer,
		entry: com.mojang.blaze3d.vertex.PoseStack.Pose,
		start: Vec3,
		end: Vec3,
		red: Int,
		green: Int,
		blue: Int,
		alpha: Int,
		lineWidth: Float,
	) {
		consumer.addVertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.setColor(red, green, blue, alpha)
			.setNormal(entry, 0.0f, 1.0f, 0.0f)
			.setLineWidth(lineWidth)
		consumer.addVertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
			.setColor(red, green, blue, alpha)
			.setNormal(entry, 0.0f, 1.0f, 0.0f)
			.setLineWidth(lineWidth)
	}

	private fun drawTracer(
		consumer: VertexConsumer,
		entry: com.mojang.blaze3d.vertex.PoseStack.Pose,
		cameraPos: Vec3,
		target: Vec3,
		color: Int,
		alpha: Int,
		lineWidth: Float,
	) {
		val start = crosshairStart(cameraPos)
		val delta = target.subtract(start)
		val lengthSquared = delta.lengthSqr()
		if (!delta.x.isFinite() || !delta.y.isFinite() || !delta.z.isFinite() || lengthSquared < 0.0001) {
			return
		}
		val length = sqrt(lengthSquared)
		val normalX = (delta.x / length).toFloat()
		val normalY = (delta.y / length).toFloat()
		val normalZ = (delta.z / length).toFloat()
		val (red, green, blue) = ClientColor.rgbChannels(color)
		consumer.addVertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.setColor(red, green, blue, alpha)
			.setNormal(entry, normalX, normalY, normalZ)
			.setLineWidth(lineWidth)
		consumer.addVertex(entry, target.x.toFloat(), target.y.toFloat(), target.z.toFloat())
			.setColor(red, green, blue, alpha)
			.setNormal(entry, normalX, normalY, normalZ)
			.setLineWidth(lineWidth)
	}

	private fun crosshairStart(cameraPos: Vec3): Vec3 {
		val client = Minecraft.getInstance()
		val viewEntity = client.cameraEntity ?: client.player ?: return cameraPos
		val yawRadians = Math.toRadians(viewEntity.yRot.toDouble())
		val pitchRadians = Math.toRadians(viewEntity.xRot.toDouble())
		val horizontalScale = cos(pitchRadians)
		val look = Vec3(
			-sin(yawRadians) * horizontalScale,
			-sin(pitchRadians),
			cos(yawRadians) * horizontalScale,
		)
		return cameraPos.add(
			look.x * CROSSHAIR_OFFSET,
			look.y * CROSSHAIR_OFFSET,
			look.z * CROSSHAIR_OFFSET,
		)
	}

	private data class WormholeNode(val x: Double, val y: Double, val z: Double) {
		val blockPos: BlockPos = BlockPos(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())
		val center: Vec3 = Vec3(x + 0.5, y + 0.5, z + 0.5)
		val middleBlockCenter: Vec3 = Vec3(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
		val waterSurfaceCenter: Vec3 = Vec3(blockPos.x + 0.5, blockPos.y + 1.0 + WATER_SURFACE_OFFSET, blockPos.z + 0.5)
	}

	private val LOTUS_ATOLL_WORMHOLES = listOf(
		WormholeNode(45.0, 66.0, -10.0),
		WormholeNode(36.0, 66.0, 6.0),
		WormholeNode(79.0, 91.0, 2.0),
		WormholeNode(68.0, 61.0, -7.0),
		WormholeNode(13.0, 66.0, -2.0),
		WormholeNode(42.0, 66.0, 19.0),
		WormholeNode(37.0, 70.0, -34.0),
		WormholeNode(92.0, 79.0, -4.0),
		WormholeNode(84.0, 91.0, -4.0),
		WormholeNode(14.0, 74.0, 29.0),
		WormholeNode(26.0, 66.0, -10.0),
		WormholeNode(25.0, 66.0, 4.0),
		WormholeNode(17.0, 66.0, -13.0),
		WormholeNode(73.0, 61.0, 7.0),
		WormholeNode(79.0, 61.0, -19.0),
		WormholeNode(99.0, 79.0, -4.0),
		WormholeNode(36.0, 66.0, -20.0),
		WormholeNode(63.0, 66.0, -29.0),
		WormholeNode(67.0, 66.0, 20.0),
		WormholeNode(54.0, 66.0, -20.0),
		WormholeNode(25.0, 66.0, 20.0),
		WormholeNode(52.0, 66.0, 5.0),
		WormholeNode(18.0, 66.0, 4.0),
	)

	private val CRIMSON_ISLE_WORMHOLES = listOf(
		WormholeNode(-387.0, 171.0, -495.0),
		WormholeNode(-449.0, 98.0, -728.0),
		WormholeNode(-466.0, 91.0, -821.0),
		WormholeNode(-277.0, 98.0, -750.0),
		WormholeNode(-620.0, 154.0, -807.0),
		WormholeNode(-426.0, 74.0, -566.0),
		WormholeNode(-360.0, 74.0, -562.0),
		WormholeNode(-309.0, 105.0, -574.0),
		WormholeNode(-372.0, 118.0, -817.0),
		WormholeNode(-248.0, 73.0, -727.0),
		WormholeNode(-348.0, 139.0, -809.0),
		WormholeNode(-460.0, 98.0, -746.0),
		WormholeNode(-216.0, 106.5, -567.0),
		WormholeNode(-345.0, 118.0, -790.0),
		WormholeNode(-296.0, 94.0, -853.0),
		WormholeNode(-424.0, 94.0, -864.0),
	)

	private val SECTION_FORMATTING_PATTERN = Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE)
}

object WormholeDepartureAlertHudElement : XclipsenHudElement(
	id = "wormhole_departure_alert",
	displayName = "Wormhole Closed Alert",
) {
	override fun isEnabled(config: BridgeConfig): Boolean =
		config.wormholeFinderModuleEnabled && config.wormholeDepartureAlertEnabled

	override fun shouldDraw(config: BridgeConfig): Boolean =
		WormholeFinderFeature.shouldDrawAlert(config)

	override fun defaultX(context: GuiGraphicsExtractor): Float =
		((context.guiWidth() - DEFAULT_WIDTH) / 2f).coerceAtLeast(4f)

	override fun defaultY(context: GuiGraphicsExtractor): Float =
		(context.guiHeight() * 0.26f).coerceAtLeast(24f)

	override fun draw(context: GuiGraphicsExtractor, example: Boolean): Tuple<Float, Float> {
		val renderer = Minecraft.getInstance().font
		val message = alertMessage()
		val width = (renderer.width(message) + (PADDING_X * 2)).coerceAtLeast(DEFAULT_WIDTH)
		val height = PADDING_Y + renderer.lineHeight + PADDING_Y
		context.text(renderer, message, (width - renderer.width(message)) / 2, PADDING_Y, 0xFFFFFFFF.toInt(), true)
		return width.toFloat() to height.toFloat()
	}

	private fun alertMessage(): Component {
		return Component.literal("Your ")
			.withColor(0xAA55AA)
			.append(Component.literal("Wormhole").withColor(0xFF55FF))
			.append(Component.literal(" closed up...").withColor(0xAA55AA))
	}

	private const val DEFAULT_WIDTH = 210
	private const val PADDING_X = 10
	private const val PADDING_Y = 7
}
