package de.xclipsen.ircbridge

import de.xclipsen.ircbridge.FrozenCorpseDetector.DetectedFrozenCorpse
import de.xclipsen.ircbridge.FrozenCorpseDetector.FrozenCorpseType
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.util.math.Box

object CorpseEspFeature {
	private var tickCounter = 0
	private var cachedCorpses: List<DetectedFrozenCorpse> = emptyList()

	fun onTick(client: MinecraftClient) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: run {
			cachedCorpses = emptyList()
			return
		}

		if (!config.corpseEspModuleEnabled || !FrozenCorpseDetector.isInMineshaftArea()) {
			cachedCorpses = emptyList()
			tickCounter = 0
			return
		}

		if (client.world == null || client.player == null) {
			cachedCorpses = emptyList()
			tickCounter = 0
			return
		}

		if (++tickCounter < SCAN_INTERVAL_TICKS) {
			return
		}
		tickCounter = 0

		cachedCorpses = FrozenCorpseDetector.findNearbyCorpses(client)
			.filter { corpse -> isTypeEnabled(config, corpse.type) }
	}

	fun onDisconnect() {
		tickCounter = 0
		cachedCorpses = emptyList()
	}

	fun render(context: WorldRenderContext) {
		val client = MinecraftClient.getInstance()
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.corpseEspModuleEnabled || client.options.hudHidden || cachedCorpses.isEmpty()) {
			return
		}

		val cameraPos = context.gameRenderer().camera.cameraPos
		val matrices = context.matrices()
		val consumers = context.consumers()
		val lineLayer = XclipsenRenderLayers.getXrayLine(OUTLINE_WIDTH)
		val lineConsumer = consumers.getBuffer(lineLayer)
		val fillConsumer = consumers.getBuffer(XclipsenRenderLayers.getXrayFill())

		matrices.push()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.peek()

		for (corpse in cachedCorpses) {
			val stand = corpse.armorStand
			if (!stand.isAlive || stand.isRemoved || !isTypeEnabled(config, corpse.type)) {
				continue
			}

			val color = corpse.type.colorRgb
			val red = (color shr 16 and 0xFF) / 255.0f
			val green = (color shr 8 and 0xFF) / 255.0f
			val blue = (color and 0xFF) / 255.0f
			val box = expandedBox(stand.boundingBox)

			VertexRendering.drawFilledBox(
				matrices,
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
			VertexRendering.drawBox(entry, lineConsumer, box, red, green, blue, BOX_OUTLINE_ALPHA)
		}

		(consumers as? VertexConsumerProvider.Immediate)?.draw(XclipsenRenderLayers.getXrayFill())
		(consumers as? VertexConsumerProvider.Immediate)?.draw(lineLayer)
		matrices.pop()
	}

	private fun expandedBox(box: Box): Box {
		return box.expand(BOX_EXPANSION_XZ, BOX_EXPANSION_Y, BOX_EXPANSION_XZ)
	}

	private fun isTypeEnabled(config: BridgeConfig, type: FrozenCorpseType): Boolean {
		return when (type) {
			FrozenCorpseType.LAPIS -> config.corpseEspLapisEnabled
			FrozenCorpseType.TUNGSTEN -> config.corpseEspTungstenEnabled
			FrozenCorpseType.UMBER -> config.corpseEspUmberEnabled
			FrozenCorpseType.VANGUARD -> config.corpseEspVanguardEnabled
		}
	}

	private const val SCAN_INTERVAL_TICKS = 5
	private const val OUTLINE_WIDTH = 2.25
	private const val BOX_EXPANSION_XZ = 0.45
	private const val BOX_EXPANSION_Y = 0.95
	private const val BOX_FILL_ALPHA = 0.12f
	private const val BOX_OUTLINE_ALPHA = 0.92f
}
