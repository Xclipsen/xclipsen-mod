package de.xclipsen.ircbridge

import net.minecraft.entity.Entity
import net.minecraft.entity.mob.ShulkerEntity
import net.minecraft.entity.projectile.ShulkerBulletEntity

object ShulkerGlowFeature {
	fun shouldGlow(entity: Entity): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		return config.shulkerGlowEnabled && (entity is ShulkerEntity || entity is ShulkerBulletEntity)
	}
}
