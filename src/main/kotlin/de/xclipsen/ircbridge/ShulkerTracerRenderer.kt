package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.world.entity.monster.Shulker
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

object ShulkerTracerRenderer {
	private val completedShulkerIds = LinkedHashSet<UUID>()
	private var lastWorld: ClientLevel? = null

	fun render(context: LevelRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		val lineMode = config.shulkerTracerLineMode.coerceIn(0, 3)
		if (!config.hideonleafHelperEnabled || lineMode == 0) {
			return
		}

		val client = Minecraft.getInstance()
		val world = client.level ?: return
		if (client.player == null || client.options.hideGui) {
			return
		}

		val cameraPos = context.levelState().cameraRenderState.pos
		val availableShulkers = currentAvailableShulkers(world)
			.filterNot { completedShulkerIds.contains(it.id) }
		val shulkerPath = buildNearestShulkerPath(cameraPos, availableShulkers, lineMode)
		if (shulkerPath.isEmpty()) {
			return
		}
		val shulkerCenters = shulkerPath.map { it.center }

		val color = ClientColor.parseRgb(config.shulkerTracerLineColorHex) ?: DEFAULT_LINE_COLOR
		val start = crosshairStart(cameraPos)
		val lineWidth = config.shulkerTracerLineWidth.coerceIn(1.0f, 8.0f)
		var previous = start
		for (center in shulkerCenters) {
			drawLine(context, cameraPos, previous, center, color, lineWidth)
			previous = center
		}
	}

	fun markCurrentTargetCompleted() {
		val client = Minecraft.getInstance()
		val world = client.level ?: return
		val cameraPos = client.gameRenderer.mainCamera.position()
		val nextTarget = buildNearestShulkerPath(
			cameraPos,
			currentAvailableShulkers(world).filterNot { completedShulkerIds.contains(it.id) },
			1,
		).firstOrNull() ?: return

		completedShulkerIds += nextTarget.id
	}

	private fun currentAvailableShulkers(world: ClientLevel): List<ShulkerTarget> {
		if (world !== lastWorld) {
			completedShulkerIds.clear()
			lastWorld = world
		}

		val available = world.entitiesForRendering()
			.asSequence()
			.filterIsInstance<Shulker>()
			.filter { it.isAlive && !it.isRemoved }
			.map { ShulkerTarget(it.uuid, it.boundingBox.center) }
			.toList()
		return available
	}

	private fun buildNearestShulkerPath(start: Vec3, availableShulkers: List<ShulkerTarget>, maxCount: Int): List<ShulkerTarget> {
		val remaining = availableShulkers.toMutableList()
		val path = ArrayList<ShulkerTarget>(maxCount.coerceAtMost(remaining.size))
		var currentOrigin = start

		while (path.size < maxCount && remaining.isNotEmpty()) {
			val next = remaining.minByOrNull { it.center.distanceToSqr(currentOrigin) } ?: break
			path += next
			remaining -= next
			currentOrigin = next.center
		}

		return path
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

	private fun drawLine(context: LevelRenderContext, cameraPos: Vec3, start: Vec3, end: Vec3, color: Int, width: Float) {
		val matrices = context.poseStack()
		val delta = end.subtract(start)
		val lengthSquared = delta.lengthSqr()
		if (!delta.x.isFinite() || !delta.y.isFinite() || !delta.z.isFinite() || lengthSquared < 0.0001) {
			return
		}
		val length = kotlin.math.sqrt(lengthSquared)
		val normalX = (delta.x / length).toFloat()
		val normalY = (delta.y / length).toFloat()
		val normalZ = (delta.z / length).toFloat()

		val (red, green, blue) = ClientColor.rgbChannels(color)
		val alpha = 230

		matrices.pushPose()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.last()
		val renderLayer = XclipsenRenderLayers.getXrayLine(width.toDouble())
		val consumer = context.bufferSource().getBuffer(renderLayer)
		consumer.addVertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.setColor(red, green, blue, alpha)
			.setNormal(entry, normalX, normalY, normalZ)
			.setLineWidth(width)
		consumer.addVertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
			.setColor(red, green, blue, alpha)
			.setNormal(entry, normalX, normalY, normalZ)
			.setLineWidth(width)
		context.bufferSource().endBatch(renderLayer)
		matrices.popPose()
	}

	private fun Double.isFinite(): Boolean = !isNaN() && kotlin.math.abs(this) != Double.POSITIVE_INFINITY

	private const val CROSSHAIR_OFFSET = 2.0
	private const val DEFAULT_LINE_COLOR = 0x36C5F0

	private data class ShulkerTarget(
		val id: UUID,
		val center: Vec3,
	)
}
