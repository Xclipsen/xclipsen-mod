package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult

object PickobulusHelperFeature {
	private val predictedBlocks = linkedSetOf<BlockPos>()
	private var tickCounter = 0
	private var lastReason = "inactive"

	fun onTick(client: Minecraft) {
		if (++tickCounter < UPDATE_INTERVAL_TICKS) return
		tickCounter = 0

		val config = XclipsenIrcBridgeClient.instance?.config()
		val world = client.level
		val player = client.player
		val island = resolveMiningIsland()
		if (config?.pickaxeAbilityCooldownModuleEnabled != true || !config.pickobulusHelperModuleEnabled) {
			clear("disabled")
			return
		}
		if (world == null || player == null || island == null) {
			clear("outside mining islands")
			return
		}
		if (!hasPickobulus(player.mainHandItem)) {
			clear("not holding Pickobulus tool")
			return
		}
		if (!isPickobulusReady()) {
			clear("ability on cooldown")
			return
		}

		val start = player.eyePosition.add(0.0, RAYCAST_HEIGHT_OFFSET, 0.0)
		val hit = world.clip(
			ClipContext(start, start.add(player.lookAngle.scale(REACH)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player),
		)
		if (hit.type != HitResult.Type.BLOCK) {
			clear("not looking at a block")
			return
		}

		calculate(world::getBlockState, hit.blockPos, island)
		lastReason = "predicting"
	}

	fun render(context: LevelRenderContext) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.pickaxeAbilityCooldownModuleEnabled || !config.pickobulusHelperModuleEnabled || resolveMiningIsland() == null || predictedBlocks.isEmpty()) return

		val cameraPos = context.levelState().cameraRenderState.pos
		val matrices = context.poseStack()
		val consumers = context.bufferSource()
		val layer = RenderTypes.lines()
		val consumer = consumers.getBuffer(layer)
		val (red, green, blue) = ClientColor.rgbFloatChannels(HIGHLIGHT_COLOR)

		matrices.pushPose()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		for (pos in predictedBlocks) {
			XclipsenWorldRenderUtils.drawBox(
				matrices.last(),
				consumer,
				AABB(pos).inflate(BOX_EXPANSION),
				red,
				green,
				blue,
				1.0f,
				LINE_WIDTH.toFloat(),
			)
		}
		consumers.endBatch(layer)
		matrices.popPose()
	}

	fun onWorldChange() {
		tickCounter = 0
		clear("world changed")
	}

	fun predictedBlockCount(): Int = predictedBlocks.size

	fun statusLine(): String {
		val config = XclipsenIrcBridgeClient.instance?.config()
		val enabled = config?.pickaxeAbilityCooldownModuleEnabled == true && config.pickobulusHelperModuleEnabled
		return "enabled=$enabled, configured=${config?.pickobulusHelperModuleEnabled == true}, island=${LocationTracker.currentIsland}, resolved=${resolveMiningIsland()}, " +
			"area=${LocationTracker.currentArea.ifBlank { "unknown" }}, predicted=${predictedBlocks.size}, state=$lastReason, packets=none"
	}

	private fun calculate(stateAt: (BlockPos) -> BlockState, origin: BlockPos, island: IslandType) {
		predictedBlocks.clear()
		val states = Array(CUBE_SIZE) { x ->
			Array(CUBE_SIZE) { y ->
				Array(CUBE_SIZE) { z -> stateAt(origin.offset(x - CUBE_RADIUS, y - CUBE_RADIUS, z - CUBE_RADIUS)) }
			}
		}

		for (x in 1 until CUBE_SIZE - 1) {
			for (y in 1 until CUBE_SIZE - 1) {
				for (z in 1 until CUBE_SIZE - 1) {
					val state = states[x][y][z]
					if (state.isAir || state.`is`(Blocks.BEDROCK) || !isExposed(states, x, y, z)) continue
					val pos = origin.offset(x - CUBE_RADIUS, y - CUBE_RADIUS, z - CUBE_RADIUS)
					if (isBreakable(state, island)) {
						predictedBlocks += pos
					}
				}
			}
		}
	}

	private fun isExposed(states: Array<Array<Array<BlockState>>>, x: Int, y: Int, z: Int): Boolean =
		states[x - 1][y][z].isAir || states[x + 1][y][z].isAir ||
			states[x][y - 1][z].isAir || states[x][y + 1][z].isAir ||
			states[x][y][z - 1].isAir || states[x][y][z + 1].isAir

	private fun isBreakable(state: BlockState, island: IslandType): Boolean {
		return when (island) {
			IslandType.CRYSTAL_HOLLOWS, IslandType.MINESHAFT -> true
			IslandType.DWARVEN_MINES -> if (isInGlaciteTunnels()) state.block in GLACITE_TUNNEL_BLOCKS else state.block in BEDROCK_CONVERSION_BLOCKS
			IslandType.GOLD_MINES, IslandType.DEEP_CAVERNS -> state.block in BEDROCK_CONVERSION_BLOCKS
			else -> false
		}
	}

	private fun isInGlaciteTunnels(): Boolean =
		LocationTracker.currentArea.contains("Glacite Tunnels", ignoreCase = true) ||
			LocationTracker.currentArea.contains("Great Glacite Lake", ignoreCase = true)

	private fun resolveMiningIsland(): IslandType? {
		LocationTracker.currentIsland.takeIf { it in MINING_ISLANDS }?.let { return it }
		return when (LocationTracker.currentModeIdentifier) {
			"mining_1" -> IslandType.GOLD_MINES
			"mining_2" -> IslandType.DEEP_CAVERNS
			"mining_3" -> IslandType.DWARVEN_MINES
			"crystal_hollows" -> IslandType.CRYSTAL_HOLLOWS
			"mineshaft" -> IslandType.MINESHAFT
			else -> miningIslandFromArea(LocationTracker.currentArea)
		}
	}

	private fun miningIslandFromArea(area: String): IslandType? = when {
		area.contains("Mineshaft", ignoreCase = true) -> IslandType.MINESHAFT
		area.contains("Crystal Hollows", ignoreCase = true) -> IslandType.CRYSTAL_HOLLOWS
		area.contains("Dwarven Mines", ignoreCase = true) ||
			area.contains("Glacite Tunnels", ignoreCase = true) ||
			area.contains("Great Glacite Lake", ignoreCase = true) -> IslandType.DWARVEN_MINES
		area.contains("Deep Caverns", ignoreCase = true) -> IslandType.DEEP_CAVERNS
		area.contains("Gold Mine", ignoreCase = true) -> IslandType.GOLD_MINES
		else -> null
	}

	private fun hasPickobulus(stack: net.minecraft.world.item.ItemStack): Boolean =
		stack.get(DataComponents.LORE)?.lines()?.any { it.string.contains("Pickobulus", ignoreCase = true) } == true

	private fun isPickobulusReady(): Boolean {
		val status = PickaxeAbilityCooldownFeature.currentStatus() ?: return false
		return status.name.equals("Pickobulus", ignoreCase = true) && status.ready
	}

	private fun clear(reason: String) {
		predictedBlocks.clear()
		lastReason = reason
	}

	private val STAINED_GLASS_BLOCKS: Set<Block> = setOf(
		Blocks.WHITE_STAINED_GLASS,
		Blocks.ORANGE_STAINED_GLASS,
		Blocks.MAGENTA_STAINED_GLASS,
		Blocks.LIGHT_BLUE_STAINED_GLASS,
		Blocks.YELLOW_STAINED_GLASS,
		Blocks.LIME_STAINED_GLASS,
		Blocks.PINK_STAINED_GLASS,
		Blocks.GRAY_STAINED_GLASS,
		Blocks.LIGHT_GRAY_STAINED_GLASS,
		Blocks.CYAN_STAINED_GLASS,
		Blocks.PURPLE_STAINED_GLASS,
		Blocks.BLUE_STAINED_GLASS,
		Blocks.BROWN_STAINED_GLASS,
		Blocks.GREEN_STAINED_GLASS,
		Blocks.RED_STAINED_GLASS,
		Blocks.BLACK_STAINED_GLASS,
		Blocks.WHITE_STAINED_GLASS_PANE,
		Blocks.ORANGE_STAINED_GLASS_PANE,
		Blocks.MAGENTA_STAINED_GLASS_PANE,
		Blocks.LIGHT_BLUE_STAINED_GLASS_PANE,
		Blocks.YELLOW_STAINED_GLASS_PANE,
		Blocks.LIME_STAINED_GLASS_PANE,
		Blocks.PINK_STAINED_GLASS_PANE,
		Blocks.GRAY_STAINED_GLASS_PANE,
		Blocks.LIGHT_GRAY_STAINED_GLASS_PANE,
		Blocks.CYAN_STAINED_GLASS_PANE,
		Blocks.PURPLE_STAINED_GLASS_PANE,
		Blocks.BLUE_STAINED_GLASS_PANE,
		Blocks.BROWN_STAINED_GLASS_PANE,
		Blocks.GREEN_STAINED_GLASS_PANE,
		Blocks.RED_STAINED_GLASS_PANE,
		Blocks.BLACK_STAINED_GLASS_PANE,
	)
	private val BEDROCK_CONVERSION_BLOCKS: Set<Block> = setOf(
		Blocks.STONE,
		Blocks.COBBLESTONE,
		Blocks.POLISHED_DIORITE,
		Blocks.PRISMARINE,
		Blocks.PRISMARINE_BRICKS,
		Blocks.DARK_PRISMARINE,
		Blocks.CYAN_TERRACOTTA,
		Blocks.LIGHT_BLUE_WOOL,
		Blocks.GRAY_WOOL,
		Blocks.LAPIS_BLOCK,
		Blocks.GOLD_BLOCK,
		Blocks.IRON_BLOCK,
		Blocks.DIAMOND_BLOCK,
		Blocks.EMERALD_BLOCK,
		Blocks.REDSTONE_BLOCK,
		Blocks.COAL_BLOCK,
		Blocks.QUARTZ_BLOCK,
		Blocks.GOLD_ORE,
		Blocks.IRON_ORE,
		Blocks.COAL_ORE,
		Blocks.LAPIS_ORE,
		Blocks.REDSTONE_ORE,
		Blocks.DIAMOND_ORE,
		Blocks.EMERALD_ORE,
		Blocks.NETHER_QUARTZ_ORE,
		Blocks.NETHERRACK,
		Blocks.GLOWSTONE,
		Blocks.OBSIDIAN,
		Blocks.END_STONE,
	) + STAINED_GLASS_BLOCKS
	private val GLACITE_TUNNEL_BLOCKS: Set<Block> = setOf(
		Blocks.PACKED_ICE,
		Blocks.POLISHED_DIORITE,
		Blocks.INFESTED_STONE,
		Blocks.LIGHT_GRAY_CARPET,
		Blocks.PRISMARINE,
		Blocks.PRISMARINE_BRICKS,
		Blocks.DARK_PRISMARINE,
		Blocks.LIGHT_BLUE_WOOL,
		Blocks.GRAY_WOOL,
		Blocks.CYAN_TERRACOTTA,
		Blocks.BROWN_TERRACOTTA,
		Blocks.TERRACOTTA,
		Blocks.SMOOTH_RED_SANDSTONE,
		Blocks.INFESTED_COBBLESTONE,
		Blocks.CLAY,
	) + STAINED_GLASS_BLOCKS
	private val MINING_ISLANDS = setOf(
		IslandType.GOLD_MINES,
		IslandType.DEEP_CAVERNS,
		IslandType.DWARVEN_MINES,
		IslandType.CRYSTAL_HOLLOWS,
		IslandType.MINESHAFT,
	)
	private const val UPDATE_INTERVAL_TICKS = 2
	private const val CUBE_SIZE = 8
	private const val CUBE_RADIUS = 4
	private const val REACH = 20.0
	private const val RAYCAST_HEIGHT_OFFSET = 0.53625
	private const val LINE_WIDTH = 2.0
	private const val BOX_EXPANSION = 0.002
	// Feature-specific light blue keeps predicted Pickobulus blocks distinct from other ESP modules.
	private const val HIGHLIGHT_COLOR = 0x55FFFF
}
