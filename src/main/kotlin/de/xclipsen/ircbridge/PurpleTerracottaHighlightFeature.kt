package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.Blocks
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.phys.AABB
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.chunk.LevelChunk
import java.util.function.Predicate

object PurpleTerracottaHighlightFeature {
	private const val RESCAN_INTERVAL_TICKS = 10
	private const val BOX_EXPANSION = 0.002
	private const val DEFAULT_COLOR = 0xB06CFF
	private val TARGET_MATCHER = Predicate<BlockState> { it.block === Blocks.PURPLE_TERRACOTTA }

	private val highlightedBlocks = linkedSetOf<BlockPos>()
	private var lastWorld: ClientLevel? = null
	private var ticksUntilRescan = 0

	fun onTick(client: Minecraft) {
		val config = XclipsenIrcBridgeClient.instance?.config()
		val world = client.level
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
		rescan(world, ChunkPos.containing(player.blockPosition()), resolveScanRadiusChunks(client))
	}

	fun onWorldChange() {
		clear()
		lastWorld = null
		ticksUntilRescan = 0
	}

	fun render(context: LevelRenderContext) {
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
		val cameraPos = context.levelState().cameraRenderState.pos
		val matrices = context.poseStack()
		val consumers = context.bufferSource()

		matrices.pushPose()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val fillLayer = net.minecraft.client.render.XclipsenRenderLayers.getXrayFill()
		val fillConsumer = consumers.getBuffer(fillLayer)
		for (pos in highlightedBlocks) {
			drawHighlight(matrices, fillConsumer, pos, redFloat, greenFloat, blueFloat)
		}
		consumers.endBatch(fillLayer)
		matrices.popPose()
	}

	private fun clear() {
		highlightedBlocks.clear()
	}

	private fun resolveScanRadiusChunks(client: Minecraft): Int {
		return client.options.renderDistance().get().coerceIn(2, 32)
	}

	private fun rescan(world: ClientLevel, center: ChunkPos, radiusChunks: Int) {
		highlightedBlocks.clear()

		for (chunkX in (center.x - radiusChunks)..(center.x + radiusChunks)) {
			for (chunkZ in (center.z - radiusChunks)..(center.z + radiusChunks)) {
				if (!world.hasChunk(chunkX, chunkZ)) {
					continue
				}

				val chunk = world.chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) ?: continue
				collectChunkHighlights(chunk)
			}
		}
	}

	private fun collectChunkHighlights(chunk: LevelChunk) {
		val sections = chunk.sections
		val chunkPos = chunk.pos

		for (sectionIndex in sections.indices) {
			val section = sections[sectionIndex]
			if (!shouldScanSection(section)) {
				continue
			}

			val baseY = chunk.getSectionYFromSectionIndex(sectionIndex) * 16
			for (x in 0 until 16) {
				for (y in 0 until 16) {
					for (z in 0 until 16) {
						if (section.getBlockState(x, y, z).block !== Blocks.PURPLE_TERRACOTTA) {
							continue
						}

						highlightedBlocks += BlockPos(chunkPos.minBlockX + x, baseY + y, chunkPos.minBlockZ + z)
					}
				}
			}
		}
	}

	private fun shouldScanSection(section: LevelChunkSection): Boolean {
		return !section.hasOnlyAir() && section.maybeHas(TARGET_MATCHER)
	}

	private fun drawHighlight(
		matrices: com.mojang.blaze3d.vertex.PoseStack,
		fillConsumer: com.mojang.blaze3d.vertex.VertexConsumer,
		pos: BlockPos,
		red: Float,
		green: Float,
		blue: Float,
	) {
		val box = AABB(
			pos.x.toDouble() - BOX_EXPANSION,
			pos.y.toDouble() - BOX_EXPANSION,
			pos.z.toDouble() - BOX_EXPANSION,
			pos.x.toDouble() + 1.0 + BOX_EXPANSION,
			pos.y.toDouble() + 1.0 + BOX_EXPANSION,
			pos.z.toDouble() + 1.0 + BOX_EXPANSION,
		)
		XclipsenWorldRenderUtils.drawFilledBox(
			matrices.last(),
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
