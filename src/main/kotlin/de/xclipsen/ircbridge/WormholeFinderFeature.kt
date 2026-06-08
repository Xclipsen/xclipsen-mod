package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
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

	fun onTick(client: MinecraftClient) {
		val config = XclipsenIrcBridgeClient.instance?.config()
		val world = client.world
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
		val candidates = candidateWormholes(Vec3d(player.x, player.y, player.z))
		if (candidates.isEmpty()) {
			return
		}

		val arrows = world.getEntitiesByClass(
			DisplayEntity.TextDisplayEntity::class.java,
			player.boundingBox.expand(ARROW_SCAN_RADIUS),
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
			if (isArrivedAt(playerPos = Vec3d(player.x, player.y, player.z), target = nearest)) {
				tracerTarget = null
				arrivedTarget = nearest.blockPos
			} else if (arrivedTarget != nearest.blockPos) {
				tracerTarget = nearest
			}
		}
	}

	fun onIncomingMessage(message: Text?) {
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
		val client = MinecraftClient.getInstance()
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

	fun render(context: WorldRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		val active = activeWormhole
		val target = tracerTarget
		if (!config.wormholeFinderModuleEnabled || !LocationTracker.isOnHypixelSkyBlock || (active == null && target == null)) {
			return
		}

		val cameraPos = context.gameRenderer().camera.cameraPos
		val matrices = context.matrices()
		val consumers = context.consumers()

		matrices.push()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.peek()
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

		(consumers as? VertexConsumerProvider.Immediate)?.draw(shadowRingLayer)
		(consumers as? VertexConsumerProvider.Immediate)?.draw(ringLayer)
		(consumers as? VertexConsumerProvider.Immediate)?.draw(shadowTracerLayer)
		(consumers as? VertexConsumerProvider.Immediate)?.draw(tracerLayer)
		matrices.pop()
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

	private fun matchArrow(arrow: DisplayEntity.TextDisplayEntity, candidates: List<WormholeNode>): WormholeNode? {
		val origin = Vec3d(arrow.x, arrow.y, arrow.z)
		val forward = arrowForwardVec(arrow)
		return candidates.asSequence()
			.mapNotNull { node ->
				val horizontal = Vec3d(node.x - origin.x, 0.0, node.z - origin.z).normalizeOrNull() ?: return@mapNotNull null
				node to forward.dotProduct(horizontal)
			}
			.filter { (_, score) -> score >= DIRECTION_TOLERANCE }
			.maxByOrNull { (_, score) -> score }
			?.first
	}

	private fun arrowForwardVec(arrow: DisplayEntity.TextDisplayEntity): Vec3d {
		return try {
			val renderState = arrow.renderState ?: return yawForwardVec(arrow.yaw)
			val transformation = renderState.transformation.interpolate(0.0f)
			val localY = Vector3f(0.0f, 1.0f, 0.0f)
			transformation.leftRotation.transform(localY)
			Vec3d(localY.x.toDouble(), 0.0, localY.z.toDouble()).normalizeOrNull()
		} catch (_: RuntimeException) {
			null
		} ?: yawForwardVec(arrow.yaw)
	}

	private fun yawForwardVec(yawDegrees: Float): Vec3d {
		val yaw = Math.toRadians(yawDegrees.toDouble())
		return Vec3d(-kotlin.math.sin(yaw), 0.0, kotlin.math.cos(yaw)).normalizeOrNull() ?: Vec3d(0.0, 0.0, 1.0)
	}

	private fun Vec3d.normalizeOrNull(): Vec3d? {
		val lengthSquared = lengthSquared()
		if (!x.isFinite() || !y.isFinite() || !z.isFinite() || lengthSquared < 0.000001) {
			return null
		}
		val length = sqrt(lengthSquared)
		return Vec3d(x / length, y / length, z / length)
	}

	private fun candidateWormholes(playerPos: Vec3d): List<WormholeNode> {
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
		val player = MinecraftClient.getInstance().player ?: return null
		val playerPos = Vec3d(player.x, player.y, player.z)
		val candidates = candidateWormholes(playerPos)
			.filterNot { it.blockPos in closedWormholes }
		return candidates.minByOrNull { it.center.squaredDistanceTo(playerPos) }
	}

	private fun isArrivedAt(playerPos: Vec3d, target: WormholeNode): Boolean {
		val dx = playerPos.x - target.middleBlockCenter.x
		val dz = playerPos.z - target.middleBlockCenter.z
		return dx * dx + dz * dz <= ARRIVAL_RADIUS * ARRIVAL_RADIUS
	}

	private fun isNearAny(playerPos: Vec3d, nodes: List<WormholeNode>, maxHorizontalDistance: Double): Boolean {
		val maxDistanceSq = maxHorizontalDistance * maxHorizontalDistance
		return nodes.any { node ->
			val dx = playerPos.x - node.x
			val dz = playerPos.z - node.z
			(dx * dx + dz * dz) <= maxDistanceSq
		}
	}

	private fun drawWaterSurfaceRing(
		lineConsumer: VertexConsumer,
		entry: net.minecraft.client.util.math.MatrixStack.Entry,
		center: Vec3d,
		color: Int,
		alpha: Int,
		lineWidth: Float,
	) {
		val red = color shr 16 and 0xFF
		val green = color shr 8 and 0xFF
		val blue = color and 0xFF
		drawCircleRing(lineConsumer, entry, center, RING_RADIUS, red, green, blue, alpha, lineWidth)
	}

	private fun drawCircleRing(
		consumer: VertexConsumer,
		entry: net.minecraft.client.util.math.MatrixStack.Entry,
		center: Vec3d,
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
		entry: net.minecraft.client.util.math.MatrixStack.Entry,
		start: Vec3d,
		end: Vec3d,
		red: Int,
		green: Int,
		blue: Int,
		alpha: Int,
		lineWidth: Float,
	) {
		consumer.vertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.color(red, green, blue, alpha)
			.normal(entry, 0.0f, 1.0f, 0.0f)
			.lineWidth(lineWidth)
		consumer.vertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
			.color(red, green, blue, alpha)
			.normal(entry, 0.0f, 1.0f, 0.0f)
			.lineWidth(lineWidth)
	}

	private fun drawTracer(
		consumer: VertexConsumer,
		entry: net.minecraft.client.util.math.MatrixStack.Entry,
		cameraPos: Vec3d,
		target: Vec3d,
		color: Int,
		alpha: Int,
		lineWidth: Float,
	) {
		val start = crosshairStart(cameraPos)
		val delta = target.subtract(start)
		val lengthSquared = delta.lengthSquared()
		if (!delta.x.isFinite() || !delta.y.isFinite() || !delta.z.isFinite() || lengthSquared < 0.0001) {
			return
		}
		val length = sqrt(lengthSquared)
		val normalX = (delta.x / length).toFloat()
		val normalY = (delta.y / length).toFloat()
		val normalZ = (delta.z / length).toFloat()
		val red = color shr 16 and 0xFF
		val green = color shr 8 and 0xFF
		val blue = color and 0xFF
		consumer.vertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.color(red, green, blue, alpha)
			.normal(entry, normalX, normalY, normalZ)
			.lineWidth(lineWidth)
		consumer.vertex(entry, target.x.toFloat(), target.y.toFloat(), target.z.toFloat())
			.color(red, green, blue, alpha)
			.normal(entry, normalX, normalY, normalZ)
			.lineWidth(lineWidth)
	}

	private fun crosshairStart(cameraPos: Vec3d): Vec3d {
		val client = MinecraftClient.getInstance()
		val viewEntity = client.cameraEntity ?: client.player ?: return cameraPos
		val yawRadians = Math.toRadians(viewEntity.yaw.toDouble())
		val pitchRadians = Math.toRadians(viewEntity.pitch.toDouble())
		val horizontalScale = cos(pitchRadians)
		val look = Vec3d(
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
		val center: Vec3d = Vec3d(x + 0.5, y + 0.5, z + 0.5)
		val middleBlockCenter: Vec3d = Vec3d(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
		val waterSurfaceCenter: Vec3d = Vec3d(blockPos.x + 0.5, blockPos.y + 1.0 + WATER_SURFACE_OFFSET, blockPos.z + 0.5)
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

	override fun defaultX(context: DrawContext): Float =
		((context.scaledWindowWidth - DEFAULT_WIDTH) / 2f).coerceAtLeast(4f)

	override fun defaultY(context: DrawContext): Float =
		(context.scaledWindowHeight * 0.26f).coerceAtLeast(24f)

	override fun draw(context: DrawContext, example: Boolean): Pair<Float, Float> {
		val renderer = MinecraftClient.getInstance().textRenderer
		val message = alertMessage()
		val width = (renderer.getWidth(message) + (PADDING_X * 2)).coerceAtLeast(DEFAULT_WIDTH)
		val height = PADDING_Y + renderer.fontHeight + PADDING_Y
		drawPanel(context, renderer, message, width, height)
		return width.toFloat() to height.toFloat()
	}

	private fun alertMessage(): Text {
		return Text.literal("Your ")
			.withColor(0xAA55AA)
			.append(Text.literal("Wormhole").withColor(0xFF55FF))
			.append(Text.literal(" closed up...").withColor(0xAA55AA))
	}

	private fun drawPanel(
		context: DrawContext,
		renderer: TextRenderer,
		text: Text,
		width: Int,
		height: Int,
	) {
		context.fill(0, 0, width, height, 0xC018101C.toInt())
		context.fill(0, 0, width, 1, 0xFFFF55FF.toInt())
		context.fill(0, height - 1, width, height, 0xFFFF55FF.toInt())
		context.fill(0, 0, 1, height, 0xFFFF55FF.toInt())
		context.fill(width - 1, 0, width, height, 0xFFFF55FF.toInt())
		context.fill(3, 3, width - 3, height - 3, 0x45AA55AA)
		context.drawCenteredTextWithShadow(renderer, text, width / 2, PADDING_Y, 0xFFFFFFFF.toInt())
	}

	private const val DEFAULT_WIDTH = 210
	private const val PADDING_X = 10
	private const val PADDING_Y = 7
}
