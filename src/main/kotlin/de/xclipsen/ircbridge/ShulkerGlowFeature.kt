package de.xclipsen.ircbridge

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.Shulker
import net.minecraft.world.entity.projectile.ShulkerBullet

object ShulkerGlowFeature {
	fun shouldGlow(entity: Entity): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		return config.hideonleafHelperEnabled &&
			config.shulkerGlowEnabled &&
			(entity is Shulker || entity is ShulkerBullet)
	}

	fun colorValue(entity: Entity): Int? {
		if (!shouldGlow(entity)) {
			return null
		}

		val config = XclipsenIrcBridgeClient.instance?.config() ?: return null
		val hex = if (entity is ShulkerBullet) {
			config.shulkerProjectileGlowColorHex
		} else {
			config.shulkerGlowColorHex
		}
		return ClientColor.parseRgb(hex)
	}
}
