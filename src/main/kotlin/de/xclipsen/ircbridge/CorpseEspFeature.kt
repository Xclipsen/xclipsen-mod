package de.xclipsen.ircbridge

import de.xclipsen.ircbridge.FrozenCorpseDetector.FrozenCorpseType
import net.minecraft.entity.Entity
import net.minecraft.entity.decoration.ArmorStandEntity

object CorpseEspFeature {
	fun shouldGlow(entity: Entity): Boolean {
		return matchingCorpseType(entity) != null
	}

	fun colorValue(entity: Entity): Int? {
		return matchingCorpseType(entity)?.colorRgb
	}

	fun onTick(client: net.minecraft.client.MinecraftClient) = Unit

	fun onDisconnect() = Unit

	fun render(context: net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext) = Unit

	private fun matchingCorpseType(entity: Entity): FrozenCorpseType? {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return null
		if (!config.corpseEspModuleEnabled || !FrozenCorpseDetector.isInMineshaftArea()) {
			return null
		}
		val stand = entity as? ArmorStandEntity ?: return null
		if (!FrozenCorpseDetector.looksLikeCorpseStand(stand)) {
			return null
		}
		val type = FrozenCorpseDetector.resolveCorpseType(stand) ?: return null
		return type.takeIf { isTypeEnabled(config, it) }
	}

	private fun isTypeEnabled(config: BridgeConfig, type: FrozenCorpseType): Boolean {
		return when (type) {
			FrozenCorpseType.LAPIS -> config.corpseEspLapisEnabled
			FrozenCorpseType.TUNGSTEN -> config.corpseEspTungstenEnabled
			FrozenCorpseType.UMBER -> config.corpseEspUmberEnabled
			FrozenCorpseType.VANGUARD -> config.corpseEspVanguardEnabled
		}
	}
}
