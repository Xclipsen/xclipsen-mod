package de.xclipsen.ircbridge

object DungeonRedVignetteFeature {
	fun isEnabled(): Boolean {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return false
		return config.dungeonRedVignetteModuleEnabled && config.dungeonRedVignetteEnabled
	}
}
