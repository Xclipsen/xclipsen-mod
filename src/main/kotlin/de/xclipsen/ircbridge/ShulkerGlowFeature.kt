package de.xclipsen.ircbridge

import net.minecraft.entity.Entity
import net.minecraft.entity.mob.ShulkerEntity
import net.minecraft.entity.projectile.ShulkerBulletEntity

object ShulkerGlowFeature {
	fun shouldGlow(entity: Entity): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		return config.hideonleafHelperEnabled &&
			config.shulkerGlowEnabled &&
			(entity is ShulkerEntity || entity is ShulkerBulletEntity)
	}

	fun colorValue(entity: Entity): Int? {
		if (!shouldGlow(entity)) {
			return null
		}

		val config = XclipsenIrcBridgeClient.instance?.config() ?: return null
		val hex = if (entity is ShulkerBulletEntity) {
			config.shulkerProjectileGlowColorHex
		} else {
			config.shulkerGlowColorHex
		}
		return hex.trim()
			.removePrefix("#")
			.takeIf { HEX_COLOR_PATTERN.matches(it) }
			?.toInt(16)
	}

	private val HEX_COLOR_PATTERN = Regex("[0-9a-fA-F]{6}")
}
