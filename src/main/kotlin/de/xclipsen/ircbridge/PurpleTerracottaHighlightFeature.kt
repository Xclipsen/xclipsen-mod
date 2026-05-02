package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Box
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.ChunkSection
import net.minecraft.world.chunk.ChunkStatus
import net.minecraft.world.chunk.WorldChunk
import java.util.function.Predicate

object PurpleTerracottaHighlightFeature {
	private const val RESCAN_INTERVAL_TICKS = 10
	private const val OUTLINE_WIDTH = 2.0
	private const val OUTLINE_ALPHA = 230
	private const val BOX_EXPANSION = 0.002
	private const val DEFAULT_COLOR = 0xB06CFF
	private val TARGET_MATCHER = Predicate<BlockState> { it.isOf(Blocks.PURPLE_TERRACOTTA) }

	private val highlightedBlocks = linkedSetOf<BlockPos>()
	private var lastWorld: ClientWorld? = null
	private var ticksUntilRescan = 0

	fun onTick(client: MinecraftClient) {
		val config = XclipsenIrcBridgeClient.instance?.config()
		val world = client.world
		val player = client.player
		if (
			config == null ||
			world == null ||
			player == null ||
			!config.purpleTerracottaHighlightModuleEnabled ||
			!LocationTracker.isOnEndIsland
		) {
			clear()
			return
		}

		if (world !== lastWorld) {
			clear()
			lastWorld = world
			ticksUntilRescan = 0
		}

		if (ticksUntilRescan-- > 0) {
			return
		}

		ticksUntilRescan = RESCAN_INTERVAL_TICKS
		rescan(world, ChunkPos(player.blockPos), resolveScanRadiusChunks(client))
	}

	fun onWorldChange() {
		clear()
		lastWorld = null
		ticksUntilRescan = 0
	}

	fun render(context: WorldRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.purpleTerracottaHighlightModuleEnabled || !LocationTracker.isOnEndIsland || highlightedBlocks.isEmpty()) {
			return
		}

		val color = parseColor(config.purpleTerracottaHighlightColorHex) ?: DEFAULT_COLOR
		val red = color shr 16 and 0xFF
		val green = color shr 8 and 0xFF
		val blue = color and 0xFF
		val redFloat = red / 255.0f
		val greenFloat = green / 255.0f
		val blueFloat = blue / 255.0f
		val cameraPos = context.gameRenderer().camera.cameraPos
		val matrices = context.matrices()
		val consumers = context.consumers()

		matrices.push()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.peek()
		val lineConsumer = consumers.getBuffer(XclipsenRenderLayers.getXrayLine(OUTLINE_WIDTH))
		val fillConsumer = consumers.getBuffer(XclipsenRenderLayers.getXrayFill())
		for (pos in highlightedBlocks) {
			drawHighlight(matrices, entry, fillConsumer, lineConsumer, pos, redFloat, greenFloat, blueFloat)
		}
		(consumers as? VertexConsumerProvider.Immediate)?.draw(XclipsenRenderLayers.getXrayFill())
		(consumers as? VertexConsumerProvider.Immediate)?.draw(XclipsenRenderLayers.getXrayLine(OUTLINE_WIDTH))
		matrices.pop()
	}

	private fun clear() {
		highlightedBlocks.clear()
	}

	private fun resolveScanRadiusChunks(client: MinecraftClient): Int {
		return client.options.clampedViewDistance.coerceIn(2, 32)
	}

	private fun rescan(world: ClientWorld, center: ChunkPos, radiusChunks: Int) {
		highlightedBlocks.clear()

		for (chunkX in (center.x - radiusChunks)..(center.x + radiusChunks)) {
			for (chunkZ in (center.z - radiusChunks)..(center.z + radiusChunks)) {
				if (!world.isChunkLoaded(chunkX, chunkZ)) {
					continue
				}

				val chunk = world.chunkManager.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) ?: continue
				collectChunkHighlights(chunk)
			}
		}
	}

	private fun collectChunkHighlights(chunk: WorldChunk) {
		val sections = chunk.sectionArray
		val chunkPos = chunk.pos
		val bottomY = chunk.bottomY

		for (sectionIndex in sections.indices) {
			val section = sections[sectionIndex]
			if (!shouldScanSection(section)) {
				continue
			}

			val baseY = bottomY + (sectionIndex * 16)
			for (x in 0 until 16) {
				for (y in 0 until 16) {
					for (z in 0 until 16) {
						if (!section.getBlockState(x, y, z).isOf(Blocks.PURPLE_TERRACOTTA)) {
							continue
						}

						highlightedBlocks += BlockPos(chunkPos.startX + x, baseY + y, chunkPos.startZ + z)
					}
				}
			}
		}
	}

	private fun shouldScanSection(section: ChunkSection): Boolean {
		return !section.isEmpty && section.hasAny(TARGET_MATCHER)
	}

	private fun drawHighlight(
		matrices: net.minecraft.client.util.math.MatrixStack,
		entry: net.minecraft.client.util.math.MatrixStack.Entry,
		fillConsumer: net.minecraft.client.render.VertexConsumer,
		lineConsumer: net.minecraft.client.render.VertexConsumer,
		pos: BlockPos,
		red: Float,
		green: Float,
		blue: Float,
	) {
		val box = Box(
			pos.x.toDouble() - BOX_EXPANSION,
			pos.y.toDouble() - BOX_EXPANSION,
			pos.z.toDouble() - BOX_EXPANSION,
			pos.x.toDouble() + 1.0 + BOX_EXPANSION,
			pos.y.toDouble() + 1.0 + BOX_EXPANSION,
			pos.z.toDouble() + 1.0 + BOX_EXPANSION,
		)
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
			0.12f,
		)
		VertexRendering.drawBox(entry, lineConsumer, box, red, green, blue, OUTLINE_ALPHA / 255.0f)
	}

	private fun parseColor(hex: String): Int? {
		val candidate = hex.trim().removePrefix("#")
		if (!HEX_COLOR_PATTERN.matches(candidate)) {
			return null
		}
		return candidate.toInt(16)
	}

	private val HEX_COLOR_PATTERN = Regex("[0-9a-fA-F]{6}")
}
