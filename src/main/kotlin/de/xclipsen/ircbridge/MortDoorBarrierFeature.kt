package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.entity.Entity
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

object MortDoorBarrierFeature {
	private const val CHECK_INTERVAL_TICKS = 4
	private const val MAX_MORT_DISTANCE = 9.0
	private const val FULL_CUBE_TARGET_BLOCKS = 27
	private const val SCAN_RADIUS_XZ = 6
	private const val SCAN_MIN_Y = -2
	private const val SCAN_MAX_Y = 7
	private const val DEBUG_INTERVAL_TICKS = 10
	private const val DEBUG_LINE_WIDTH = 2.0
	private const val DEBUG_ALPHA = 220
	private const val SET_BLOCK_FLAGS = 19
	private val DOOR_BLOCK_STATE = Blocks.BARRIER.defaultState

	private val appliedStates = linkedMapOf<BlockPos, BlockState>()
	private var cachedDoorCube: Set<BlockPos> = emptySet()
	private var tickCounter = 0
	private var debugTickCounter = 0

	fun onTick(client: MinecraftClient) {
		if (++tickCounter < CHECK_INTERVAL_TICKS) return
		tickCounter = 0

		val world = client.world
		val player = client.player
		val config = XclipsenIrcBridgeClient.instance?.config()
		if (world == null || player == null || config == null || !config.dungeonDoorModuleEnabled || !config.dungeonDoorEnabled || !isRelevantArea(LocationTracker.currentArea)) {
			clear(client)
			debug(client, config?.dungeonDoorDebugEnabled == true, "inactive area='${LocationTracker.currentArea.ifBlank { "?" }}'")
			return
		}

		val mort = findNearestMort(client) ?: run {
			clear(client)
			cachedDoorCube = emptySet()
			debug(client, config.dungeonDoorDebugEnabled, "no mort found area='${LocationTracker.currentArea}'")
			return
		}

		val cube = findDoorWindow(world, mort.blockPos)
		if (cube != null && cube.closedCount == FULL_CUBE_TARGET_BLOCKS && cube.airCount == 0) {
			cachedDoorCube = cube.positions
		} else if (cachedDoorCube.isNotEmpty()) {
			clear(client)
			cachedDoorCube = emptySet()
			debug(
				client,
				config.dungeonDoorDebugEnabled,
				"mort=${entityName(mort)} door open/partial -> clear ${cube?.let { "closed=${it.closedCount} air=${it.airCount}" } ?: "no cube"}",
			)
			return
		}

		if (cachedDoorCube.isEmpty()) {
			clear(client)
			val nearbyTargets = countNearbyTargetBlocks(world, mort.blockPos)
			debug(client, config.dungeonDoorDebugEnabled, "mort ok, no 3x3x3 cube matched nearbyTargets=$nearbyTargets")
			return
		}

		val cachedClosedPositions = cachedDoorCube.filterTo(linkedSetOf()) { isClosedDoorState(world.getBlockState(it)) }
		val cachedClosed = cachedClosedPositions.size
		val cachedAirPositions = cachedDoorCube.filterTo(linkedSetOf()) { world.getBlockState(it).isAir }
		if (cachedClosed != FULL_CUBE_TARGET_BLOCKS || cachedAirPositions.isNotEmpty()) {
			clear(client)
			cachedDoorCube = emptySet()
			debug(
				client,
				config.dungeonDoorDebugEnabled,
				"mort=${entityName(mort)} cached cube no longer full -> clear closed=$cachedClosed air=${cachedAirPositions.size}",
			)
			return
		}

		syncDesiredStates(client, cachedDoorCube)
		debug(
			client,
			config.dungeonDoorDebugEnabled,
			"mort=${entityName(mort)} ${cube?.let { "axis=${it.axis} start=${it.startPos.toShortString()} closed=${it.closedCount}" } ?: "using cached cube"} air=${cachedAirPositions.size} barrierTargets=${cachedDoorCube.size} applied=${appliedStates.size}",
		)
	}

	fun onWorldChange() {
		val client = MinecraftClient.getInstance()
		clear(client)
		cachedDoorCube = emptySet()
	}

	fun onRender(context: WorldRenderContext) {
		val client = MinecraftClient.getInstance()
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.dungeonDoorModuleEnabled || !config.dungeonDoorDebugEnabled) return
		if (client.player == null || appliedStates.isEmpty()) return

		val cameraPos = context.gameRenderer().camera.pos
		for (pos in appliedStates.keys) {
			drawDebugBox(context, pos, cameraPos)
		}
	}

	private fun isRelevantArea(area: String): Boolean {
		if (area.isBlank()) return false
		return area.contains("catacombs", ignoreCase = true) ||
			area.contains("entrance", ignoreCase = true) ||
			area.contains("dungeon", ignoreCase = true)
	}

	private fun findNearestMort(client: MinecraftClient): Entity? {
		val world = client.world ?: return null
		val player = client.player ?: return null
		return world.entities
			.asSequence()
			.filter { !it.isRemoved }
			.filter { it.squaredDistanceTo(player) <= MAX_MORT_DISTANCE * MAX_MORT_DISTANCE }
			.filter { entityName(it).contains("Mort", ignoreCase = true) }
			.minByOrNull { it.squaredDistanceTo(player) }
	}

	private fun entityName(entity: Entity): String {
		return entity.customName?.string ?: entity.displayName?.string ?: entity.name.string
	}

	private fun findDoorWindow(clientWorld: net.minecraft.client.world.ClientWorld, origin: BlockPos): DoorCube? {
		val candidates = mutableListOf<DoorCube>()

		for (axis in Axis.entries) {
			for (planeCoord in -SCAN_RADIUS_XZ..(SCAN_RADIUS_XZ - 2)) {
				for (sideStart in -SCAN_RADIUS_XZ..(SCAN_RADIUS_XZ - 2)) {
					for (yStart in SCAN_MIN_Y..(SCAN_MAX_Y - 2)) {
						val positions = buildWindowPositions(origin, axis, planeCoord, sideStart, yStart)
						val closedCount = positions.count { isClosedDoorState(clientWorld.getBlockState(it)) }
						val airPositions = positions.filterTo(linkedSetOf()) { clientWorld.getBlockState(it).isAir }
						val airCount = airPositions.size
						val otherCount = positions.size - closedCount - airCount
						if (closedCount + airCount != FULL_CUBE_TARGET_BLOCKS || otherCount > 0) continue

						candidates.add(
							DoorCube(
								axis = axis,
								startPos = windowStartPosition(origin, axis, planeCoord, sideStart, yStart),
								positions = positions,
								airPositions = airPositions,
								closedCount = closedCount,
								airCount = airCount,
							),
						)
					}
				}
			}
		}

		return candidates.maxWithOrNull(
			compareBy<DoorCube> { it.closedCount }
				.thenByDescending { it.airCount },
		)
	}

	private fun countNearbyTargetBlocks(clientWorld: net.minecraft.client.world.ClientWorld, origin: BlockPos): Int {
		return collectNearbyTargetBlocks(clientWorld, origin).size
	}

	private fun collectNearbyTargetBlocks(clientWorld: net.minecraft.client.world.ClientWorld, origin: BlockPos): List<BlockPos> {
		return buildList {
			for (xOffset in -SCAN_RADIUS_XZ..SCAN_RADIUS_XZ) {
				for (zOffset in -SCAN_RADIUS_XZ..SCAN_RADIUS_XZ) {
					for (yOffset in SCAN_MIN_Y..SCAN_MAX_Y) {
						val pos = origin.add(xOffset, yOffset, zOffset)
						if (clientWorld.getBlockState(pos).block == Blocks.INFESTED_CHISELED_STONE_BRICKS) {
							add(pos.toImmutable())
						}
					}
				}
			}
		}
	}

	private fun buildWindowPositions(origin: BlockPos, axis: Axis, planeCoord: Int, sideStart: Int, yStart: Int): Set<BlockPos> {
		return buildSet {
			for (depthOffset in 0..2) {
				for (sideOffset in 0..2) {
					for (yOffset in 0..2) {
						add(
							when (axis) {
								Axis.X -> origin.add(planeCoord + depthOffset, yStart + yOffset, sideStart + sideOffset)
								Axis.Z -> origin.add(sideStart + sideOffset, yStart + yOffset, planeCoord + depthOffset)
							}.toImmutable(),
						)
					}
				}
			}
		}
	}

	private fun windowStartPosition(origin: BlockPos, axis: Axis, planeCoord: Int, sideStart: Int, yStart: Int): BlockPos {
		return when (axis) {
			Axis.X -> origin.add(planeCoord, yStart, sideStart).toImmutable()
			Axis.Z -> origin.add(sideStart, yStart, planeCoord).toImmutable()
		}
	}

	private fun syncDesiredStates(client: MinecraftClient, desired: Set<BlockPos>) {
		val world = client.world ?: return
		val stale = appliedStates.keys.filter { it !in desired }
		stale.forEach { restoreBlock(world, it) }

		for (pos in desired) {
			val currentState = world.getBlockState(pos)
			if (currentState.block != Blocks.INFESTED_CHISELED_STONE_BRICKS && currentState.block != DOOR_BLOCK_STATE.block) continue
			if (currentState.block == DOOR_BLOCK_STATE.block) {
				appliedStates.putIfAbsent(pos, Blocks.INFESTED_CHISELED_STONE_BRICKS.defaultState)
				continue
			}

			appliedStates.putIfAbsent(pos.toImmutable(), currentState)
			world.setBlockState(pos, DOOR_BLOCK_STATE, SET_BLOCK_FLAGS)
		}
	}

	private fun isClosedDoorState(state: BlockState): Boolean {
		return state.block == Blocks.INFESTED_CHISELED_STONE_BRICKS || state.block == DOOR_BLOCK_STATE.block
	}

	private fun clear(client: MinecraftClient) {
		val world = client.world ?: run {
			appliedStates.clear()
			return
		}

		appliedStates.keys.toList().forEach { restoreBlock(world, it) }
		appliedStates.clear()
	}

	private fun restoreBlock(world: net.minecraft.client.world.ClientWorld, pos: BlockPos) {
		val original = appliedStates.remove(pos) ?: return
		if (world.getBlockState(pos).block == DOOR_BLOCK_STATE.block) {
			world.setBlockState(pos, original, SET_BLOCK_FLAGS)
		}
	}

	private fun debug(client: MinecraftClient, enabled: Boolean, message: String) {
		if (!enabled) return
		if (++debugTickCounter < DEBUG_INTERVAL_TICKS) return
		debugTickCounter = 0
		client.player?.sendMessage(Text.literal("[DoorDebug] $message"), false)
	}

	private fun drawDebugBox(context: WorldRenderContext, pos: BlockPos, cameraPos: Vec3d) {
		val minX = pos.x.toDouble()
		val minY = pos.y.toDouble()
		val minZ = pos.z.toDouble()
		val maxX = minX + 1.0
		val maxY = minY + 1.0
		val maxZ = minZ + 1.0
		val r = 255
		val g = 64
		val b = 64

		val matrices = context.matrices()
		matrices.push()
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
		val entry = matrices.peek()
		val layer = XclipsenRenderLayers.getXrayLine(DEBUG_LINE_WIDTH)
		val consumers = context.consumers()

		fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double) {
			val consumer = consumers.getBuffer(layer)
			consumer.vertex(entry, x1.toFloat(), y1.toFloat(), z1.toFloat()).color(r, g, b, DEBUG_ALPHA)
			consumer.vertex(entry, x2.toFloat(), y2.toFloat(), z2.toFloat()).color(r, g, b, DEBUG_ALPHA)
			(consumers as? VertexConsumerProvider.Immediate)?.draw(layer)
		}

		line(minX, minY, minZ, maxX, minY, minZ)
		line(maxX, minY, minZ, maxX, minY, maxZ)
		line(maxX, minY, maxZ, minX, minY, maxZ)
		line(minX, minY, maxZ, minX, minY, minZ)
		line(minX, maxY, minZ, maxX, maxY, minZ)
		line(maxX, maxY, minZ, maxX, maxY, maxZ)
		line(maxX, maxY, maxZ, minX, maxY, maxZ)
		line(minX, maxY, maxZ, minX, maxY, minZ)
		line(minX, minY, minZ, minX, maxY, minZ)
		line(maxX, minY, minZ, maxX, maxY, minZ)
		line(maxX, minY, maxZ, maxX, maxY, maxZ)
		line(minX, minY, maxZ, minX, maxY, maxZ)

		matrices.pop()
	}

	private data class DoorCube(
		val axis: Axis,
		val startPos: BlockPos,
		val positions: Set<BlockPos>,
		val airPositions: Set<BlockPos>,
		val closedCount: Int,
		val airCount: Int,
	)

	private enum class Axis {
		X,
		Z,
	}
}
