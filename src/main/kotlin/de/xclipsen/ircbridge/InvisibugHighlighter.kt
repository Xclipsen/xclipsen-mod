package de.xclipsen.ircbridge

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.XclipsenRenderLayers
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.entity.EquipmentSlot
import net.minecraft.util.math.Vec3d

/**
 * Detects invisible Invisibugs on Galatea via CRIT particles and renders
 * an outline box at each detected position so they can be seen.
 *
 * Detection: when a CRIT particle arrives near a "completely default"
 * (invisible, nameless, unequipped) ArmorStand, that stand is tracked
 * as an Invisibug until it is removed from the world.
 */
object InvisibugHighlighter {

    private const val DETECT_RANGE = 5.0
    private const val BOX_HALF = 0.4
    private const val BOX_HEIGHT = 0.8
    private const val BEAM_HEIGHT = 16.0   // vertikaler Strahl nach oben (in Blocks)
    private const val LINE_WIDTH = 2.0
    private const val ALPHA = 200

    private val trackedStands = mutableSetOf<ArmorStandEntity>()
    private var wasOnGalatea = false

    // Called every tick from XclipsenIrcBridgeClient
    fun onTick() {
        val onGalatea = LocationTracker.isOnGalatea
        if (wasOnGalatea && !onGalatea) {
            trackedStands.clear()
        }
        wasOnGalatea = onGalatea
        trackedStands.removeIf { it.isRemoved }
    }

    // Called when the player changes world / disconnects
    fun onWorldChange() {
        trackedStands.clear()
    }

    // Called from InvisibugParticleMixin when a CRIT particle is received
    fun onCritParticle(x: Double, y: Double, z: Double) {
        val config = XclipsenIrcBridgeClient.instance?.config() ?: return
        if (!config.invisibugHighlightEnabled) return
        if (!LocationTracker.isOnGalatea) return

        val world = MinecraftClient.getInstance().world ?: return
        val particlePos = Vec3d(x, y, z)

        // Skip if we already track a stand near this position
        if (trackedStands.any { it.boundingBox.center.distanceTo(particlePos) < DETECT_RANGE }) return

        val box = net.minecraft.util.math.Box.of(particlePos, DETECT_RANGE * 2, DETECT_RANGE * 2, DETECT_RANGE * 2)
        val nearest = world
            .getEntitiesByClass(ArmorStandEntity::class.java, box) { isCompletelyDefault(it) && it !in trackedStands }
            .minByOrNull { it.boundingBox.center.distanceTo(particlePos) }
            ?: return

        trackedStands.add(nearest)
    }

    // Called from WorldRenderEvents.AFTER_ENTITIES
    fun onRender(context: WorldRenderContext) {
        val config = XclipsenIrcBridgeClient.instance?.config() ?: return
        if (!config.invisibugHighlightEnabled) return
        if (!LocationTracker.isOnGalatea) return
        if (trackedStands.isEmpty()) return

        val client = MinecraftClient.getInstance()
        if (client.player == null || client.options.hudHidden) return

        val cameraPos = context.gameRenderer().camera.pos
        val color = parseHexColor(config.invisibugHighlightColorHex)

        for (stand in trackedStands) {
            if (stand.isRemoved) continue
            drawBox(context, stand.boundingBox.center, cameraPos, color)
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun isCompletelyDefault(stand: ArmorStandEntity): Boolean {
        if (!stand.isInvisible) return false
        if (stand.hasCustomName()) return false
        if (stand.isCustomNameVisible) return false
        for (slot in EquipmentSlot.entries) {
            if (!stand.getEquippedStack(slot).isEmpty) return false
        }
        return true
    }

    private fun parseHexColor(hex: String): Int {
        return try {
            hex.trim().removePrefix("#").toInt(16)
        } catch (_: NumberFormatException) {
            0x00FFFF
        }
    }

    private fun drawBox(context: WorldRenderContext, center: Vec3d, cameraPos: Vec3d, color: Int) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val minX = center.x - BOX_HALF
        val maxX = center.x + BOX_HALF
        val minY = center.y
        val maxY = center.y + BOX_HEIGHT
        val minZ = center.z - BOX_HALF
        val maxZ = center.z + BOX_HALF

        val matrices = context.matrices()
        matrices.push()
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        val entry = matrices.peek()

        val layer = XclipsenRenderLayers.getXrayLine(LINE_WIDTH)
        val consumers = context.consumers()

        fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double) {
            val consumer = consumers.getBuffer(layer)
            consumer.vertex(entry, x1.toFloat(), y1.toFloat(), z1.toFloat()).color(r, g, b, ALPHA)
            consumer.vertex(entry, x2.toFloat(), y2.toFloat(), z2.toFloat()).color(r, g, b, ALPHA)
            (consumers as? VertexConsumerProvider.Immediate)?.draw(layer)
        }

        // Bottom face
        line(minX, minY, minZ, maxX, minY, minZ)
        line(maxX, minY, minZ, maxX, minY, maxZ)
        line(maxX, minY, maxZ, minX, minY, maxZ)
        line(minX, minY, maxZ, minX, minY, minZ)

        // Top face
        line(minX, maxY, minZ, maxX, maxY, minZ)
        line(maxX, maxY, minZ, maxX, maxY, maxZ)
        line(maxX, maxY, maxZ, minX, maxY, maxZ)
        line(minX, maxY, maxZ, minX, maxY, minZ)

        // Vertical edges
        line(minX, minY, minZ, minX, maxY, minZ)
        line(maxX, minY, minZ, maxX, maxY, minZ)
        line(maxX, minY, maxZ, maxX, maxY, maxZ)
        line(minX, minY, maxZ, minX, maxY, maxZ)

        // Vertikaler Strahl nach oben — von weit weg sichtbar
        val beamX = center.x
        val beamZ = center.z
        line(beamX, minY, beamZ, beamX, minY + BEAM_HEIGHT, beamZ)

        matrices.pop()
    }
}
