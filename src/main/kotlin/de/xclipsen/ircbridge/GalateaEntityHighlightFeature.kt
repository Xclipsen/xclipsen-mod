package de.xclipsen.ircbridge

import net.minecraft.entity.Entity
import net.minecraft.entity.mob.ShulkerEntity

/**
 * Highlights the Hideonleaf mob (a named ShulkerEntity) on Galatea
 * with a user-configurable glow colour, independent of the general shulker glow.
 */
object GalateaEntityHighlightFeature {

    private val HEX_PATTERN = Regex("[0-9a-fA-F]{6}")

    fun isHideonleaf(entity: Entity): Boolean {
        if (entity !is ShulkerEntity) return false
        val name = entity.customName?.string ?: return false
        return name.contains("Hideonleaf", ignoreCase = true)
    }

    fun shouldGlow(entity: Entity): Boolean {
        if (!LocationTracker.isOnGalatea) return false
        val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
        return config.hideonleafHighlightEnabled && isHideonleaf(entity)
    }

    fun colorValue(entity: Entity): Int? {
        if (!shouldGlow(entity)) return null
        val config = XclipsenIrcBridgeClient.instance?.config() ?: return null
        val hex = config.hideonleafHighlightColorHex.trim().removePrefix("#")
        if (!HEX_PATTERN.matches(hex)) return null
        return hex.toInt(16)
    }
}
