package de.xclipsen.ircbridge

class BackendLinkCompleteRequest {
	@JvmField var playerName: String = ""
	@JvmField var code: String = ""
}

class BackendLinkStatusResponse {
	@JvmField var linked: Boolean = false
	@JvmField var discordUserId: String = ""
	@JvmField var discordDisplayName: String = ""
	@JvmField var playerName: String = ""
	@JvmField var error: String = ""
	@JvmField var minecraftUsernames: MutableList<String> = mutableListOf()
}

class BackendMessage {
	@JvmField var id: Long = 0L
	@JvmField var source: String = "discord"
	@JvmField var user: String = ""
	@JvmField var content: String = ""
	@JvmField var title: String = ""
	@JvmField var eventKey: String = ""
}

class BackendMessagesResponse {
	@JvmField var messages: MutableList<BackendMessage> = mutableListOf()
}

class BackendOutgoingMessage {
	@JvmField var type: String = "chat"
	@JvmField var playerName: String = ""
	@JvmField var message: String = ""
	@JvmField var forwardedPlayerName: String = ""
}

class BackendStatusSnapshot(
	@JvmField val state: String,
	@JvmField val lastHttpStatus: Int,
	@JvmField val lastSuccessAt: Long,
	@JvmField val lastPollAt: Long,
	@JvmField val lastMessageAt: Long,
	@JvmField val lastError: String,
)

class BridgeConfig {
	@JvmField var backendBaseUrl: String = "https://api.xclipsen.de"
	@JvmField var minigameBackendBaseUrl: String = "https://api.xclipsen.de"
	@JvmField var devModeEnabled: Boolean = false
	@JvmField var devBackendBaseUrl: String = "http://127.0.0.1:8765"
	@JvmField var ircServerBaseUrl: String = "http://127.0.0.1:8765"
	@JvmField var backendAuthToken: String = "change-me"
	@JvmField var backendPollIntervalMs: Long = 2000L
	@JvmField var checkForUpdatesEnabled: Boolean = true
	@JvmField var autoUpdateEnabled: Boolean = true
	@JvmField var linkedDiscordDisplayName: String = ""
	@JvmField var ircCommandFormat: String = "[IRC] <%player%> %message%"
	@JvmField var ircBridgeEnabled: Boolean = false
	@JvmField var coopChatRelayEnabled: Boolean = false
	@JvmField var chatModuleEnabled: Boolean = false
	@JvmField var chatImplosionHiderEnabled: Boolean = true
	@JvmField var hideonleafHelperEnabled: Boolean = false
	@JvmField var shulkerGlowEnabled: Boolean = true
	@JvmField var shulkerGlowColorHex: String = "#36C5F0"
	@JvmField var shulkerProjectileGlowColorHex: String = "#FF4D4D"
	@JvmField var shulkerTracerLineEnabled: Boolean = true
	@JvmField var shulkerTracerLineMode: Int = 1
	@JvmField var shulkerTracerLineColorHex: String = "#36C5F0"
	@JvmField var shulkerTracerLineWidth: Float = 2.0f
	@JvmField var hideonleafLostFightAlertEnabled: Boolean = true
	@JvmField var hideonleafShareDataEnabled: Boolean = true
	@JvmField var hideonleafLostFightAlertSoundId: String = "minecraft:block.note_block.pling"
	@JvmField var hideonleafLostFightAlertSoundVolume: Float = 1.0f
	@JvmField var hideonleafLostFightAlertSoundPitch: Float = 1.5f
	@JvmField var shardTrackerEnabled: Boolean = true
	@JvmField var purpleTerracottaHighlightModuleEnabled: Boolean = false
	@JvmField var purpleTerracottaHighlightColorHex: String = "#B06CFF"
	@JvmField var wormholeFinderModuleEnabled: Boolean = false
	@JvmField var wormholeDepartureAlertEnabled: Boolean = true
	@JvmField var wormholeDepartureAlertSoundId: String = "minecraft:entity.enderman.teleport"
	@JvmField var wormholeDepartureAlertSoundVolume: Float = 2.0f
	@JvmField var wormholeDepartureAlertSoundPitch: Float = 0.7f
	@JvmField var autoSprintModuleEnabled: Boolean = false
	@JvmField var autoSprintDisableWhenFullySubmerged: Boolean = true
	@JvmField var timeChangerEnabled: Boolean = false
	@JvmField var timeChangerMode: Int = 0
	@JvmField var auctionHouseModuleEnabled: Boolean = false
	@JvmField var auctionHouseAutoCopyUnderbidEnabled: Boolean = true
	@JvmField var autoCroesusModuleEnabled: Boolean = false
	@JvmField var experimentationTableModuleEnabled: Boolean = false
	@JvmField var autoExperimentsClickDelayMs: Int = 200
	@JvmField var autoExperimentsDelayVarietyMs: Int = 50
	@JvmField var autoExperimentsEnabled: Boolean = true
	@JvmField var autoExperimentsAutoClose: Boolean = true
	@JvmField var autoExperimentsAutoPairs: Boolean = true
	@JvmField var autoExperimentsSerumCount: Int = 0
	@JvmField var autoExperimentsGetMaxXp: Boolean = false
	@JvmField var autoExperimentsShowSolver: Boolean = false
	@JvmField var dungeonDoorModuleEnabled: Boolean = false
	@JvmField var dungeonDoorEnabled: Boolean = false
	@JvmField var dungeonDoorDebugEnabled: Boolean = false
	@JvmField var dungeonDoorMode: Int = 0
	@JvmField var dungeonRedVignetteModuleEnabled: Boolean = false
	@JvmField var dungeonRedVignetteEnabled: Boolean = false
	@JvmField var pestEspModuleEnabled: Boolean = false
	@JvmField var pestEspColorHex: String = "#7CFF6B"
	@JvmField var pestEspTracerEnabled: Boolean = true
	@JvmField var corpseEspModuleEnabled: Boolean = false
	@JvmField var corpseEspLapisEnabled: Boolean = true
	@JvmField var corpseEspTungstenEnabled: Boolean = true
	@JvmField var corpseEspUmberEnabled: Boolean = true
	@JvmField var corpseEspVanguardEnabled: Boolean = true
	@JvmField var mobModelModuleEnabled: Boolean = false
	@JvmField var mobModelEntityType: String = "minecraft:zombie"
	@JvmField var mobModelVariant: String = ""
	@JvmField var mobModelBaby: Boolean = false
	@JvmField var mobModelShowArmor: Boolean = true
	@JvmField var mobModelShowHeldItems: Boolean = true
	@JvmField var mobModelScale: Float = 1.0f
	@JvmField var inventoryPreviewModuleEnabled: Boolean = false
	@JvmField var inventoryPreviewShowArmor: Boolean = true
	@JvmField var customCrosshairModuleEnabled: Boolean = false
	@JvmField var customCrosshairShowInFirstPerson: Boolean = false
	@JvmField var customCrosshairVisibleInF5: Boolean = false
	@JvmField var customCrosshairPattern: String = CustomCrosshairFeature.defaultPattern
	@JvmField var silentDisconnectModuleEnabled: Boolean = false
	@JvmField var silentDisconnectLastStatus: String = "online"
	@JvmField var silentDisconnectRestorePending: Boolean = false
	@JvmField var chimeraBookDropEffectsModuleEnabled: Boolean = true
	@JvmField var chimeraBookDropEffectsSoundId: String = "minecraft:ui.toast.challenge_complete"
	@JvmField var chimeraBookDropEffectsSoundVolume: Float = 1.0f
	@JvmField var chimeraBookDropEffectsSoundPitch: Float = 1.0f
	@JvmField var m5ModuleEnabled: Boolean = false
	@JvmField var m5LividFinderEnabled: Boolean = true
	@JvmField var m5TracerEnabled: Boolean = true
	@JvmField var m5IceSprayTimerEnabled: Boolean = true
	@JvmField var m5RagAxeAlertEnabled: Boolean = true
	@JvmField var dungeonAutoKickModuleEnabled: Boolean = false
	@JvmField var dungeonAutoKickStatsDisplayEnabled: Boolean = true
	@JvmField var dungeonAutoKickSendKickLineEnabled: Boolean = true
	@JvmField var dungeonAutoKickAutoKickEnabled: Boolean = true
	@JvmField var dungeonAutoKickFloor: String = "7"
	@JvmField var dungeonAutoKickMasterMode: Boolean = true
	@JvmField var dungeonAutoKickMaxPbSeconds: Int = 400
	@JvmField var dungeonAutoKickMinSecretsThousands: Int = 0
	@JvmField var dungeonAutoKickMinMagicalPower: Int = 1300
	@JvmField var dungeonAutoKickApiOffKickEnabled: Boolean = false
	@JvmField var dungeonAutoKickInformKickedEnabled: Boolean = true
	@JvmField var dungeonAutoKickCacheEnabled: Boolean = true
	@JvmField var partyFinderGuiStatsEnabled: Boolean = true
	@JvmField var partyFinderHighlightsEnabled: Boolean = true
	@JvmField var partyFinderMemberCountEnabled: Boolean = true
	@JvmField var partyFinderRightClickEnabled: Boolean = true
	@JvmField var pickaxeAbilityCooldownModuleEnabled: Boolean = false
	@JvmField var pickaxeAbilityCooldownShowReady: Boolean = true
	@JvmField var pickaxeAbilityCooldownAlertEnabled: Boolean = false
	@JvmField var pickaxeAbilityCooldownAlertSoundId: String = SoundCatalog.defaultSoundId
	@JvmField var pickaxeAbilityCooldownAlertSoundVolume: Float = 1.0f
	@JvmField var pickaxeAbilityCooldownAlertSoundPitch: Float = 1.0f
	@JvmField var pickaxeAbilityCooldownAlertText: String = PickaxeAbilityCooldownFeature.DEFAULT_ALERT_TEXT
	@JvmField var fireFreezeModuleEnabled: Boolean = false
	@JvmField var fireFreezeMobTimerEnabled: Boolean = true
	@JvmField var fireFreezeFreezeTimerEnabled: Boolean = true
	@JvmField var fireFreezeStrongMobsOnly: Boolean = false
	@JvmField var fireFreezeBoxFrozenMobsEnabled: Boolean = true
	@JvmField var fireFreezeCustomCircleEnabled: Boolean = true
	@JvmField var fireFreezeCircleColorHex: String = "#00F5FF"
	@JvmField var fireFreezeCircleLineWidth: Float = 2.0f
	@JvmField var fireFreezeRefreezeAlertEnabled: Boolean = true
	@JvmField var fireFreezeRefreezeAlertSoundId: String = SoundCatalog.defaultSoundId
	@JvmField var fireFreezeRefreezeAlertSoundVolume: Float = 1.0f
	@JvmField var fireFreezeRefreezeAlertSoundPitch: Float = 1.0f
	@JvmField var mineshaftAutoWarpModuleEnabled: Boolean = false
	@JvmField var mineshaftAutoWarpCorpseRule: String = ""
	@JvmField var mineshaftAutoWarpDelayMs: Long = 3500L
	@JvmField var mineshaftAutoWarpWindowMs: Long = 55000L
	@JvmField var deploybleModuleEnabled: Boolean = false
	@JvmField var slayerModuleEnabled: Boolean = false
	@JvmField var slayerSpawnAnnouncerEnabled: Boolean = true
	@JvmField var slayerBlazePhaseDisplayEnabled: Boolean = true
	@JvmField var slayerBlazeColoredMobsEnabled: Boolean = false
	@JvmField var slayerBlazeAutoDaggerEnabled: Boolean = false
	@JvmField var slayerBlazeAutoDaggerDelayMaxTicks: Int = 2
	@JvmField var slayerBlazeAutoDaggerResetAfterBossEnabled: Boolean = false
	@JvmField var slayerBlazeAutoDaggerDebugEnabled: Boolean = false
	@JvmField var slayerSpawnAnnouncerText: String = SlayerFeature.DEFAULT_ANNOUNCER_TEXT
	@JvmField var slayerSpawnAnnouncerSoundId: String = SoundCatalog.defaultSoundId
	@JvmField var slayerSpawnAnnouncerSoundVolume: Float = 1.0f
	@JvmField var slayerSpawnAnnouncerSoundPitch: Float = 1.0f
	@JvmField var hudElements: MutableMap<String, HudElementPlacement> = mutableMapOf()

	fun copy(): BridgeConfig = BridgeConfig().also {
		it.backendBaseUrl = backendBaseUrl
		it.minigameBackendBaseUrl = minigameBackendBaseUrl
		it.devModeEnabled = devModeEnabled
		it.devBackendBaseUrl = devBackendBaseUrl
		it.ircServerBaseUrl = ircServerBaseUrl
		it.backendAuthToken = backendAuthToken
		it.backendPollIntervalMs = backendPollIntervalMs
		it.checkForUpdatesEnabled = checkForUpdatesEnabled
		it.autoUpdateEnabled = autoUpdateEnabled
		it.linkedDiscordDisplayName = linkedDiscordDisplayName
		it.ircCommandFormat = ircCommandFormat
		it.ircBridgeEnabled = ircBridgeEnabled
		it.coopChatRelayEnabled = coopChatRelayEnabled
		it.chatModuleEnabled = chatModuleEnabled
		it.chatImplosionHiderEnabled = chatImplosionHiderEnabled
		it.hideonleafHelperEnabled = hideonleafHelperEnabled
		it.shulkerGlowEnabled = shulkerGlowEnabled
		it.shulkerGlowColorHex = shulkerGlowColorHex
		it.shulkerProjectileGlowColorHex = shulkerProjectileGlowColorHex
		it.shulkerTracerLineEnabled = shulkerTracerLineEnabled
		it.shulkerTracerLineMode = shulkerTracerLineMode
		it.shulkerTracerLineColorHex = shulkerTracerLineColorHex
		it.shulkerTracerLineWidth = shulkerTracerLineWidth
		it.hideonleafLostFightAlertEnabled = hideonleafLostFightAlertEnabled
		it.hideonleafShareDataEnabled = hideonleafShareDataEnabled
		it.hideonleafLostFightAlertSoundId = hideonleafLostFightAlertSoundId
		it.hideonleafLostFightAlertSoundVolume = hideonleafLostFightAlertSoundVolume
		it.hideonleafLostFightAlertSoundPitch = hideonleafLostFightAlertSoundPitch
		it.shardTrackerEnabled = shardTrackerEnabled
		it.purpleTerracottaHighlightModuleEnabled = purpleTerracottaHighlightModuleEnabled
		it.purpleTerracottaHighlightColorHex = purpleTerracottaHighlightColorHex
		it.wormholeFinderModuleEnabled = wormholeFinderModuleEnabled
		it.wormholeDepartureAlertEnabled = wormholeDepartureAlertEnabled
		it.wormholeDepartureAlertSoundId = wormholeDepartureAlertSoundId
		it.wormholeDepartureAlertSoundVolume = wormholeDepartureAlertSoundVolume
		it.wormholeDepartureAlertSoundPitch = wormholeDepartureAlertSoundPitch
		it.autoSprintModuleEnabled = autoSprintModuleEnabled
		it.autoSprintDisableWhenFullySubmerged = autoSprintDisableWhenFullySubmerged
		it.timeChangerEnabled = timeChangerEnabled
		it.timeChangerMode = timeChangerMode
		it.auctionHouseModuleEnabled = auctionHouseModuleEnabled
		it.auctionHouseAutoCopyUnderbidEnabled = auctionHouseAutoCopyUnderbidEnabled
		it.autoCroesusModuleEnabled = autoCroesusModuleEnabled
		it.experimentationTableModuleEnabled = experimentationTableModuleEnabled
		it.autoExperimentsClickDelayMs = autoExperimentsClickDelayMs
		it.autoExperimentsDelayVarietyMs = autoExperimentsDelayVarietyMs
		it.autoExperimentsEnabled = autoExperimentsEnabled
		it.autoExperimentsAutoClose = autoExperimentsAutoClose
		it.autoExperimentsAutoPairs = autoExperimentsAutoPairs
		it.autoExperimentsSerumCount = autoExperimentsSerumCount
		it.autoExperimentsGetMaxXp = autoExperimentsGetMaxXp
		it.autoExperimentsShowSolver = autoExperimentsShowSolver
		it.dungeonDoorModuleEnabled = dungeonDoorModuleEnabled
		it.dungeonDoorEnabled = dungeonDoorEnabled
		it.dungeonDoorDebugEnabled = dungeonDoorDebugEnabled
		it.dungeonDoorMode = dungeonDoorMode
		it.dungeonRedVignetteModuleEnabled = dungeonRedVignetteModuleEnabled
		it.dungeonRedVignetteEnabled = dungeonRedVignetteEnabled
		it.pestEspModuleEnabled = pestEspModuleEnabled
		it.pestEspColorHex = pestEspColorHex
		it.pestEspTracerEnabled = pestEspTracerEnabled
		it.corpseEspModuleEnabled = corpseEspModuleEnabled
		it.corpseEspLapisEnabled = corpseEspLapisEnabled
		it.corpseEspTungstenEnabled = corpseEspTungstenEnabled
		it.corpseEspUmberEnabled = corpseEspUmberEnabled
		it.corpseEspVanguardEnabled = corpseEspVanguardEnabled
		it.mobModelModuleEnabled = mobModelModuleEnabled
		it.mobModelEntityType = mobModelEntityType
		it.mobModelVariant = mobModelVariant
		it.mobModelBaby = mobModelBaby
		it.mobModelShowArmor = mobModelShowArmor
		it.mobModelShowHeldItems = mobModelShowHeldItems
		it.mobModelScale = mobModelScale
		it.inventoryPreviewModuleEnabled = inventoryPreviewModuleEnabled
		it.inventoryPreviewShowArmor = inventoryPreviewShowArmor
		it.customCrosshairModuleEnabled = customCrosshairModuleEnabled
		it.customCrosshairShowInFirstPerson = customCrosshairShowInFirstPerson
		it.customCrosshairVisibleInF5 = customCrosshairVisibleInF5
		it.customCrosshairPattern = customCrosshairPattern
		it.silentDisconnectModuleEnabled = silentDisconnectModuleEnabled
		it.silentDisconnectLastStatus = silentDisconnectLastStatus
		it.silentDisconnectRestorePending = silentDisconnectRestorePending
		it.chimeraBookDropEffectsModuleEnabled = chimeraBookDropEffectsModuleEnabled
		it.chimeraBookDropEffectsSoundId = chimeraBookDropEffectsSoundId
		it.chimeraBookDropEffectsSoundVolume = chimeraBookDropEffectsSoundVolume
		it.chimeraBookDropEffectsSoundPitch = chimeraBookDropEffectsSoundPitch
		it.m5ModuleEnabled = m5ModuleEnabled
		it.m5LividFinderEnabled = m5LividFinderEnabled
		it.m5TracerEnabled = m5TracerEnabled
		it.m5IceSprayTimerEnabled = m5IceSprayTimerEnabled
		it.m5RagAxeAlertEnabled = m5RagAxeAlertEnabled
		it.dungeonAutoKickModuleEnabled = dungeonAutoKickModuleEnabled
		it.dungeonAutoKickStatsDisplayEnabled = dungeonAutoKickStatsDisplayEnabled
		it.dungeonAutoKickSendKickLineEnabled = dungeonAutoKickSendKickLineEnabled
		it.dungeonAutoKickAutoKickEnabled = dungeonAutoKickAutoKickEnabled
		it.dungeonAutoKickFloor = dungeonAutoKickFloor
		it.dungeonAutoKickMasterMode = dungeonAutoKickMasterMode
		it.dungeonAutoKickMaxPbSeconds = dungeonAutoKickMaxPbSeconds
		it.dungeonAutoKickMinSecretsThousands = dungeonAutoKickMinSecretsThousands
		it.dungeonAutoKickMinMagicalPower = dungeonAutoKickMinMagicalPower
		it.dungeonAutoKickApiOffKickEnabled = dungeonAutoKickApiOffKickEnabled
		it.dungeonAutoKickInformKickedEnabled = dungeonAutoKickInformKickedEnabled
		it.dungeonAutoKickCacheEnabled = dungeonAutoKickCacheEnabled
		it.partyFinderGuiStatsEnabled = partyFinderGuiStatsEnabled
		it.partyFinderHighlightsEnabled = partyFinderHighlightsEnabled
		it.partyFinderMemberCountEnabled = partyFinderMemberCountEnabled
		it.partyFinderRightClickEnabled = partyFinderRightClickEnabled
		it.pickaxeAbilityCooldownModuleEnabled = pickaxeAbilityCooldownModuleEnabled
		it.pickaxeAbilityCooldownShowReady = pickaxeAbilityCooldownShowReady
		it.pickaxeAbilityCooldownAlertEnabled = pickaxeAbilityCooldownAlertEnabled
		it.pickaxeAbilityCooldownAlertSoundId = pickaxeAbilityCooldownAlertSoundId
		it.pickaxeAbilityCooldownAlertSoundVolume = pickaxeAbilityCooldownAlertSoundVolume
		it.pickaxeAbilityCooldownAlertSoundPitch = pickaxeAbilityCooldownAlertSoundPitch
		it.pickaxeAbilityCooldownAlertText = pickaxeAbilityCooldownAlertText
		it.fireFreezeModuleEnabled = fireFreezeModuleEnabled
		it.fireFreezeMobTimerEnabled = fireFreezeMobTimerEnabled
		it.fireFreezeFreezeTimerEnabled = fireFreezeFreezeTimerEnabled
		it.fireFreezeStrongMobsOnly = fireFreezeStrongMobsOnly
		it.fireFreezeBoxFrozenMobsEnabled = fireFreezeBoxFrozenMobsEnabled
		it.fireFreezeCustomCircleEnabled = fireFreezeCustomCircleEnabled
		it.fireFreezeCircleColorHex = fireFreezeCircleColorHex
		it.fireFreezeCircleLineWidth = fireFreezeCircleLineWidth
		it.fireFreezeRefreezeAlertEnabled = fireFreezeRefreezeAlertEnabled
		it.fireFreezeRefreezeAlertSoundId = fireFreezeRefreezeAlertSoundId
		it.fireFreezeRefreezeAlertSoundVolume = fireFreezeRefreezeAlertSoundVolume
		it.fireFreezeRefreezeAlertSoundPitch = fireFreezeRefreezeAlertSoundPitch
		it.mineshaftAutoWarpModuleEnabled = mineshaftAutoWarpModuleEnabled
		it.mineshaftAutoWarpCorpseRule = mineshaftAutoWarpCorpseRule
		it.mineshaftAutoWarpDelayMs = mineshaftAutoWarpDelayMs
		it.mineshaftAutoWarpWindowMs = mineshaftAutoWarpWindowMs
		it.deploybleModuleEnabled = deploybleModuleEnabled
		it.slayerModuleEnabled = slayerModuleEnabled
		it.slayerSpawnAnnouncerEnabled = slayerSpawnAnnouncerEnabled
		it.slayerBlazePhaseDisplayEnabled = slayerBlazePhaseDisplayEnabled
		it.slayerBlazeColoredMobsEnabled = slayerBlazeColoredMobsEnabled
		it.slayerBlazeAutoDaggerEnabled = slayerBlazeAutoDaggerEnabled
		it.slayerBlazeAutoDaggerDelayMaxTicks = slayerBlazeAutoDaggerDelayMaxTicks
		it.slayerBlazeAutoDaggerResetAfterBossEnabled = slayerBlazeAutoDaggerResetAfterBossEnabled
		it.slayerBlazeAutoDaggerDebugEnabled = slayerBlazeAutoDaggerDebugEnabled
		it.slayerSpawnAnnouncerText = slayerSpawnAnnouncerText
		it.slayerSpawnAnnouncerSoundId = slayerSpawnAnnouncerSoundId
		it.slayerSpawnAnnouncerSoundVolume = slayerSpawnAnnouncerSoundVolume
		it.slayerSpawnAnnouncerSoundPitch = slayerSpawnAnnouncerSoundPitch
		it.hudElements = hudElements.mapValues { entry -> entry.value.copy() }.toMutableMap()
	}
}

class HudElementPlacement() {
	@JvmField var x: Float = -1f
	@JvmField var y: Float = -1f
	@JvmField var scale: Float = 1f

	constructor(x: Float, y: Float, scale: Float) : this() {
		this.x = x
		this.y = y
		this.scale = scale
	}

	fun copy(): HudElementPlacement = HudElementPlacement(x, y, scale)
}

class HealthResponse {
	@JvmField var status: String = ""
}

class BackendHideonleafTrackedItem {
	@JvmField var amount: Long = 0L
	@JvmField var timesDropped: Long = 0L
	@JvmField var pricePerUnit: Double = 0.0
}

class BackendHideonleafStatsUpload {
	@JvmField var playerName: String = ""
	@JvmField var kills: Long = 0L
	@JvmField var totalShards: Long = 0L
	@JvmField var totalProfit: Double = 0.0
	@JvmField var profitPerHour: Double = 0.0
	@JvmField var totalDurationMs: Long = 0L
	@JvmField var updatedAt: Long = 0L
	@JvmField var items: MutableMap<String, BackendHideonleafTrackedItem> = mutableMapOf()
}

class BackendMobModelState {
	@JvmField var minecraftUsername: String = ""
	@JvmField var enabled: Boolean = false
	@JvmField var entityType: String = "minecraft:zombie"
	@JvmField var variant: String = ""
	@JvmField var baby: Boolean = false
	@JvmField var showArmor: Boolean = true
	@JvmField var showHeldItems: Boolean = true
	@JvmField var scale: Float = 1.0f
	@JvmField var updatedAt: Long = 0L
}

class BackendMobModelStatesResponse {
	@JvmField var states: MutableList<BackendMobModelState> = mutableListOf()
}

/** Response from GET /api/skyblock/prices */
class BackendPricePayload {
	@JvmField var prices: Map<String, ItemPrice> = emptyMap()
}

class ItemPrice {
	/** Instant-buy price per unit from the Bazaar buy-summary. */
	@JvmField var buyPrice: Double = 0.0
	/** Instant-sell price per unit from the Bazaar sell-summary. */
	@JvmField var sellPrice: Double = 0.0
	/** Unix-ms timestamp when the Bot last fetched this from Hypixel. */
	@JvmField var lastUpdated: Long = 0L
}

class BackendDungeonStatsResponse {
	@JvmField var ok: Boolean = false
	@JvmField var username: String = ""
	@JvmField var uuid: String = ""
	@JvmField var profileName: String = ""
	@JvmField var selected: Boolean = false
	@JvmField var stats: BackendDungeonStats = BackendDungeonStats()
	@JvmField var cachedAt: Long = 0L
	@JvmField var code: String = ""
	@JvmField var error: String = ""
}

class BackendDungeonPlayersResponse {
	@JvmField var ok: Boolean = false
	@JvmField var players: MutableMap<String, BackendDungeonStatsResponse> = mutableMapOf()
	@JvmField var error: String = ""
}

class BackendDungeonStats {
	@JvmField var catacombsLevel: Double = 0.0
	@JvmField var catacombsXp: Double = 0.0
	@JvmField var secrets: Long = 0L
	@JvmField var bloodMobKills: Long = 0L
	@JvmField var watcherKills: Long = 0L
	@JvmField var adjustedSecrets: Long = 0L
	@JvmField var averageSecrets: Double = 0.0
	@JvmField var totalRuns: Int = 0
	@JvmField var magicalPower: Int = 0
	@JvmField var inventoryApi: Boolean = false
	@JvmField var classAverage: Double = 0.0
	@JvmField var totalClassXp: Double = 0.0
	@JvmField var tunings: MutableList<String> = mutableListOf()
	@JvmField var classes: MutableMap<String, BackendDungeonClassStats> = mutableMapOf()
	@JvmField var floors: BackendDungeonFloors = BackendDungeonFloors()
	@JvmField var armor: MutableList<BackendDungeonArmorPiece> = mutableListOf()
	@JvmField var missingItems: MutableList<BackendDungeonMissingItem> = mutableListOf()
}

class BackendDungeonClassStats {
	@JvmField var level: Double = 0.0
	@JvmField var xp: Double = 0.0
}

class BackendDungeonFloors {
	@JvmField var normal: MutableMap<String, BackendDungeonFloorStats> = mutableMapOf()
	@JvmField var master: MutableMap<String, BackendDungeonFloorStats> = mutableMapOf()
}

class BackendDungeonFloorStats {
	@JvmField var sPbMs: Long = 0L
	@JvmField var sPlusPbMs: Long = 0L
	@JvmField var bestTimeMs: Long = 0L
	@JvmField var completions: Int = 0
}

class BackendDungeonArmorPiece {
	@JvmField var slot: String = ""
	@JvmField var displayName: String = ""
	@JvmField var lore: MutableList<String> = mutableListOf()
}

class BackendDungeonMissingItem {
	@JvmField var name: String = ""
	@JvmField var shortName: String = ""
}

object TextFormatter {
	@JvmStatic
	fun apply(template: String, vararg replacements: String): String {
		var result = template
		var index = 0
		while (index + 1 < replacements.size) {
			result = result.replace(replacements[index], replacements[index + 1])
			index += 2
		}

		return result
	}
}
