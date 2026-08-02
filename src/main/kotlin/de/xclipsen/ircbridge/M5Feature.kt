package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.network.chat.Component
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

object M5Feature {
	private val lividMap = mapOf(
		Blocks.GREEN_WOOL to "Frog Livid",
		Blocks.PURPLE_WOOL to "Purple Livid",
		Blocks.GRAY_WOOL to "Doctor Livid",
		Blocks.BLUE_WOOL to "Scream Livid",
		Blocks.LIME_WOOL to "Smile Livid",
		Blocks.RED_WOOL to "Hockey Livid",
		Blocks.MAGENTA_WOOL to "Crossed Livid",
		Blocks.YELLOW_WOOL to "Arcade Livid",
		Blocks.WHITE_WOOL to "Vendetta Livid",
	)

	private var lastWorld: ClientLevel? = null
	private var tickCounter = 0
	private var bossEncounterActive = false
	private var ragAxeTriggered = false
	private var currentLividId: UUID? = null
	private var currentLividName = ""
	private var iceSprayTicksRemaining = 0
	private var lastObservedServerTick = 0L
	private var currentAlertText = ""
	private var alertVisibleUntil = 0L
	private var lastNormalizedMessage = ""
	private var lastNormalizedMessageAt = 0L

	fun onTick(client: Minecraft) {
		val config = XclipsenIrcBridgeClient.instance?.config()
		val world = client.level
		val player = client.player
		if (config?.m5ModuleEnabled != true || world == null || player == null || !LocationTracker.isOnHypixelSkyBlock) {
			clearRuntimeState(clearAlert = true)
			return
		}

		if (world !== lastWorld) {
			clearRuntimeState(clearAlert = true)
			lastWorld = world
		}

		val currentServerTick = ServerTickTracker.currentTickCount()
		val elapsedServerTicks = (currentServerTick - lastObservedServerTick).coerceAtLeast(0L)
		lastObservedServerTick = currentServerTick

		if (iceSprayTicksRemaining > 0 && elapsedServerTicks > 0L) {
			val previousTicksRemaining = iceSprayTicksRemaining
			iceSprayTicksRemaining = max(0, iceSprayTicksRemaining - elapsedServerTicks.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
			if (previousTicksRemaining > 0 && iceSprayTicksRemaining == 0 && config.m5IceSprayTimerEnabled) {
				showAlert(ICE_SPRAY_READY_ALERT)
			}
		}

		if (++tickCounter < LIVID_SCAN_INTERVAL_TICKS) {
			return
		}
		tickCounter = 0

		val hasBossRoomSignals = detectBossRoom(world)
		if (!bossEncounterActive && !hasBossRoomSignals) {
			currentLividId = null
			currentLividName = ""
			return
		}

		if (!config.m5LividFinderEnabled) {
			currentLividId = null
			currentLividName = ""
			return
		}

		updateCurrentLivid(client, world)
	}

	fun onWorldChange() {
		clearRuntimeState(clearAlert = true)
		lastWorld = null
	}

	fun onIncomingMessage(message: Component?) {
		val normalized = normalizeMessage(message?.string ?: return)
		if (normalized.isBlank()) {
			return
		}

		val now = System.currentTimeMillis()
		if (normalized == lastNormalizedMessage && now - lastNormalizedMessageAt <= MESSAGE_DEDUPE_WINDOW_MS) {
			return
		}
		lastNormalizedMessage = normalized
		lastNormalizedMessageAt = now

		if (normalized == LIVID_BOSS_INTRO_MESSAGE) {
			bossEncounterActive = true
			ragAxeTriggered = false
			iceSprayTicksRemaining = ICE_SPRAY_DELAY_TICKS
			lastObservedServerTick = ServerTickTracker.currentTickCount()
			return
		}

		if (normalized == RAG_AXE_TRIGGER_MESSAGE && !ragAxeTriggered) {
			bossEncounterActive = true
			ragAxeTriggered = true
			val config = XclipsenIrcBridgeClient.instance?.config()
			if (config?.m5ModuleEnabled == true && config.m5RagAxeAlertEnabled) {
				Minecraft.getInstance().player?.sendSystemMessage(Component.literal(RAG_AXE_CHAT_MESSAGE))
				showAlert(RAG_AXE_ALERT)
			}
		}
	}

	fun shouldGlow(entity: Entity): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		return config.m5ModuleEnabled &&
			config.m5LividFinderEnabled &&
			currentLividId != null &&
			entity.uuid == currentLividId
	}

	fun colorValue(entity: Entity): Int? {
		return if (shouldGlow(entity)) LIVID_GLOW_COLOR else null
	}

	fun render(context: LevelRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.m5ModuleEnabled || !config.m5TracerEnabled) {
			return
		}

		val client = Minecraft.getInstance()
		val world = client.level ?: return
		if (client.player == null || client.options.hideGui) {
			return
		}

		val livid = currentLividEntity(world) ?: return
		if (!livid.isAlive || livid.isRemoved) {
			return
		}

		val cameraPos = context.levelState().cameraRenderState.pos
		val start = crosshairStart(cameraPos)
		val end = livid.boundingBox.center.add(0.0, livid.bbHeight * 0.2, 0.0)
		drawLine(context, cameraPos, start, end, TRACER_COLOR, TRACER_WIDTH)
	}

	fun shouldDrawTimer(config: BridgeConfig): Boolean {
		return config.m5ModuleEnabled && config.m5IceSprayTimerEnabled && iceSprayTicksRemaining > 0
	}

	fun timerLine(): String {
		if (iceSprayTicksRemaining <= 0) {
			return "No active Ice Spray timer"
		}
		return formatTimerValue(iceSprayTicksRemaining)
	}

	fun shouldDrawAlert(config: BridgeConfig): Boolean {
		return config.m5ModuleEnabled &&
			currentAlertText.isNotBlank() &&
			System.currentTimeMillis() <= alertVisibleUntil
	}

	fun currentAlertText(): String = currentAlertText

	fun statusLine(): String {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return "Unavailable"
		if (!config.m5ModuleEnabled) {
			return "Disabled"
		}
		if (!LocationTracker.isOnHypixelSkyBlock) {
			return "Not on Hypixel SkyBlock"
		}
		if (iceSprayTicksRemaining > 0) {
			return "Ice Spray in ${formatTimerValue(iceSprayTicksRemaining)}"
		}
		if (currentLividName.isNotBlank()) {
			return "Tracking $currentLividName"
		}
		return if (bossEncounterActive) "Waiting for Livid split" else "Idle"
	}

	private fun updateCurrentLivid(client: Minecraft, world: ClientLevel) {
		val targetLividName = resolveTargetLividName(world) ?: run {
			currentLividId = null
			currentLividName = ""
			return
		}
		currentLividName = targetLividName

		val current = currentLividEntity(world)
		if (current != null && current.isAlive && !current.isRemoved && entityName(current).equals(targetLividName, ignoreCase = true)) {
			return
		}

		currentLividId = world.players()
			.asSequence()
			.filter { it !== client.player }
			.filter { it.isAlive && !it.isRemoved }
			.firstOrNull { entityName(it).equals(targetLividName, ignoreCase = true) }
			?.uuid
	}

	private fun detectBossRoom(world: ClientLevel): Boolean {
		if (!isDungeonArea()) {
			return false
		}

		val targetName = resolveTargetLividName(world) ?: return false
		return world.players().asSequence()
			.any { it.isAlive && !it.isRemoved && entityName(it).equals(targetName, ignoreCase = true) }
	}

	private fun resolveTargetLividName(world: ClientLevel): String? {
		val block = world.getBlockState(CEILING_WOOL_BLOCK).block
		return lividMap[block]
	}

	private fun currentLividEntity(world: ClientLevel): Player? {
		val currentId = currentLividId ?: return null
		return world.players()
			.asSequence()
			.firstOrNull { it.uuid == currentId }
	}

	private fun entityName(entity: Entity): String {
		return entity.displayName?.string?.trim().orEmpty()
	}

	private fun isDungeonArea(): Boolean {
		val area = LocationTracker.currentArea
		return area.contains("catacombs", ignoreCase = true) || area.contains("dungeon", ignoreCase = true)
	}

	private fun showAlert(text: String) {
		currentAlertText = text
		alertVisibleUntil = System.currentTimeMillis() + ALERT_VISIBLE_MS
	}

	private fun clearRuntimeState(clearAlert: Boolean) {
		tickCounter = 0
		bossEncounterActive = false
		ragAxeTriggered = false
		currentLividId = null
		currentLividName = ""
		iceSprayTicksRemaining = 0
		lastObservedServerTick = ServerTickTracker.currentTickCount()
		lastNormalizedMessage = ""
		lastNormalizedMessageAt = 0L
		if (clearAlert) {
			currentAlertText = ""
			alertVisibleUntil = 0L
		}
	}

	private fun normalizeMessage(raw: String): String {
		if (raw.isBlank()) {
			return ""
		}

		var result = raw
		if (result.contains('§')) {
			val builder = StringBuilder(result.length)
			var skip = false
			for (character in result) {
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
			result = builder.toString()
		}

		result = AMPERSAND_PATTERN.replace(result, "")
		return result.replace('\r', ' ').replace('\n', ' ').replace(WHITESPACE_PATTERN, " ").trim()
	}

	private fun formatTimerValue(ticks: Int): String {
		val seconds = ticks / 20.0
		val secondsText = if (seconds >= 10.0) {
			"${ceil(seconds).toInt()}s"
		} else {
			String.format(Locale.ROOT, "%.1fs", ceil(seconds * 10.0) / 10.0)
		}
		return "${ticks}t ($secondsText)"
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
		val length = sqrt(lengthSquared)
		val normalX = (delta.x / length).toFloat()
		val normalY = (delta.y / length).toFloat()
		val normalZ = (delta.z / length).toFloat()

		val (red, green, blue) = ClientColor.rgbChannels(color)

		matrices.pushPose()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.last()
		val renderLayer = XclipsenRenderLayers.getXrayLine(width.toDouble())
		val consumer = context.bufferSource().getBuffer(renderLayer)
		consumer.addVertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
			.setColor(red, green, blue, 230)
			.setNormal(entry, normalX, normalY, normalZ)
			.setLineWidth(width)
		consumer.addVertex(entry, end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
			.setColor(red, green, blue, 230)
			.setNormal(entry, normalX, normalY, normalZ)
			.setLineWidth(width)
		context.bufferSource().endBatch(renderLayer)
		matrices.popPose()
	}

	private fun Double.isFinite(): Boolean = !isNaN() && this != Double.POSITIVE_INFINITY && this != Double.NEGATIVE_INFINITY

	private val CEILING_WOOL_BLOCK = BlockPos(5, 108, 40)
	private const val LIVID_SCAN_INTERVAL_TICKS = 2
	private const val ICE_SPRAY_DELAY_TICKS = 390
	private const val MESSAGE_DEDUPE_WINDOW_MS = 750L
	private const val ALERT_VISIBLE_MS = 2_800L
	private const val CROSSHAIR_OFFSET = 2.0
	private const val TRACER_WIDTH = 2.0f
	private const val LIVID_GLOW_COLOR = 0x55E3FF
	private const val TRACER_COLOR = 0x55E3FF
	private const val LIVID_BOSS_INTRO_MESSAGE = "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows."
	private const val RAG_AXE_TRIGGER_MESSAGE = "[BOSS] Livid: I can now turn those Spirits into shadows of myself, identical to their creator."
	private const val ICE_SPRAY_READY_ALERT = "Ice Spray Livid!"
	private const val RAG_AXE_ALERT = "Rag Axe now!"
	private const val RAG_AXE_CHAT_MESSAGE = "[M5] Rag Axe now!"
	private val AMPERSAND_PATTERN = Regex("(?i)&[0-9A-FK-OR]")
	private val WHITESPACE_PATTERN = Regex("\\s+")
}

object M5IceSprayHudElement : XclipsenHudElement(
	id = "m5_ice_spray_timer",
	displayName = "M5 Ice Spray TimerQueue",
) {
	override fun isEnabled(config: BridgeConfig): Boolean =
		config.m5ModuleEnabled && config.m5IceSprayTimerEnabled

	override fun shouldDraw(config: BridgeConfig): Boolean =
		M5Feature.shouldDrawTimer(config)

	override fun defaultX(context: GuiGraphicsExtractor): Float = 20f

	override fun defaultY(context: GuiGraphicsExtractor): Float = 120f

	override fun draw(context: GuiGraphicsExtractor, example: Boolean): Tuple<Float, Float> {
		val client = Minecraft.getInstance()
		val textRenderer = client.font
		val title = "ICE SPRAY"
		val value = if (example) "390t (19.5s)" else M5Feature.timerLine()
		val contentWidth = max(textRenderer.width(title), textRenderer.width(value))
		val boxWidth = max(MIN_WIDTH, contentWidth + (PADDING_X * 2))
		val boxHeight = PADDING_Y + textRenderer.lineHeight + LINE_GAP + textRenderer.lineHeight + PADDING_Y

		context.text(textRenderer, title, PADDING_X, PADDING_Y, LABEL_TEXT, true)
		context.text(textRenderer, value, PADDING_X, PADDING_Y + textRenderer.lineHeight + LINE_GAP, VALUE_TEXT, true)

		return boxWidth.toFloat() to boxHeight.toFloat()
	}

	private const val PADDING_X = 6
	private const val PADDING_Y = 5
	private const val LINE_GAP = 2
	private const val MIN_WIDTH = 112
	private const val LABEL_TEXT = 0xFFE8E8E8.toInt()
	private const val VALUE_TEXT = 0xFF8DEEFF.toInt()
}

object M5AlertHudElement : XclipsenHudElement(
	id = "m5_alert",
	displayName = "M5 Alert",
) {
	override fun isEnabled(config: BridgeConfig): Boolean = config.m5ModuleEnabled

	override fun shouldDraw(config: BridgeConfig): Boolean =
		M5Feature.shouldDrawAlert(config)

	override fun defaultX(context: GuiGraphicsExtractor): Float {
		return ((context.guiWidth() - DEFAULT_WIDTH) / 2f).coerceAtLeast(4f)
	}

	override fun defaultY(context: GuiGraphicsExtractor): Float {
		return (context.guiHeight() * 0.28f).coerceAtLeast(28f)
	}

	override fun draw(context: GuiGraphicsExtractor, example: Boolean): Tuple<Float, Float> {
		val client = Minecraft.getInstance()
		val textRenderer = client.font
		val text = if (example) "Rag Axe now!" else M5Feature.currentAlertText()
		val width = max(DEFAULT_WIDTH, textRenderer.width(text) + (PADDING_X * 2))
		val height = PADDING_Y + textRenderer.lineHeight + PADDING_Y

		context.text(textRenderer, text, (width - textRenderer.width(text)) / 2, PADDING_Y, TEXT_COLOR, true)
		return width.toFloat() to height.toFloat()
	}

	private const val DEFAULT_WIDTH = 180
	private const val PADDING_X = 8
	private const val PADDING_Y = 6
	private const val TEXT_COLOR = 0xFFFFFFFF.toInt()
}
