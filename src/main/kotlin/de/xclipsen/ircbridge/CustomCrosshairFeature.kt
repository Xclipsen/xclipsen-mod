package de.xclipsen.ircbridge

import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier

object CustomCrosshairFeature {
	const val GRID_SIZE = 7
	private const val CELL_SIZE = 2
	private const val CELL_GAP = 0
	private const val COLOR = 0xE0FFFFFF.toInt()
	private const val VANILLA_CROSSHAIR_SIZE = 15

	val defaultPattern: String = listOf(
		"0001000",
		"0001000",
		"0001000",
		"1111111",
		"0001000",
		"0001000",
		"0001000",
	).joinToString("/")

	fun shouldOverrideVanilla(): Boolean {
		val client = MinecraftClient.getInstance()
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		if (!config.customCrosshairModuleEnabled) {
			return false
		}
		if (client.options.hudHidden) {
			return false
		}
		val perspective = client.options.perspective
		return if (perspective.isFirstPerson) {
			config.customCrosshairShowInFirstPerson
		} else {
			config.customCrosshairVisibleInF5
		}
	}

	fun render(context: DrawContext) {
		val client = MinecraftClient.getInstance()
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.customCrosshairModuleEnabled || client.options.hudHidden) {
			return
		}
		val perspective = client.options.perspective
		if (perspective.isFirstPerson) {
			if (!config.customCrosshairShowInFirstPerson) {
				return
			}
			renderCustomCrosshair(context, config)
			return
		}

		if (!config.customCrosshairVisibleInF5) {
			return
		}
		if (config.customCrosshairShowInFirstPerson) {
			renderCustomCrosshair(context, config)
		} else {
			renderVanillaCrosshair(context)
		}
	}

	private fun renderCustomCrosshair(context: DrawContext, config: BridgeConfig) {
		val cells = decode(config.customCrosshairPattern)
		val step = CELL_SIZE + CELL_GAP
		val totalSize = GRID_SIZE * step - CELL_GAP
		val startX = (context.scaledWindowWidth - totalSize) / 2
		val startY = (context.scaledWindowHeight - totalSize) / 2

		for (row in 0 until GRID_SIZE) {
			for (column in 0 until GRID_SIZE) {
				if (!cells[row * GRID_SIZE + column]) continue
				val left = startX + column * step
				val top = startY + row * step
				context.fill(left, top, left + CELL_SIZE, top + CELL_SIZE, COLOR)
			}
		}
	}

	private fun renderVanillaCrosshair(context: DrawContext) {
		val left = (context.scaledWindowWidth - VANILLA_CROSSHAIR_SIZE) / 2
		val top = (context.scaledWindowHeight - VANILLA_CROSSHAIR_SIZE) / 2
		context.drawGuiTexture(RenderPipelines.CROSSHAIR, VANILLA_CROSSHAIR_TEXTURE, left, top, VANILLA_CROSSHAIR_SIZE, VANILLA_CROSSHAIR_SIZE)
	}

	fun normalizePattern(raw: String?): String {
		val rows = raw.orEmpty()
			.trim()
			.split('/')
			.filter { it.isNotBlank() }
		if (rows.size != GRID_SIZE) {
			return defaultPattern
		}
		if (rows.any { row -> row.length != GRID_SIZE || row.any { it != '0' && it != '1' } }) {
			return defaultPattern
		}
		return rows.joinToString("/")
	}

	fun decode(pattern: String?): BooleanArray {
		val normalized = normalizePattern(pattern)
		val rows = normalized.split('/')
		return BooleanArray(GRID_SIZE * GRID_SIZE) { index ->
			val row = index / GRID_SIZE
			val column = index % GRID_SIZE
			rows[row][column] == '1'
		}
	}

	fun toggleCell(pattern: String?, row: Int, column: Int): String {
		if (row !in 0 until GRID_SIZE || column !in 0 until GRID_SIZE) {
			return normalizePattern(pattern)
		}
		val cells = decode(pattern)
		val index = row * GRID_SIZE + column
		cells[index] = !cells[index]
		return encode(cells)
	}

	fun resetPattern(): String = defaultPattern

	private val VANILLA_CROSSHAIR_TEXTURE: Identifier = Identifier.ofVanilla("hud/crosshair")

	private fun encode(cells: BooleanArray): String {
		return (0 until GRID_SIZE).joinToString("/") { row ->
			buildString(GRID_SIZE) {
				for (column in 0 until GRID_SIZE) {
					append(if (cells[row * GRID_SIZE + column]) '1' else '0')
				}
			}
		}
	}
}
