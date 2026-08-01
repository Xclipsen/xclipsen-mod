package de.xclipsen.ircbridge

import com.autocroesus.config.AcConfig
import com.autocroesus.config.AcDataStore
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.input.KeyInput
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import java.awt.Color
import java.io.IOException
import java.util.Locale

class XclipsenConfigScreen(
	private val parent: Screen?,
	private val mod: XclipsenIrcBridgeClient,
) : Screen(Text.literal("Xclipsen Settings")) {
	private var workingCopy: BridgeConfig = copyOf(mod.config())
	private var workingAutoCroesusConfig: AcConfig = copyOf(AcDataStore.config)
	private var selectedSection = ConfigSection.SETUP
	private var openedSection: ConfigSection? = null
	private var openColorField: ConfigField? = null
	private var draggingColorPicker: ColorPickerDragTarget? = null
	private var soundDropdownOpen = false
	private var soundScrollOffset = 0
	private var mobModelDropdownOpen = false
	private var mobModelScrollOffset = 0
	private var mobModelVariantDropdownOpen = false
	private var mobModelVariantScrollOffset = 0
	private var dungeonAutoKickFloorDropdownOpen = false
	private var dungeonAutoKickFloorScrollOffset = 0
	private var draggingSlider: SliderDragTarget? = null
	private var pickaxeAlertExpanded = false
	private var slayerBlazeExpanded = true
	private var slayerMiscExpanded = true
	private var awaitingHideonleafResetConfirmation = false
	private var statusMessage: Text = Text.empty()
	private val colorPickerOpen: Boolean
		get() = openColorField != null

	private lateinit var searchField: TextFieldWidget
	private lateinit var ircServerBaseUrlField: TextFieldWidget
	private lateinit var backendAuthTokenField: TextFieldWidget
	private lateinit var backendPollIntervalField: TextFieldWidget
	private lateinit var ircFormatField: TextFieldWidget
	private lateinit var autoExperimentsClickDelayField: TextFieldWidget
	private lateinit var autoExperimentsDelayVarietyField: TextFieldWidget
	private lateinit var autoExperimentsSerumCountField: TextFieldWidget
	private lateinit var autoCroesusClickDelayField: TextFieldWidget
	private lateinit var autoCroesusKismetProfitField: TextFieldWidget
	private lateinit var autoCroesusKismetFloorsField: TextFieldWidget
	private lateinit var autoCroesusChestKeyProfitField: TextFieldWidget
	private lateinit var shulkerGlowColorHexField: TextFieldWidget
	private lateinit var shulkerProjectileGlowColorHexField: TextFieldWidget
	private lateinit var shulkerTracerLineColorHexField: TextFieldWidget
	private lateinit var purpleTerracottaHighlightColorHexField: TextFieldWidget
	private lateinit var pestEspColorHexField: TextFieldWidget
	private lateinit var fireFreezeCircleColorHexField: TextFieldWidget
	private lateinit var mobModelEntityTypeField: TextFieldWidget
	private lateinit var mobModelVariantField: TextFieldWidget
	private lateinit var pickaxeAlertTextField: TextFieldWidget
	private lateinit var slayerAnnouncerTextField: TextFieldWidget
	private lateinit var mineshaftAutoWarpRuleField: TextFieldWidget
	private lateinit var mineshaftAutoWarpDelayField: TextFieldWidget
	private lateinit var mineshaftAutoWarpWindowField: TextFieldWidget
	private lateinit var dungeonAutoKickMaxPbField: TextFieldWidget
	private lateinit var dungeonAutoKickMinSecretsField: TextFieldWidget
	private lateinit var dungeonAutoKickMinMpField: TextFieldWidget
	private lateinit var lostFightSoundSearchField: TextFieldWidget
	private lateinit var pickaxeAlertSoundSearchField: TextFieldWidget
	private lateinit var fireFreezeAlertSoundSearchField: TextFieldWidget
	private lateinit var chimeraDropSoundSearchField: TextFieldWidget
	private lateinit var slayerAnnouncerSoundSearchField: TextFieldWidget

	private val fields = mutableMapOf<ConfigField, TextFieldWidget>()
	private val sectionRows = listOf(
		ConfigPanel("MODULES", listOf(ConfigSection.IRC_BRIDGE, ConfigSection.CHAT, ConfigSection.TIME_CHANGER, ConfigSection.AUCTION_HOUSE, ConfigSection.SLAYER)),
		ConfigPanel("MISC", listOf(ConfigSection.CHIMERA_DROP, ConfigSection.DEPLOYBLE, ConfigSection.WORMHOLE_FINDER, ConfigSection.AUTO_SPRINT, ConfigSection.PEST_ESP, ConfigSection.CORPSE_ESP, ConfigSection.MOB_MODEL, ConfigSection.CROSSHAIR, ConfigSection.INVENTORY_PREVIEW, ConfigSection.SILENT_DISCONNECT, ConfigSection.PICKAXE_COOLDOWN, ConfigSection.FIRE_FREEZE, ConfigSection.MINESHAFT_AUTOWARP)),
		ConfigPanel("DUNGEON", listOf(ConfigSection.M5, ConfigSection.DUNGEON_AUTOKICK, ConfigSection.AUTO_CROESUS, ConfigSection.EXPERIMENTS, ConfigSection.DOOR, ConfigSection.RED_VIGNETTE)),
		ConfigPanel("GALATEA", listOf(ConfigSection.HIDEONLEAF_HELPER, ConfigSection.PURPLE_TERRACOTTA, ConfigSection.FLOOR_DROP_ESP)),
		ConfigPanel("SYSTEM", listOf(ConfigSection.SETUP, ConfigSection.STATUS)),
	)

	override fun init() {
		super.init()

		fields.clear()
		searchField = addField(0, 0, 150, "", "Search...")
		searchField.setTextShadow(false)

		ircServerBaseUrlField = registerField(ConfigField.IRC_SERVER_URL, workingCopy.ircServerBaseUrl, "http://127.0.0.1:8765")
		backendAuthTokenField = registerField(ConfigField.AUTH_TOKEN, workingCopy.backendAuthToken, "shared secret")
		backendPollIntervalField = registerField(ConfigField.POLL_INTERVAL, workingCopy.backendPollIntervalMs.toString(), "minimum 500")
		ircFormatField = registerField(ConfigField.IRC_FORMAT, workingCopy.ircCommandFormat, "[IRC] <%player%> %message%")
		autoExperimentsClickDelayField = registerField(ConfigField.AUTO_EXPERIMENTS_CLICK_DELAY, workingCopy.autoExperimentsClickDelayMs.toString(), "200")
		autoExperimentsDelayVarietyField = registerField(ConfigField.AUTO_EXPERIMENTS_DELAY_VARIETY, workingCopy.autoExperimentsDelayVarietyMs.toString(), "50")
		autoExperimentsSerumCountField = registerField(ConfigField.AUTO_EXPERIMENTS_SERUM_COUNT, workingCopy.autoExperimentsSerumCount.toString(), "0")
		autoCroesusClickDelayField = registerField(ConfigField.AUTO_CROESUS_CLICK_DELAY, workingAutoCroesusConfig.minClickDelay.toString(), "500")
		autoCroesusKismetProfitField = registerField(ConfigField.AUTO_CROESUS_KISMET_MIN_PROFIT, workingAutoCroesusConfig.kismetMinProfit.toString(), "2000000")
		autoCroesusKismetFloorsField = registerField(ConfigField.AUTO_CROESUS_KISMET_FLOORS, workingAutoCroesusConfig.kismetFloors.joinToString(", "), "M7, F7")
		autoCroesusChestKeyProfitField = registerField(ConfigField.AUTO_CROESUS_CHEST_KEY_MIN_PROFIT, workingAutoCroesusConfig.chestKeyMinProfit.toString(), "200000")
		shulkerGlowColorHexField = registerField(ConfigField.SHULKER_GLOW_COLOR, workingCopy.shulkerGlowColorHex, "#36C5F0")
		shulkerProjectileGlowColorHexField = registerField(ConfigField.SHULKER_PROJECTILE_GLOW_COLOR, workingCopy.shulkerProjectileGlowColorHex, "#FF4D4D")
		shulkerTracerLineColorHexField = registerField(ConfigField.SHULKER_TRACER_LINE_COLOR, workingCopy.shulkerTracerLineColorHex, "#36C5F0")
		purpleTerracottaHighlightColorHexField = registerField(ConfigField.PURPLE_TERRACOTTA_HIGHLIGHT_COLOR, workingCopy.purpleTerracottaHighlightColorHex, "#B06CFF")
		pestEspColorHexField = registerField(ConfigField.PEST_ESP_COLOR, workingCopy.pestEspColorHex, "#7CFF6B")
		fireFreezeCircleColorHexField = registerField(ConfigField.FIRE_FREEZE_CIRCLE_COLOR, workingCopy.fireFreezeCircleColorHex, "#00F5FF")
		mobModelEntityTypeField = registerField(ConfigField.MOB_MODEL_ENTITY_TYPE, "", "Search mobs...")
		mobModelVariantField = registerField(ConfigField.MOB_MODEL_VARIANT, "", "Search variants...")
		pickaxeAlertTextField = registerField(ConfigField.PICKAXE_ALERT_TEXT, workingCopy.pickaxeAbilityCooldownAlertText, PickaxeAbilityCooldownFeature.DEFAULT_ALERT_TEXT)
		slayerAnnouncerTextField = registerField(ConfigField.SLAYER_ANNOUNCER_TEXT, workingCopy.slayerSpawnAnnouncerText, SlayerFeature.DEFAULT_ANNOUNCER_TEXT)
		mineshaftAutoWarpRuleField = registerField(ConfigField.MINESHAFT_AUTOWARP_RULE, workingCopy.mineshaftAutoWarpCorpseRule, "lapis 2; vanguard 1")
		mineshaftAutoWarpDelayField = registerField(ConfigField.MINESHAFT_AUTOWARP_DELAY, workingCopy.mineshaftAutoWarpDelayMs.toString(), "3500")
		mineshaftAutoWarpWindowField = registerField(ConfigField.MINESHAFT_AUTOWARP_WINDOW, workingCopy.mineshaftAutoWarpWindowMs.toString(), "55000")
		dungeonAutoKickMaxPbField = registerField(ConfigField.DUNGEON_AUTOKICK_MAX_PB, workingCopy.dungeonAutoKickMaxPbSeconds.toString(), "400")
		dungeonAutoKickMinSecretsField = registerField(ConfigField.DUNGEON_AUTOKICK_MIN_SECRETS, workingCopy.dungeonAutoKickMinSecretsThousands.toString(), "0")
		dungeonAutoKickMinMpField = registerField(ConfigField.DUNGEON_AUTOKICK_MIN_MP, workingCopy.dungeonAutoKickMinMagicalPower.toString(), "1300")
		lostFightSoundSearchField = addField(0, 0, 150, "", "Search sound...")
		pickaxeAlertSoundSearchField = addField(0, 0, 150, "", "Search sound...")
		fireFreezeAlertSoundSearchField = addField(0, 0, 150, "", "Search sound...")
		chimeraDropSoundSearchField = addField(0, 0, 150, "", "Search sound...")
		slayerAnnouncerSoundSearchField = addField(0, 0, 150, "", "Search sound...")
		layoutWidgets()
	}

	override fun close() {
		readWorkingCopyFromFields(updateStatus = false)?.let {
			try {
				mod.saveAndApplyConfig(it)
			} catch (_: IOException) {
			}
		}
		persistAutoCroesusConfig()
		client?.setScreen(parent)
	}

	override fun shouldPause(): Boolean = false

	override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
		context.fillGradient(0, 0, width, height, 0x88000000.toInt(), 0xCC000000.toInt())

		drawPanels(context, mouseX, mouseY)
		drawSearch(context)
		drawSettingsMenu(context, mouseX, mouseY)
		drawTooltip(context, mouseX, mouseY)

		super.render(context, mouseX, mouseY, delta)
	}

	override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
		val mouseX = click.x().toInt()
		val mouseY = click.y().toInt()
		val button = click.button()
		if (button < 0) {
			return false
		}

		openedSection?.let { section ->
			val menu = settingsBounds()
			if (!menu.contains(mouseX, mouseY)) {
				closeOpenedSection()
				return true
			}

			if (handleSettingsClick(section, mouseX, mouseY, button)) {
				return true
			}

			super.mouseClicked(click, doubled)
			return true
		}

		val clickedSection = sectionAt(click.x().toInt(), click.y().toInt())
		if (clickedSection != null) {
			readWorkingCopyFromFields(updateStatus = false)
			selectedSection = clickedSection
			statusMessage = Text.empty()
			if (button == LEFT_MOUSE_BUTTON && clickedSection.toggleable) {
				toggleModule(clickedSection)
			} else if (button == LEFT_MOUSE_BUTTON && !clickedSection.toggleable) {
				openedSection = clickedSection
				openColorField = null
				soundDropdownOpen = false
				mobModelDropdownOpen = false
				mobModelVariantDropdownOpen = false
				dungeonAutoKickFloorDropdownOpen = false
				draggingColorPicker = null
				draggingSlider = null
			} else if (button == RIGHT_MOUSE_BUTTON) {
				openedSection = clickedSection
				openColorField = null
				soundDropdownOpen = false
				mobModelDropdownOpen = false
				mobModelVariantDropdownOpen = false
				dungeonAutoKickFloorDropdownOpen = false
				draggingColorPicker = null
				draggingSlider = null
			}
			layoutWidgets()
			return true
		}

		return super.mouseClicked(click, doubled)
	}

	override fun keyPressed(input: KeyInput): Boolean {
		if (input.key() == GLFW.GLFW_KEY_ESCAPE && openedSection != null) {
			closeOpenedSection()
			return true
		}

		return super.keyPressed(input)
	}

	override fun mouseDragged(click: Click, offsetX: Double, offsetY: Double): Boolean {
		if (click.button() < 0) {
			return false
		}

		val dragTarget = draggingColorPicker
		val sliderTarget = draggingSlider
		if ((openedSection == ConfigSection.HIDEONLEAF_HELPER || openedSection == ConfigSection.PICKAXE_COOLDOWN || openedSection == ConfigSection.FIRE_FREEZE || openedSection == ConfigSection.MOB_MODEL || openedSection == ConfigSection.CHIMERA_DROP || openedSection == ConfigSection.SLAYER) && sliderTarget != null) {
			updateSliderFromMouse(click.x().toInt(), sliderTarget)
			return true
		}

		if ((openedSection == ConfigSection.HIDEONLEAF_HELPER || openedSection == ConfigSection.PURPLE_TERRACOTTA || openedSection == ConfigSection.PEST_ESP || openedSection == ConfigSection.FIRE_FREEZE) && dragTarget != null) {
			updateColorFromPicker(click.x().toInt(), click.y().toInt(), dragTarget)
			return true
		}

		return super.mouseDragged(click, offsetX, offsetY)
	}

	override fun mouseReleased(click: Click): Boolean {
		draggingColorPicker = null
		draggingSlider = null
		if (click.button() < 0) {
			return false
		}
		return super.mouseReleased(click)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
		if ((openedSection == ConfigSection.HIDEONLEAF_HELPER || openedSection == ConfigSection.PICKAXE_COOLDOWN || openedSection == ConfigSection.FIRE_FREEZE || openedSection == ConfigSection.CHIMERA_DROP || openedSection == ConfigSection.SLAYER) && soundDropdownOpen) {
			val list = soundListBounds(settingsBounds())
			if (list.contains(mouseX.toInt(), mouseY.toInt())) {
				val filtered = SoundCatalog.filtered(activeSoundSearchField().text)
				val maxScroll = (filtered.size - SOUND_VISIBLE_ROWS).coerceAtLeast(0)
				soundScrollOffset = (soundScrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
				return true
			}
		}

		if (openedSection == ConfigSection.MOB_MODEL && mobModelDropdownOpen) {
			val list = mobModelListBounds(settingsBounds())
			if (list.contains(mouseX.toInt(), mouseY.toInt())) {
				val filtered = filteredMobModelOptions(mobModelEntityTypeField.text)
				val maxScroll = (filtered.size - MOB_MODEL_VISIBLE_ROWS).coerceAtLeast(0)
				mobModelScrollOffset = (mobModelScrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
				return true
			}
		}

		if (openedSection == ConfigSection.MOB_MODEL && mobModelVariantDropdownOpen) {
			val list = mobModelVariantListBounds(settingsBounds())
			if (list.contains(mouseX.toInt(), mouseY.toInt())) {
				val filtered = filteredMobModelVariantOptions(mobModelVariantField.text)
				val maxScroll = (filtered.size - MOB_MODEL_VISIBLE_ROWS).coerceAtLeast(0)
				mobModelVariantScrollOffset = (mobModelVariantScrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
				return true
			}
		}

		if (openedSection == ConfigSection.DUNGEON_AUTOKICK && dungeonAutoKickFloorDropdownOpen) {
			val list = dungeonAutoKickFloorListBounds(settingsBounds())
			if (list.contains(mouseX.toInt(), mouseY.toInt())) {
				val maxScroll = (DUNGEON_AUTOKICK_FLOOR_OPTIONS.size - DUNGEON_AUTOKICK_FLOOR_VISIBLE_ROWS).coerceAtLeast(0)
				dungeonAutoKickFloorScrollOffset = (dungeonAutoKickFloorScrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
				return true
			}
		}

		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
	}

	private fun toggleModule(section: ConfigSection) {
		when (section) {
			ConfigSection.IRC_BRIDGE -> workingCopy.ircBridgeEnabled = !workingCopy.ircBridgeEnabled
			ConfigSection.CHAT -> workingCopy.chatModuleEnabled = !workingCopy.chatModuleEnabled
			ConfigSection.HIDEONLEAF_HELPER -> workingCopy.hideonleafHelperEnabled = !workingCopy.hideonleafHelperEnabled
			ConfigSection.PURPLE_TERRACOTTA -> workingCopy.purpleTerracottaHighlightModuleEnabled = !workingCopy.purpleTerracottaHighlightModuleEnabled
			ConfigSection.FLOOR_DROP_ESP -> workingCopy.floorDropEspModuleEnabled = !workingCopy.floorDropEspModuleEnabled
			ConfigSection.WORMHOLE_FINDER -> workingCopy.wormholeFinderModuleEnabled = !workingCopy.wormholeFinderModuleEnabled
			ConfigSection.AUTO_SPRINT -> workingCopy.autoSprintModuleEnabled = !workingCopy.autoSprintModuleEnabled
			ConfigSection.TIME_CHANGER -> workingCopy.timeChangerEnabled = !workingCopy.timeChangerEnabled
			ConfigSection.AUCTION_HOUSE -> workingCopy.auctionHouseModuleEnabled = !workingCopy.auctionHouseModuleEnabled
			ConfigSection.SLAYER -> workingCopy.slayerModuleEnabled = !workingCopy.slayerModuleEnabled
			ConfigSection.AUTO_CROESUS -> workingCopy.autoCroesusModuleEnabled = !workingCopy.autoCroesusModuleEnabled
			ConfigSection.EXPERIMENTS -> workingCopy.experimentationTableModuleEnabled = !workingCopy.experimentationTableModuleEnabled
			ConfigSection.DOOR -> workingCopy.dungeonDoorModuleEnabled = !workingCopy.dungeonDoorModuleEnabled
			ConfigSection.RED_VIGNETTE -> workingCopy.dungeonRedVignetteModuleEnabled = !workingCopy.dungeonRedVignetteModuleEnabled
			ConfigSection.PEST_ESP -> workingCopy.pestEspModuleEnabled = !workingCopy.pestEspModuleEnabled
			ConfigSection.CORPSE_ESP -> workingCopy.corpseEspModuleEnabled = !workingCopy.corpseEspModuleEnabled
			ConfigSection.MOB_MODEL -> workingCopy.mobModelModuleEnabled = !workingCopy.mobModelModuleEnabled
			ConfigSection.CROSSHAIR -> workingCopy.customCrosshairModuleEnabled = !workingCopy.customCrosshairModuleEnabled
			ConfigSection.INVENTORY_PREVIEW -> workingCopy.inventoryPreviewModuleEnabled = !workingCopy.inventoryPreviewModuleEnabled
			ConfigSection.SILENT_DISCONNECT -> workingCopy.silentDisconnectModuleEnabled = !workingCopy.silentDisconnectModuleEnabled
			ConfigSection.CHIMERA_DROP -> workingCopy.chimeraBookDropEffectsModuleEnabled = !workingCopy.chimeraBookDropEffectsModuleEnabled
			ConfigSection.M5 -> workingCopy.m5ModuleEnabled = !workingCopy.m5ModuleEnabled
			ConfigSection.DUNGEON_AUTOKICK -> workingCopy.dungeonAutoKickModuleEnabled = !workingCopy.dungeonAutoKickModuleEnabled
			ConfigSection.PICKAXE_COOLDOWN -> workingCopy.pickaxeAbilityCooldownModuleEnabled = !workingCopy.pickaxeAbilityCooldownModuleEnabled
			ConfigSection.FIRE_FREEZE -> workingCopy.fireFreezeModuleEnabled = !workingCopy.fireFreezeModuleEnabled
			ConfigSection.MINESHAFT_AUTOWARP -> workingCopy.mineshaftAutoWarpModuleEnabled = !workingCopy.mineshaftAutoWarpModuleEnabled
			ConfigSection.DEPLOYBLE -> workingCopy.deploybleModuleEnabled = !workingCopy.deploybleModuleEnabled
			else -> return
		}

		try {
			mod.saveAndApplyConfig(workingCopy)
		} catch (_: IOException) {
			statusMessage = Text.literal("Failed to save module state.")
		}
	}

	private fun closeOpenedSection() {
		readWorkingCopyFromFields(updateStatus = false)
		openedSection = null
		setFocused(null)
		openColorField = null
		soundDropdownOpen = false
		soundScrollOffset = 0
		draggingColorPicker = null
		draggingSlider = null
		pickaxeAlertExpanded = false
		awaitingHideonleafResetConfirmation = false
		mobModelDropdownOpen = false
		mobModelScrollOffset = 0
		mobModelVariantDropdownOpen = false
		mobModelVariantScrollOffset = 0
		dungeonAutoKickFloorDropdownOpen = false
		dungeonAutoKickFloorScrollOffset = 0
		layoutWidgets()
	}

	private fun save() {
		if (readWorkingCopyFromFields(updateStatus = true) == null) {
			return
		}

		try {
			mod.saveAndApplyConfig(workingCopy)
			persistAutoCroesusConfig()
			statusMessage = Text.literal("Saved.")
			close()
		} catch (_: IOException) {
			statusMessage = Text.literal("Failed to save config.")
		}
	}

	private fun testConnection() {
		val candidate = readWorkingCopyFromFields(updateStatus = true) ?: run {
			return
		}
		statusMessage = Text.literal(XclipsenIrcBridgeClient.formatStatus(mod.testBackendConnection(candidate)))
	}

	private fun checkForUpdatesNow() {
		val candidate = readWorkingCopyFromFields(updateStatus = true) ?: return
		try {
			mod.saveAndApplyConfig(candidate)
			statusMessage = if (ModUpdateChecker.requestCheckNow()) {
				Text.literal("Started update check.")
			} else {
				Text.literal("Update check already running or disabled.")
			}
		} catch (_: IOException) {
			statusMessage = Text.literal("Failed to save config.")
		}
	}

	private fun readWorkingCopyFromFields(updateStatus: Boolean): BridgeConfig? {
		val candidate = copyOf(workingCopy)
		candidate.backendBaseUrl = BridgeConfigManager.MOD_BACKEND_BASE_URL
		candidate.ircServerBaseUrl = ircServerBaseUrlField.text.trim()
		candidate.backendAuthToken = backendAuthTokenField.text.trim()
		candidate.checkForUpdatesEnabled = workingCopy.checkForUpdatesEnabled
		candidate.autoUpdateEnabled = workingCopy.autoUpdateEnabled
		candidate.ircBridgeEnabled = workingCopy.ircBridgeEnabled
		candidate.ircCommandFormat = ircFormatField.text
		candidate.coopChatRelayEnabled = workingCopy.coopChatRelayEnabled
		candidate.chatModuleEnabled = workingCopy.chatModuleEnabled
		candidate.chatImplosionHiderEnabled = workingCopy.chatImplosionHiderEnabled
		candidate.hideonleafHelperEnabled = workingCopy.hideonleafHelperEnabled
		candidate.shulkerTracerLineMode = workingCopy.shulkerTracerLineMode.coerceIn(0, 3)
		candidate.shulkerTracerLineEnabled = candidate.shulkerTracerLineMode > 0
		candidate.shulkerTracerLineWidth = workingCopy.shulkerTracerLineWidth
		candidate.hideonleafLostFightAlertEnabled = workingCopy.hideonleafLostFightAlertEnabled
		candidate.hideonleafShareDataEnabled = workingCopy.hideonleafShareDataEnabled
		candidate.hideonleafLostFightAlertSoundId = SoundCatalog.normalizeSoundId(workingCopy.hideonleafLostFightAlertSoundId)
		candidate.hideonleafLostFightAlertSoundVolume = workingCopy.hideonleafLostFightAlertSoundVolume
		candidate.hideonleafLostFightAlertSoundPitch = workingCopy.hideonleafLostFightAlertSoundPitch
		candidate.timeChangerEnabled = workingCopy.timeChangerEnabled
		candidate.purpleTerracottaHighlightModuleEnabled = workingCopy.purpleTerracottaHighlightModuleEnabled
		candidate.wormholeFinderModuleEnabled = workingCopy.wormholeFinderModuleEnabled
		candidate.wormholeDepartureAlertEnabled = workingCopy.wormholeDepartureAlertEnabled
		candidate.wormholeDepartureAlertSoundId = SoundCatalog.normalizeSoundId(workingCopy.wormholeDepartureAlertSoundId)
		candidate.wormholeDepartureAlertSoundVolume = workingCopy.wormholeDepartureAlertSoundVolume
		candidate.wormholeDepartureAlertSoundPitch = workingCopy.wormholeDepartureAlertSoundPitch
		candidate.autoSprintModuleEnabled = workingCopy.autoSprintModuleEnabled
		candidate.autoSprintDisableWhenFullySubmerged = workingCopy.autoSprintDisableWhenFullySubmerged
		candidate.timeChangerMode = workingCopy.timeChangerMode.coerceIn(0, ClientTimeChanger.modeCount - 1)
		candidate.auctionHouseModuleEnabled = workingCopy.auctionHouseModuleEnabled
		candidate.auctionHouseAutoCopyUnderbidEnabled = workingCopy.auctionHouseAutoCopyUnderbidEnabled
		candidate.highClassDiceTrackerEnabled = workingCopy.highClassDiceTrackerEnabled
		candidate.slayerModuleEnabled = workingCopy.slayerModuleEnabled
		candidate.slayerSpawnAnnouncerEnabled = workingCopy.slayerSpawnAnnouncerEnabled
		candidate.slayerBlazePhaseDisplayEnabled = workingCopy.slayerBlazePhaseDisplayEnabled
		candidate.slayerBlazeColoredMobsEnabled = workingCopy.slayerBlazeColoredMobsEnabled
		candidate.slayerBlazeAutoDaggerEnabled = workingCopy.slayerBlazeAutoDaggerEnabled
		candidate.slayerBlazeAutoDaggerDelayMaxTicks = workingCopy.slayerBlazeAutoDaggerDelayMaxTicks.coerceIn(2, 5)
		candidate.slayerBlazeAutoDaggerResetAfterBossEnabled = workingCopy.slayerBlazeAutoDaggerResetAfterBossEnabled
		candidate.slayerBlazeAutoDaggerDebugEnabled = workingCopy.slayerBlazeAutoDaggerDebugEnabled
		candidate.slayerRngMeterDisplayEnabled = workingCopy.slayerRngMeterDisplayEnabled
		candidate.slayerRngMeterOptimalRemovalEnabled = workingCopy.slayerRngMeterOptimalRemovalEnabled
		candidate.slayerRngMeterCompactMode = workingCopy.slayerRngMeterCompactMode
		candidate.slayerRngMeterUseMagicFind = workingCopy.slayerRngMeterUseMagicFind
		candidate.slayerRngMeterMagicFind = workingCopy.slayerRngMeterMagicFind.coerceIn(0, 900)
		candidate.slayerRngMeterActiveSlayer = workingCopy.slayerRngMeterActiveSlayer
		candidate.slayerRngMeterState = workingCopy.slayerRngMeterState.mapValues { entry -> entry.value.copy() }.toMutableMap()
		candidate.slayerRngMeterWikiCacheUpdatedAtMs = workingCopy.slayerRngMeterWikiCacheUpdatedAtMs
		candidate.slayerRngMeterWikiCache = workingCopy.slayerRngMeterWikiCache.mapValues { entry -> entry.value.copy() }.toMutableMap()
		candidate.slayerSpawnAnnouncerText = slayerAnnouncerTextField.text.trim()
		candidate.slayerSpawnAnnouncerSoundId = SoundCatalog.normalizeSoundId(workingCopy.slayerSpawnAnnouncerSoundId)
		candidate.slayerSpawnAnnouncerSoundVolume = workingCopy.slayerSpawnAnnouncerSoundVolume
		candidate.slayerSpawnAnnouncerSoundPitch = workingCopy.slayerSpawnAnnouncerSoundPitch
		candidate.autoCroesusModuleEnabled = workingCopy.autoCroesusModuleEnabled
		candidate.experimentationTableModuleEnabled = workingCopy.experimentationTableModuleEnabled
		candidate.autoExperimentsEnabled = workingCopy.autoExperimentsEnabled
		candidate.autoExperimentsAutoClose = workingCopy.autoExperimentsAutoClose
		candidate.autoExperimentsAutoPairs = workingCopy.autoExperimentsAutoPairs
		candidate.autoExperimentsGetMaxXp = workingCopy.autoExperimentsGetMaxXp
		candidate.autoExperimentsShowSolver = workingCopy.autoExperimentsShowSolver
		candidate.dungeonDoorModuleEnabled = workingCopy.dungeonDoorModuleEnabled
		candidate.dungeonDoorEnabled = workingCopy.dungeonDoorEnabled
		candidate.dungeonDoorDebugEnabled = workingCopy.dungeonDoorDebugEnabled
		candidate.dungeonDoorMode = workingCopy.dungeonDoorMode.coerceIn(0, MortDoorBarrierFeature.modeCount - 1)
		candidate.dungeonRedVignetteModuleEnabled = workingCopy.dungeonRedVignetteModuleEnabled
		candidate.dungeonRedVignetteEnabled = workingCopy.dungeonRedVignetteEnabled
		candidate.dungeonAutoKickModuleEnabled = workingCopy.dungeonAutoKickModuleEnabled
		candidate.dungeonAutoKickStatsDisplayEnabled = workingCopy.dungeonAutoKickStatsDisplayEnabled
		candidate.dungeonAutoKickSendKickLineEnabled = workingCopy.dungeonAutoKickSendKickLineEnabled
		candidate.dungeonAutoKickAutoKickEnabled = workingCopy.dungeonAutoKickAutoKickEnabled
		candidate.dungeonAutoKickFloor = workingCopy.dungeonAutoKickFloor
		candidate.dungeonAutoKickMasterMode = workingCopy.dungeonAutoKickMasterMode
		candidate.dungeonAutoKickApiOffKickEnabled = workingCopy.dungeonAutoKickApiOffKickEnabled
		candidate.dungeonAutoKickInformKickedEnabled = workingCopy.dungeonAutoKickInformKickedEnabled
		candidate.dungeonAutoKickCacheEnabled = workingCopy.dungeonAutoKickCacheEnabled
		candidate.partyFinderGuiStatsEnabled = workingCopy.partyFinderGuiStatsEnabled
		candidate.partyFinderHighlightsEnabled = workingCopy.partyFinderHighlightsEnabled
		candidate.partyFinderMemberCountEnabled = workingCopy.partyFinderMemberCountEnabled
		candidate.partyFinderRightClickEnabled = workingCopy.partyFinderRightClickEnabled
		candidate.pestEspModuleEnabled = workingCopy.pestEspModuleEnabled
		candidate.floorDropEspModuleEnabled = workingCopy.floorDropEspModuleEnabled
		candidate.floorDropEspTracerEnabled = workingCopy.floorDropEspTracerEnabled
		candidate.pestEspTracerEnabled = workingCopy.pestEspTracerEnabled
		candidate.corpseEspModuleEnabled = workingCopy.corpseEspModuleEnabled
		candidate.corpseEspLapisEnabled = workingCopy.corpseEspLapisEnabled
		candidate.corpseEspTungstenEnabled = workingCopy.corpseEspTungstenEnabled
		candidate.corpseEspUmberEnabled = workingCopy.corpseEspUmberEnabled
		candidate.corpseEspVanguardEnabled = workingCopy.corpseEspVanguardEnabled
		candidate.mobModelModuleEnabled = workingCopy.mobModelModuleEnabled
		candidate.mobModelVariant = workingCopy.mobModelVariant
		candidate.mobModelBaby = workingCopy.mobModelBaby
		candidate.mobModelShowArmor = workingCopy.mobModelShowArmor
		candidate.mobModelShowHeldItems = workingCopy.mobModelShowHeldItems
		candidate.mobModelScale = workingCopy.mobModelScale
		candidate.mobModelEntityType = workingCopy.mobModelEntityType
		candidate.inventoryPreviewModuleEnabled = workingCopy.inventoryPreviewModuleEnabled
		candidate.inventoryPreviewShowArmor = workingCopy.inventoryPreviewShowArmor
		candidate.customCrosshairModuleEnabled = workingCopy.customCrosshairModuleEnabled
		candidate.customCrosshairShowInFirstPerson = workingCopy.customCrosshairShowInFirstPerson
		candidate.customCrosshairVisibleInF5 = workingCopy.customCrosshairVisibleInF5
		candidate.customCrosshairPattern = CustomCrosshairFeature.normalizePattern(workingCopy.customCrosshairPattern)
		candidate.silentDisconnectModuleEnabled = workingCopy.silentDisconnectModuleEnabled
		candidate.silentDisconnectLastStatus = workingCopy.silentDisconnectLastStatus
		candidate.silentDisconnectRestorePending = workingCopy.silentDisconnectRestorePending
		candidate.chimeraBookDropEffectsModuleEnabled = workingCopy.chimeraBookDropEffectsModuleEnabled
		candidate.chimeraBookDropEffectsSoundId = SoundCatalog.normalizeSoundId(workingCopy.chimeraBookDropEffectsSoundId)
		candidate.chimeraBookDropEffectsSoundVolume = workingCopy.chimeraBookDropEffectsSoundVolume
		candidate.chimeraBookDropEffectsSoundPitch = workingCopy.chimeraBookDropEffectsSoundPitch
		candidate.m5ModuleEnabled = workingCopy.m5ModuleEnabled
		candidate.m5LividFinderEnabled = workingCopy.m5LividFinderEnabled
		candidate.m5TracerEnabled = workingCopy.m5TracerEnabled
		candidate.m5IceSprayTimerEnabled = workingCopy.m5IceSprayTimerEnabled
		candidate.m5RagAxeAlertEnabled = workingCopy.m5RagAxeAlertEnabled
		candidate.pickaxeAbilityCooldownModuleEnabled = workingCopy.pickaxeAbilityCooldownModuleEnabled
		candidate.pickaxeAbilityCooldownShowReady = workingCopy.pickaxeAbilityCooldownShowReady
		candidate.pickaxeAbilityCooldownAlertEnabled = workingCopy.pickaxeAbilityCooldownAlertEnabled
		candidate.pickaxeAbilityCooldownAlertSoundId = SoundCatalog.normalizeSoundId(workingCopy.pickaxeAbilityCooldownAlertSoundId)
		candidate.pickaxeAbilityCooldownAlertSoundVolume = workingCopy.pickaxeAbilityCooldownAlertSoundVolume
		candidate.pickaxeAbilityCooldownAlertSoundPitch = workingCopy.pickaxeAbilityCooldownAlertSoundPitch
		candidate.pickaxeAbilityCooldownAlertText = pickaxeAlertTextField.text.trim()
		candidate.fireFreezeModuleEnabled = workingCopy.fireFreezeModuleEnabled
		candidate.fireFreezeMobTimerEnabled = workingCopy.fireFreezeMobTimerEnabled
		candidate.fireFreezeFreezeTimerEnabled = workingCopy.fireFreezeFreezeTimerEnabled
		candidate.fireFreezeStrongMobsOnly = workingCopy.fireFreezeStrongMobsOnly
		candidate.fireFreezeBoxFrozenMobsEnabled = workingCopy.fireFreezeBoxFrozenMobsEnabled
		candidate.fireFreezeCustomCircleEnabled = workingCopy.fireFreezeCustomCircleEnabled
		candidate.fireFreezeCircleLineWidth = workingCopy.fireFreezeCircleLineWidth
		candidate.fireFreezeRefreezeAlertEnabled = workingCopy.fireFreezeRefreezeAlertEnabled
		candidate.fireFreezeRefreezeAlertSoundId = SoundCatalog.normalizeSoundId(workingCopy.fireFreezeRefreezeAlertSoundId)
		candidate.fireFreezeRefreezeAlertSoundVolume = workingCopy.fireFreezeRefreezeAlertSoundVolume
		candidate.fireFreezeRefreezeAlertSoundPitch = workingCopy.fireFreezeRefreezeAlertSoundPitch
		candidate.mineshaftAutoWarpModuleEnabled = workingCopy.mineshaftAutoWarpModuleEnabled
		candidate.mineshaftAutoWarpCorpseRule = mineshaftAutoWarpRuleField.text.trim()
		candidate.hudElements = mod.config().hudElements.mapValues { entry -> entry.value.copy() }.toMutableMap()
		candidate.shulkerGlowColorHex = normalizedHexColor(shulkerGlowColorHexField.text) ?: run {
			if (updateStatus) statusMessage = Text.literal("Glow color must be #RRGGBB.")
			return null
		}
		candidate.shulkerProjectileGlowColorHex = normalizedHexColor(shulkerProjectileGlowColorHexField.text) ?: run {
			if (updateStatus) statusMessage = Text.literal("Projectile color must be #RRGGBB.")
			return null
		}
		candidate.shulkerTracerLineColorHex = normalizedHexColor(shulkerTracerLineColorHexField.text) ?: run {
			if (updateStatus) statusMessage = Text.literal("Line color must be #RRGGBB.")
			return null
		}
		candidate.purpleTerracottaHighlightColorHex = normalizedHexColor(purpleTerracottaHighlightColorHexField.text) ?: run {
			if (updateStatus) statusMessage = Text.literal("Purple terracotta color must be #RRGGBB.")
			return null
		}
		candidate.pestEspColorHex = normalizedHexColor(pestEspColorHexField.text) ?: run {
			if (updateStatus) statusMessage = Text.literal("Pest ESP color must be #RRGGBB.")
			return null
		}
		candidate.fireFreezeCircleColorHex = normalizedHexColor(fireFreezeCircleColorHexField.text) ?: run {
			if (updateStatus) statusMessage = Text.literal("Fire Freeze circle color must be #RRGGBB.")
			return null
		}
		candidate.fireFreezeCircleLineWidth = candidate.fireFreezeCircleLineWidth.coerceIn(1.0f, 8.0f)
		val normalizedMobModelEntityType = normalizeMobModelEntityType(candidate.mobModelEntityType)
		val resolvedMobModelEntityType = normalizedMobModelEntityType?.let(MobModelCatalog::resolve)
		candidate.mobModelEntityType = when {
			resolvedMobModelEntityType != null -> normalizedMobModelEntityType
			candidate.mobModelModuleEnabled && normalizedMobModelEntityType == null -> {
				if (updateStatus) statusMessage = Text.literal("Mob model id must be a valid entity like minecraft:zombie.")
				return null
			}
			candidate.mobModelModuleEnabled -> {
				if (updateStatus) statusMessage = Text.literal("Mob model entity must be a living mob on this client.")
				return null
			}
			else -> "minecraft:zombie"
		}
		candidate.mobModelVariant = MobModelVariantCatalog.normalize(candidate.mobModelVariant)
		candidate.mobModelScale = candidate.mobModelScale.coerceIn(0.25f, 4.0f)
		MobModelVariantCatalog.validate(candidate.mobModelEntityType, candidate.mobModelVariant)?.let { variantError ->
			if (updateStatus) statusMessage = Text.literal(variantError)
			return null
		}

		try {
			candidate.backendPollIntervalMs = backendPollIntervalField.text.trim().toLong()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Text.literal("Poll interval must be a number.")
			return null
		}

		if (candidate.backendPollIntervalMs < 500L) {
			if (updateStatus) statusMessage = Text.literal("Poll interval must be at least 500 ms.")
			return null
		}

		try {
			candidate.autoExperimentsClickDelayMs = autoExperimentsClickDelayField.text.trim().toInt()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Text.literal("Auto Experiments click delay must be a number.")
			return null
		}
		if (candidate.autoExperimentsClickDelayMs < 50) {
			if (updateStatus) statusMessage = Text.literal("Auto Experiments click delay must be at least 50 ms.")
			return null
		}

		try {
			candidate.autoExperimentsDelayVarietyMs = autoExperimentsDelayVarietyField.text.trim().toInt()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Text.literal("Auto Experiments delay variety must be a number.")
			return null
		}
		if (candidate.autoExperimentsDelayVarietyMs < 0) {
			if (updateStatus) statusMessage = Text.literal("Auto Experiments delay variety must be at least 0 ms.")
			return null
		}

		try {
			candidate.autoExperimentsSerumCount = autoExperimentsSerumCountField.text.trim().toInt()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Text.literal("Auto Experiments serum count must be a number.")
			return null
		}
		if (candidate.autoExperimentsSerumCount !in 0..3) {
			if (updateStatus) statusMessage = Text.literal("Auto Experiments serum count must be between 0 and 3.")
			return null
		}

		if (candidate.pickaxeAbilityCooldownAlertText.isBlank()) {
			candidate.pickaxeAbilityCooldownAlertText = PickaxeAbilityCooldownFeature.DEFAULT_ALERT_TEXT
		}
		if (candidate.slayerSpawnAnnouncerText.isBlank()) {
			candidate.slayerSpawnAnnouncerText = SlayerFeature.DEFAULT_ANNOUNCER_TEXT
		}
		val mineshaftRuleError = MineshaftAutoWarpFeature.validateCorpseRule(candidate.mineshaftAutoWarpCorpseRule)
		if (mineshaftRuleError != null) {
			if (updateStatus) statusMessage = Text.literal(mineshaftRuleError)
			return null
		}

		try {
			candidate.mineshaftAutoWarpDelayMs = mineshaftAutoWarpDelayField.text.trim().toLong()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Text.literal("Mineshaft AutoWarp delay must be a number.")
			return null
		}
		if (candidate.mineshaftAutoWarpDelayMs < 500L) {
			if (updateStatus) statusMessage = Text.literal("Mineshaft AutoWarp delay must be at least 500 ms.")
			return null
		}

		try {
			candidate.mineshaftAutoWarpWindowMs = mineshaftAutoWarpWindowField.text.trim().toLong()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Text.literal("Mineshaft AutoWarp window must be a number.")
			return null
		}
		if (candidate.mineshaftAutoWarpWindowMs !in 5_000L..60_000L) {
			if (updateStatus) statusMessage = Text.literal("Mineshaft AutoWarp window must be between 5000 and 60000 ms.")
			return null
		}

		candidate.dungeonAutoKickMaxPbSeconds = parseDurationSeconds(dungeonAutoKickMaxPbField.text) ?: run {
			if (updateStatus) statusMessage = Text.literal("Dungeon AutoKick PB must be seconds or m:ss.")
			return null
		}
		if (candidate.dungeonAutoKickMaxPbSeconds !in 60..900) {
			if (updateStatus) statusMessage = Text.literal("Dungeon AutoKick PB must be between 60 and 900 seconds.")
			return null
		}

		candidate.dungeonAutoKickMinSecretsThousands = dungeonAutoKickMinSecretsField.text.trim().toIntOrNull() ?: run {
			if (updateStatus) statusMessage = Text.literal("Dungeon AutoKick secrets must be a number.")
			return null
		}
		if (candidate.dungeonAutoKickMinSecretsThousands !in 0..200) {
			if (updateStatus) statusMessage = Text.literal("Dungeon AutoKick secrets must be between 0 and 200k.")
			return null
		}

		candidate.dungeonAutoKickMinMagicalPower = dungeonAutoKickMinMpField.text.trim().toIntOrNull() ?: run {
			if (updateStatus) statusMessage = Text.literal("Dungeon AutoKick MP must be a number.")
			return null
		}
		if (candidate.dungeonAutoKickMinMagicalPower !in 0..2500) {
			if (updateStatus) statusMessage = Text.literal("Dungeon AutoKick MP must be between 0 and 2500.")
			return null
		}

		val autoCroesusCandidate = copyOf(workingAutoCroesusConfig)
		try {
			autoCroesusCandidate.minClickDelay = autoCroesusClickDelayField.text.trim().toInt()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Text.literal("AutoCroesus click delay must be a number.")
			return null
		}
		if (autoCroesusCandidate.minClickDelay < 0) {
			if (updateStatus) statusMessage = Text.literal("AutoCroesus click delay must be at least 0.")
			return null
		}

		autoCroesusCandidate.kismetMinProfit = parseNonNegativeLong(autoCroesusKismetProfitField.text) ?: run {
			if (updateStatus) statusMessage = Text.literal("AutoCroesus kismet profit must be a number.")
			return null
		}
		autoCroesusCandidate.chestKeyMinProfit = parseNonNegativeLong(autoCroesusChestKeyProfitField.text) ?: run {
			if (updateStatus) statusMessage = Text.literal("AutoCroesus chest key profit must be a number.")
			return null
		}
		autoCroesusCandidate.kismetFloors = parseAutoCroesusFloors(autoCroesusKismetFloorsField.text) ?: run {
			if (updateStatus) statusMessage = Text.literal("Kismet floors must be comma-separated floors like M7, F7.")
			return null
		}

		workingCopy = candidate
		workingAutoCroesusConfig = autoCroesusCandidate
		return candidate
	}

	private fun registerField(field: ConfigField, value: String, placeholder: String): TextFieldWidget {
		val widget = addField(0, 0, 260, value, placeholder)
		fields[field] = widget
		return widget
	}

	private fun addField(x: Int, y: Int, width: Int, value: String, placeholder: String): TextFieldWidget {
		val field = TextFieldWidget(textRenderer, x, y, width, 20, Text.empty())
		field.setMaxLength(512)
		field.text = value
		field.setPlaceholder(Text.literal(placeholder))
		field.setDrawsBackground(false)
		addDrawableChild(field)
		return field
	}

	private fun layoutWidgets() {
		val menu = settingsBounds()
		val section = openedSection

		fields.forEach { (field, widget) ->
			val row = section?.let { textFieldBounds(it, field, menu) }
			if (row != null) {
				val inputWidth = if (field == ConfigField.SHULKER_GLOW_COLOR) COLOR_INPUT_WIDTH else TEXT_INPUT_WIDTH
				widget.setDimensionsAndPosition(inputWidth, 20, row.left + 8, row.top + 15)
				setVisible(widget, true)
			} else {
				setVisible(widget, false)
			}
		}

		val slayerSoundDropdownVisible = section == ConfigSection.SLAYER && slayerMiscExpanded
		if ((section == ConfigSection.HIDEONLEAF_HELPER || section == ConfigSection.PICKAXE_COOLDOWN || section == ConfigSection.FIRE_FREEZE || section == ConfigSection.CHIMERA_DROP || slayerSoundDropdownVisible) && soundDropdownOpen) {
			val search = soundSearchBounds(menu)
			activeSoundSearchField().setDimensionsAndPosition(search.width(), 18, search.left, search.top)
			setVisible(activeSoundSearchField(), true)
		} else {
			setVisible(lostFightSoundSearchField, false)
			setVisible(pickaxeAlertSoundSearchField, false)
			setVisible(fireFreezeAlertSoundSearchField, false)
			setVisible(chimeraDropSoundSearchField, false)
			setVisible(slayerAnnouncerSoundSearchField, false)
		}

		if (section == ConfigSection.MOB_MODEL && mobModelDropdownOpen) {
			val search = mobModelSearchBounds(menu)
			mobModelEntityTypeField.setDimensionsAndPosition(search.width(), 18, search.left, search.top)
			setVisible(mobModelEntityTypeField, true)
		} else if (section != ConfigSection.MOB_MODEL) {
			setVisible(mobModelEntityTypeField, false)
		}

		if (section == ConfigSection.MOB_MODEL && mobModelVariantDropdownOpen) {
			val search = mobModelVariantSearchBounds(menu)
			mobModelVariantField.setDimensionsAndPosition(search.width(), 18, search.left, search.top)
			setVisible(mobModelVariantField, true)
		} else if (section != ConfigSection.MOB_MODEL) {
			setVisible(mobModelVariantField, false)
		}

		searchField.setDimensionsAndPosition(SEARCH_WIDTH, 22, (width / 2) - (SEARCH_WIDTH / 2), height - 40)
	}

	private fun drawPanels(context: DrawContext, mouseX: Int, mouseY: Int) {
		var x = 20
		sectionRows.forEach { panel ->
			drawPanel(context, panel, x, 20, mouseX, mouseY)
			x += 124
		}
	}

	private fun drawPanel(context: DrawContext, panel: ConfigPanel, x: Int, y: Int, mouseX: Int, mouseY: Int) {
		val visibleRows = filteredSections(panel.sections)
		if (visibleRows.isEmpty() && searchField.text.isNotBlank()) {
			return
		}

		context.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEADER_HEIGHT, PANEL_HEADER)
		context.fill(x, y, x + PANEL_WIDTH, y + 2, ACCENT)
		context.drawCenteredTextWithShadow(textRenderer, panel.title, x + PANEL_WIDTH / 2, y + 7, TEXT_WHITE)

		var rowY = y + PANEL_HEADER_HEIGHT
		visibleRows.forEach { section ->
			val hovered = mouseX in x..(x + PANEL_WIDTH) && mouseY in rowY..(rowY + PANEL_ROW_HEIGHT)
			val enabled = isModuleEnabled(section)
			context.fill(x, rowY, x + PANEL_WIDTH, rowY + PANEL_ROW_HEIGHT, PANEL_BODY)
			if (section.toggleable && enabled) {
				context.fill(x, rowY, x + PANEL_WIDTH, rowY + PANEL_ROW_HEIGHT, SELECTED)
				context.fill(x, rowY, x + 2, rowY + PANEL_ROW_HEIGHT, ACCENT)
			} else if (!section.toggleable && section == selectedSection) {
				context.fill(x, rowY, x + PANEL_WIDTH, rowY + PANEL_ROW_HEIGHT, 0x22181818)
			}
			if (hovered) {
				context.fill(x, rowY, x + PANEL_WIDTH, rowY + PANEL_ROW_HEIGHT, HOVER)
			}
			val textColor = if (section.toggleable && !enabled) TEXT_DISABLED else TEXT_PRIMARY
			context.drawCenteredTextWithShadow(textRenderer, section.label, x + PANEL_WIDTH / 2, rowY + 4, textColor)
			rowY += PANEL_ROW_HEIGHT
		}
	}

	private fun isModuleEnabled(section: ConfigSection): Boolean {
		return when (section) {
			ConfigSection.IRC_BRIDGE -> workingCopy.ircBridgeEnabled
			ConfigSection.CHAT -> workingCopy.chatModuleEnabled
			ConfigSection.HIDEONLEAF_HELPER -> workingCopy.hideonleafHelperEnabled
			ConfigSection.PURPLE_TERRACOTTA -> workingCopy.purpleTerracottaHighlightModuleEnabled
			ConfigSection.FLOOR_DROP_ESP -> workingCopy.floorDropEspModuleEnabled
			ConfigSection.WORMHOLE_FINDER -> workingCopy.wormholeFinderModuleEnabled
			ConfigSection.AUTO_SPRINT -> workingCopy.autoSprintModuleEnabled
			ConfigSection.TIME_CHANGER -> workingCopy.timeChangerEnabled
			ConfigSection.AUCTION_HOUSE -> workingCopy.auctionHouseModuleEnabled
			ConfigSection.SLAYER -> workingCopy.slayerModuleEnabled
			ConfigSection.AUTO_CROESUS -> workingCopy.autoCroesusModuleEnabled
			ConfigSection.EXPERIMENTS -> workingCopy.experimentationTableModuleEnabled
			ConfigSection.DOOR -> workingCopy.dungeonDoorModuleEnabled
			ConfigSection.RED_VIGNETTE -> workingCopy.dungeonRedVignetteModuleEnabled
			ConfigSection.PEST_ESP -> workingCopy.pestEspModuleEnabled
			ConfigSection.CORPSE_ESP -> workingCopy.corpseEspModuleEnabled
			ConfigSection.MOB_MODEL -> workingCopy.mobModelModuleEnabled
			ConfigSection.CROSSHAIR -> workingCopy.customCrosshairModuleEnabled
			ConfigSection.INVENTORY_PREVIEW -> workingCopy.inventoryPreviewModuleEnabled
			ConfigSection.SILENT_DISCONNECT -> workingCopy.silentDisconnectModuleEnabled
			ConfigSection.CHIMERA_DROP -> workingCopy.chimeraBookDropEffectsModuleEnabled
			ConfigSection.M5 -> workingCopy.m5ModuleEnabled
			ConfigSection.DUNGEON_AUTOKICK -> workingCopy.dungeonAutoKickModuleEnabled
			ConfigSection.PICKAXE_COOLDOWN -> workingCopy.pickaxeAbilityCooldownModuleEnabled
			ConfigSection.FIRE_FREEZE -> workingCopy.fireFreezeModuleEnabled
			ConfigSection.MINESHAFT_AUTOWARP -> workingCopy.mineshaftAutoWarpModuleEnabled
			ConfigSection.DEPLOYBLE -> workingCopy.deploybleModuleEnabled
			else -> true
		}
	}

	private fun drawSearch(context: DrawContext) {
		val x = (width / 2) - (SEARCH_WIDTH / 2)
		val y = height - 40
		context.fill(x, y, x + SEARCH_WIDTH, y + 22, 0xC80F0F0F.toInt())
		context.fill(x, y + 20, x + SEARCH_WIDTH, y + 22, if (searchField.isFocused) ACCENT else 0x1EFFFFFF)
	}

	private fun drawSettingsMenu(context: DrawContext, mouseX: Int, mouseY: Int) {
		val section = openedSection ?: return
		val menu = settingsBounds()
		context.fill(menu.left, menu.top, menu.right, menu.bottom, POPUP_BACKGROUND)
		context.fill(menu.left, menu.top, menu.right, menu.top + 2, ACCENT)
		context.drawCenteredTextWithShadow(textRenderer, section.label.uppercase(), (menu.left + menu.right) / 2, menu.top + 10, TEXT_WHITE)
		context.fill(menu.left + 10, menu.top + 28, menu.right - 10, menu.top + 29, 0x1EFFFFFF)

		when (section) {
			ConfigSection.SETUP -> drawSetupSettings(context, menu, mouseX, mouseY)
			ConfigSection.IRC_BRIDGE -> drawIrcBridgeSettings(context, menu, mouseX, mouseY)
			ConfigSection.CHAT -> drawChatSettings(context, menu, mouseX, mouseY)
			ConfigSection.HIDEONLEAF_HELPER -> drawHideonleafHelperSettings(context, menu, mouseX, mouseY)
			ConfigSection.PURPLE_TERRACOTTA -> drawPurpleTerracottaSettings(context, menu, mouseX, mouseY)
			ConfigSection.FLOOR_DROP_ESP -> drawFloorDropEspSettings(context, menu, mouseX, mouseY)
			ConfigSection.WORMHOLE_FINDER -> drawWormholeFinderSettings(context, menu, mouseX, mouseY)
			ConfigSection.AUTO_SPRINT -> drawAutoSprintSettings(context, menu, mouseX, mouseY)
			ConfigSection.TIME_CHANGER -> drawTimeChangerSettings(context, menu, mouseX, mouseY)
			ConfigSection.AUCTION_HOUSE -> drawAuctionHouseSettings(context, menu, mouseX, mouseY)
			ConfigSection.SLAYER -> drawSlayerSettings(context, menu, mouseX, mouseY)
			ConfigSection.PEST_ESP -> drawPestEspSettings(context, menu, mouseX, mouseY)
			ConfigSection.CORPSE_ESP -> drawCorpseEspSettings(context, menu, mouseX, mouseY)
			ConfigSection.MOB_MODEL -> drawMobModelSettings(context, menu, mouseX, mouseY)
			ConfigSection.CROSSHAIR -> drawCrosshairSettings(context, menu, mouseX, mouseY)
			ConfigSection.INVENTORY_PREVIEW -> drawInventoryPreviewSettings(context, menu, mouseX, mouseY)
			ConfigSection.SILENT_DISCONNECT -> drawSilentDisconnectSettings(context, menu, mouseX, mouseY)
			ConfigSection.CHIMERA_DROP -> drawChimeraDropSettings(context, menu, mouseX, mouseY)
			ConfigSection.M5 -> drawM5Settings(context, menu, mouseX, mouseY)
			ConfigSection.DUNGEON_AUTOKICK -> drawDungeonAutoKickSettings(context, menu, mouseX, mouseY)
			ConfigSection.PICKAXE_COOLDOWN -> drawPickaxeCooldownSettings(context, menu, mouseX, mouseY)
			ConfigSection.FIRE_FREEZE -> drawFireFreezeSettings(context, menu, mouseX, mouseY)
			ConfigSection.MINESHAFT_AUTOWARP -> drawMineshaftAutoWarpSettings(context, menu, mouseX, mouseY)
			ConfigSection.DEPLOYBLE -> drawDeploybleSettings(context, menu, mouseX, mouseY)
			ConfigSection.EXPERIMENTS -> drawExperimentationSettings(context, menu, mouseX, mouseY)
			ConfigSection.AUTO_CROESUS -> drawAutoCroesusSettings(context, menu, mouseX, mouseY)
			ConfigSection.DOOR -> drawDoorSettings(context, menu, mouseX, mouseY)
			ConfigSection.RED_VIGNETTE -> drawRedVignetteSettings(context, menu, mouseX, mouseY)
			ConfigSection.STATUS -> drawStatusSettings(context, menu, mouseX, mouseY)
		}

		if (statusMessage.string.isNotEmpty()) {
			val color = if (statusMessage.string.startsWith("Failed") || statusMessage.string.startsWith("Poll")) TEXT_ERROR else TEXT_SUCCESS
			context.drawCenteredTextWithShadow(textRenderer, statusMessage, (menu.left + menu.right) / 2, menu.bottom - 18, color)
		}
	}

	private fun drawSetupSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawInfoSetting(context, settingRowBounds(menu, 0, TEXT_INPUT_SETTING_HEIGHT), "Mod API", BridgeConfigManager.MOD_BACKEND_BASE_URL, mouseX, mouseY)
	}

	private fun drawFloorDropEspSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, floorDropEspTracerBounds(menu), "Tracer to nearest", workingCopy.floorDropEspTracerEnabled, mouseX, mouseY)
		drawInfoSetting(context, floorDropEspDetectionBounds(menu), "Detection", "3 grouped string displays", mouseX, mouseY)
	}

	private fun drawIrcBridgeSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawTextInputSetting(context, menu, 0, "IRC Server URL", ircServerBaseUrlField, mouseX, mouseY)
		drawTextInputSetting(context, menu, 1, "IRC Auth Token", backendAuthTokenField, mouseX, mouseY)
		drawTextInputSetting(context, menu, 2, "Poll Interval (ms)", backendPollIntervalField, mouseX, mouseY)
		drawTextInputSetting(context, menu, 3, "IRC Format", ircFormatField, mouseX, mouseY)
		drawToggleSetting(context, coopRelayToggleBounds(menu), "Co-op Relay", workingCopy.coopChatRelayEnabled, mouseX, mouseY)
		drawButtonSetting(context, ircTestConnectionBounds(menu), "Test IRC Server", mouseX, mouseY)
	}

	private fun drawChatSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, chatImplosionHiderBounds(menu), "Implosion Hider", workingCopy.chatImplosionHiderEnabled, mouseX, mouseY)
		drawInfoSetting(context, chatImplosionExampleBounds(menu), "Hides", "Your Implosion hit ... damage.", mouseX, mouseY)
	}

	private fun drawHideonleafHelperSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, settingRowBounds(menu, 0, SETTING_HEIGHT), "Shulker Glow", workingCopy.shulkerGlowEnabled, mouseX, mouseY)
		drawColorSetting(context, shulkerGlowColorBounds(menu), "Shulker Color", ConfigField.SHULKER_GLOW_COLOR, mouseX, mouseY)
		drawColorSetting(context, projectileGlowColorBounds(menu), "Projectile Color", ConfigField.SHULKER_PROJECTILE_GLOW_COLOR, mouseX, mouseY)
		drawColorSetting(context, tracerLineColorBounds(menu), "Line Color", ConfigField.SHULKER_TRACER_LINE_COLOR, mouseX, mouseY)
		drawIntSliderSetting(context, tracerLineBounds(menu), "Shulker Line", workingCopy.shulkerTracerLineMode, 0, 3, mouseX, mouseY)
		drawSliderSetting(context, tracerLineWidthBounds(menu), "Line Width", workingCopy.shulkerTracerLineWidth, 1.0f, 8.0f, mouseX, mouseY)
		drawToggleSetting(context, lostFightAlertBounds(menu), "Lost Fight Alert", workingCopy.hideonleafLostFightAlertEnabled, mouseX, mouseY)
		drawToggleSetting(context, shareDataBounds(menu), "Share Data", workingCopy.hideonleafShareDataEnabled, mouseX, mouseY)
		drawSoundSetting(context, lostFightSoundBounds(menu), "Alert Sound", workingCopy.hideonleafLostFightAlertSoundId, mouseX, mouseY)
		if (soundDropdownOpen) {
			drawSoundDropdown(context, menu, mouseX, mouseY)
		}
		drawSliderSetting(context, lostFightVolumeBounds(menu), "Volume", workingCopy.hideonleafLostFightAlertSoundVolume, 0.0f, 2.0f, mouseX, mouseY)
		drawSliderSetting(context, lostFightPitchBounds(menu), "Pitch", workingCopy.hideonleafLostFightAlertSoundPitch, 0.1f, 2.0f, mouseX, mouseY)
		drawButtonSetting(context, playLostFightSoundBounds(menu), "Play Sound", mouseX, mouseY)
		drawButtonSetting(
			context,
			resetHideonleafTrackerBounds(menu),
			if (awaitingHideonleafResetConfirmation) "Confirm Reset Total" else "Reset Total",
			mouseX,
			mouseY,
		)
		if (colorPickerOpen) {
			drawColorPicker(context, menu, mouseX, mouseY)
		}
	}

	private fun drawPurpleTerracottaSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawColorSetting(context, purpleTerracottaColorBounds(menu), "Outline Color", ConfigField.PURPLE_TERRACOTTA_HIGHLIGHT_COLOR, mouseX, mouseY)
		drawInfoSetting(context, purpleTerracottaBlockIdBounds(menu), "Block ID", "minecraft:purple_terracotta", mouseX, mouseY)
		if (colorPickerOpen) {
			drawColorPicker(context, menu, mouseX, mouseY)
		}
	}

	private fun drawWormholeFinderSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, settingRowBounds(menu, 0, SETTING_HEIGHT), "Departure Alert", workingCopy.wormholeDepartureAlertEnabled, mouseX, mouseY)
		drawInfoSetting(context, settingRowBounds(menu, 1, TEXT_INPUT_SETTING_HEIGHT), "Message", "Your Wormhole closed up...", mouseX, mouseY)
		drawInfoSetting(context, settingRowBounds(menu, 2, TEXT_INPUT_SETTING_HEIGHT), "Render", "Water-surface ring with tracer until arrival.", mouseX, mouseY)
		drawButtonSetting(context, settingRowBounds(menu, 3, SETTING_HEIGHT), "Preview Alert", mouseX, mouseY)
		drawInfoSetting(context, settingRowBounds(menu, 4, TEXT_INPUT_SETTING_HEIGHT), "Source", "SkyHanni wormhole graph positions", mouseX, mouseY)
	}

	private fun drawAutoSprintSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, settingRowBounds(menu, 0, SETTING_HEIGHT), "Disable Fully Underwater", workingCopy.autoSprintDisableWhenFullySubmerged, mouseX, mouseY)
		drawInfoSetting(context, settingRowBounds(menu, 1, TEXT_INPUT_SETTING_HEIGHT), "Condition", "Feet and head must both be underwater.", mouseX, mouseY)
		drawInfoSetting(context, settingRowBounds(menu, 2, TEXT_INPUT_SETTING_HEIGHT), "Input", "Sprints while holding forward.", mouseX, mouseY)
	}

	private fun drawStatusSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, settingRowBounds(menu, 0, SETTING_HEIGHT), "Check for Updates", workingCopy.checkForUpdatesEnabled, mouseX, mouseY)
		drawToggleSetting(context, settingRowBounds(menu, 1, SETTING_HEIGHT), "Auto-Update", workingCopy.autoUpdateEnabled, mouseX, mouseY)
		drawButtonSetting(context, updateCheckNowBounds(menu), "Check Now", mouseX, mouseY)
		drawInfoSetting(context, updaterInfoBounds(menu), "Updater", ModUpdateChecker.statusLine(), mouseX, mouseY)
		drawButtonSetting(context, hudEditorBounds(menu), "Open HUD Editor", mouseX, mouseY)
	}

	private fun drawSilentDisconnectSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawInfoSetting(context, settingRowBounds(menu, 0, TEXT_INPUT_SETTING_HEIGHT), "State", SilentDisconnectFeature.statusLine(workingCopy), mouseX, mouseY)
		drawInfoSetting(context, settingRowBounds(menu, 1, TEXT_INPUT_SETTING_HEIGHT), "Behavior", "Sets /status offline on disconnect and restores it on rejoin.", mouseX, mouseY)
		drawInfoSetting(context, settingRowBounds(menu, 2, TEXT_INPUT_SETTING_HEIGHT), "Scope", "Hypixel only", mouseX, mouseY)
	}

	private fun drawChimeraDropSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawInfoSetting(context, settingRowBounds(menu, 0, TEXT_INPUT_SETTING_HEIGHT), "Status", ChimeraBookDropEffectsFeature.statusLine(), mouseX, mouseY)
		drawInfoSetting(context, settingRowBounds(menu, 1, TEXT_INPUT_SETTING_HEIGHT), "Trigger", "RARE DROP! Enchanted Book (Chimera I)", mouseX, mouseY)
		drawSoundSetting(context, chimeraDropSoundBounds(menu), "Sound", workingCopy.chimeraBookDropEffectsSoundId, mouseX, mouseY)
		if (soundDropdownOpen) {
			drawSoundDropdown(context, menu, mouseX, mouseY)
		}
		drawSliderSetting(context, chimeraDropVolumeBounds(menu), "Volume", workingCopy.chimeraBookDropEffectsSoundVolume, 0.0f, 2.0f, mouseX, mouseY)
		drawSliderSetting(context, chimeraDropPitchBounds(menu), "Pitch", workingCopy.chimeraBookDropEffectsSoundPitch, 0.1f, 2.0f, mouseX, mouseY)
		drawButtonSetting(context, chimeraDropTestBounds(menu), "Test Effect", mouseX, mouseY)
	}

	private fun drawTimeChangerSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawOptionSetting(context, timeChangerModeBounds(menu), "Time", ClientTimeChanger.displayName(workingCopy.timeChangerMode), mouseX, mouseY)
	}

	private fun drawAuctionHouseSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, auctionHouseAutoCopyBounds(menu), "Auto Copy Underbid", workingCopy.auctionHouseAutoCopyUnderbidEnabled, mouseX, mouseY)
		drawToggleSetting(context, highClassDiceTrackerBounds(menu), "High Class Dice Sell Tracker", workingCopy.highClassDiceTrackerEnabled, mouseX, mouseY)
	}

	private fun drawSlayerSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawDisclosureSetting(context, slayerBlazeHeaderBounds(menu), "Blaze", slayerBlazeExpanded, mouseX, mouseY)
		if (slayerBlazeExpanded) {
			drawToggleSetting(context, slayerBlazePhaseDisplayBounds(menu), "Phase Display", workingCopy.slayerBlazePhaseDisplayEnabled, mouseX, mouseY)
			drawToggleSetting(context, slayerBlazeColoredMobsBounds(menu), "Colored Mobs", workingCopy.slayerBlazeColoredMobsEnabled, mouseX, mouseY)
			drawToggleSetting(context, slayerBlazeAutoDaggerBounds(menu), "Auto Dagger", workingCopy.slayerBlazeAutoDaggerEnabled, mouseX, mouseY)
			drawOptionSetting(context, slayerBlazeAutoDaggerDelayBounds(menu), "Delay", autoDaggerDelayDisplay(workingCopy.slayerBlazeAutoDaggerDelayMaxTicks), mouseX, mouseY)
			drawToggleSetting(context, slayerBlazeAutoDaggerResetAfterBossBounds(menu), "Reset After Boss", workingCopy.slayerBlazeAutoDaggerResetAfterBossEnabled, mouseX, mouseY)
			drawToggleSetting(context, slayerBlazeAutoDaggerDebugBounds(menu), "Debug", workingCopy.slayerBlazeAutoDaggerDebugEnabled, mouseX, mouseY)
		}

		drawDisclosureSetting(context, slayerMiscHeaderBounds(menu), "Misc", slayerMiscExpanded, mouseX, mouseY)
			if (slayerMiscExpanded) {
				drawToggleSetting(context, slayerRngMeterDisplayBounds(menu), "RNG Meter Display", workingCopy.slayerRngMeterDisplayEnabled, mouseX, mouseY)
				drawToggleSetting(context, slayerRngMeterOptimalRemovalBounds(menu), "Optimal Removal Hint", workingCopy.slayerRngMeterOptimalRemovalEnabled, mouseX, mouseY)
				drawToggleSetting(context, slayerRngMeterCompactModeBounds(menu), "Compact Mode", workingCopy.slayerRngMeterCompactMode, mouseX, mouseY)
				drawToggleSetting(context, slayerRngMeterUseMagicFindBounds(menu), "Use Magic Find", workingCopy.slayerRngMeterUseMagicFind, mouseX, mouseY)
				drawOptionSetting(context, slayerRngMeterMagicFindBounds(menu), "Magic Find", workingCopy.slayerRngMeterMagicFind.coerceIn(0, 900).toString(), mouseX, mouseY)
				drawToggleSetting(context, slayerSpawnAnnouncerBounds(menu), "Spawn Announcer", workingCopy.slayerSpawnAnnouncerEnabled, mouseX, mouseY)
			drawTextInputSetting(context, slayerAnnouncerTextBounds(menu), "Announcer Text", slayerAnnouncerTextField, mouseX, mouseY)
			drawSoundSetting(context, slayerAnnouncerSoundBounds(menu), "Announcer Sound", workingCopy.slayerSpawnAnnouncerSoundId, mouseX, mouseY)
			if (soundDropdownOpen) {
				drawSoundDropdown(context, menu, mouseX, mouseY)
			}
			drawSliderSetting(context, slayerAnnouncerVolumeBounds(menu), "Volume", workingCopy.slayerSpawnAnnouncerSoundVolume, 0.0f, 2.0f, mouseX, mouseY)
			drawSliderSetting(context, slayerAnnouncerPitchBounds(menu), "Pitch", workingCopy.slayerSpawnAnnouncerSoundPitch, 0.1f, 2.0f, mouseX, mouseY)
			drawButtonSetting(context, slayerAnnouncerPreviewBounds(menu), "Preview Announcer", mouseX, mouseY)
		}
	}

	private fun autoDaggerDelayDisplay(maxTicks: Int): String {
		val normalized = maxTicks.coerceIn(2, 5)
		return if (normalized <= 2) "2 ticks" else "2-$normalized ticks"
	}

	private fun drawPestEspSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, pestEspTracerBounds(menu), "Tracer Line", workingCopy.pestEspTracerEnabled, mouseX, mouseY)
		drawColorSetting(context, pestEspColorBounds(menu), "Highlight Color", ConfigField.PEST_ESP_COLOR, mouseX, mouseY)
		if (colorPickerOpen) {
			drawColorPicker(context, menu, mouseX, mouseY)
		}
	}

	private fun drawCorpseEspSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, corpseEspLapisBounds(menu), "Lapis ESP", workingCopy.corpseEspLapisEnabled, mouseX, mouseY)
		drawInfoSetting(context, corpseEspLapisColorBounds(menu), "Lapis Color", "#2563EB", mouseX, mouseY)
		drawToggleSetting(context, corpseEspTungstenBounds(menu), "Tungsten ESP", workingCopy.corpseEspTungstenEnabled, mouseX, mouseY)
		drawInfoSetting(context, corpseEspTungstenColorBounds(menu), "Tungsten Color", "#9CA3AF", mouseX, mouseY)
		drawToggleSetting(context, corpseEspUmberBounds(menu), "Umber ESP", workingCopy.corpseEspUmberEnabled, mouseX, mouseY)
		drawInfoSetting(context, corpseEspUmberColorBounds(menu), "Umber Color", "#F97316", mouseX, mouseY)
		drawToggleSetting(context, corpseEspVanguardBounds(menu), "Vanguard ESP", workingCopy.corpseEspVanguardEnabled, mouseX, mouseY)
		drawInfoSetting(context, corpseEspVanguardColorBounds(menu), "Vanguard Color", "#7DD3FC", mouseX, mouseY)
	}

	private fun drawMobModelSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawMobModelSetting(context, mobModelEntityTypeBounds(menu), mouseX, mouseY)
		if (mobModelDropdownOpen) {
			drawMobModelDropdown(context, menu, mouseX, mouseY)
		}
		drawMobModelVariantSetting(context, mobModelVariantBounds(menu), mouseX, mouseY)
		if (mobModelVariantDropdownOpen) {
			drawMobModelVariantDropdown(context, menu, mouseX, mouseY)
		}
		drawToggleSetting(context, mobModelBabyBounds(menu), "Baby Variant", workingCopy.mobModelBaby, mouseX, mouseY)
		drawToggleSetting(context, mobModelShowArmorBounds(menu), "Show Armor", workingCopy.mobModelShowArmor, mouseX, mouseY)
		drawToggleSetting(context, mobModelShowHeldItemsBounds(menu), "Show Held Items", workingCopy.mobModelShowHeldItems, mouseX, mouseY)
		drawSliderSetting(context, mobModelScaleBounds(menu), "Scale", workingCopy.mobModelScale, 0.25f, 4.0f, mouseX, mouseY)
		drawInfoSetting(context, mobModelStatusBounds(menu), "Status", mobModelStatusLine(), mouseX, mouseY)
	}

	private fun drawInventoryPreviewSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, inventoryPreviewShowArmorBounds(menu), "Show Armor", workingCopy.inventoryPreviewShowArmor, mouseX, mouseY)
		drawInfoSetting(context, inventoryPreviewHudInfoBounds(menu), "Move / Scale", "Use the HUD Editor in Status.", mouseX, mouseY)
	}

	private fun drawCrosshairSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, crosshairShowInFirstPersonBounds(menu), "Custom Crosshair", workingCopy.customCrosshairShowInFirstPerson, mouseX, mouseY)
		drawToggleSetting(context, crosshairVisibleInF5Bounds(menu), "Visible In F5", workingCopy.customCrosshairVisibleInF5, mouseX, mouseY)
		drawButtonSetting(context, crosshairResetBounds(menu), "Reset Grid", mouseX, mouseY)
		drawCrosshairGridSetting(context, crosshairGridBounds(menu), mouseX, mouseY)
	}

	private fun drawM5Settings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, m5LividFinderBounds(menu), "Livid Finder", workingCopy.m5LividFinderEnabled, mouseX, mouseY)
		drawToggleSetting(context, m5TracerBounds(menu), "Tracer", workingCopy.m5TracerEnabled, mouseX, mouseY)
		drawToggleSetting(context, m5IceSprayBounds(menu), "Ice Spray Timer", workingCopy.m5IceSprayTimerEnabled, mouseX, mouseY)
		drawToggleSetting(context, m5RagAxeBounds(menu), "Rag Axe Alert", workingCopy.m5RagAxeAlertEnabled, mouseX, mouseY)
		drawInfoSetting(context, m5StatusBounds(menu), "Current State", M5Feature.statusLine(), mouseX, mouseY)
	}

	private fun drawDungeonAutoKickSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, dungeonAutoKickStatsDisplayBounds(menu), "Stats Display", workingCopy.dungeonAutoKickStatsDisplayEnabled, mouseX, mouseY)
		drawToggleSetting(context, dungeonAutoKickKickLineBounds(menu), "Send Kick Line", workingCopy.dungeonAutoKickSendKickLineEnabled, mouseX, mouseY)
		drawToggleSetting(context, dungeonAutoKickAutoKickBounds(menu), "Auto Kick", workingCopy.dungeonAutoKickAutoKickEnabled, mouseX, mouseY)
		drawOptionSetting(context, dungeonAutoKickFloorBounds(menu), "Floor", "${if (workingCopy.dungeonAutoKickMasterMode) "M" else "F"}${workingCopy.dungeonAutoKickFloor}", mouseX, mouseY)
		if (dungeonAutoKickFloorDropdownOpen) {
			drawDungeonAutoKickFloorDropdown(context, menu, mouseX, mouseY)
		}
		drawTextInputSetting(context, dungeonAutoKickMaxPbBounds(menu), "Max S+ PB (s)", dungeonAutoKickMaxPbField, mouseX, mouseY)
		drawTextInputSetting(context, dungeonAutoKickMinSecretsBounds(menu), "Min Secrets (k)", dungeonAutoKickMinSecretsField, mouseX, mouseY)
		drawTextInputSetting(context, dungeonAutoKickMinMpBounds(menu), "Min MP", dungeonAutoKickMinMpField, mouseX, mouseY)
		drawToggleSetting(context, dungeonAutoKickApiOffBounds(menu), "Kick API Off", workingCopy.dungeonAutoKickApiOffKickEnabled, mouseX, mouseY)
		drawToggleSetting(context, dungeonAutoKickInformBounds(menu), "Inform Kicked", workingCopy.dungeonAutoKickInformKickedEnabled, mouseX, mouseY)
		drawToggleSetting(context, dungeonAutoKickCacheBounds(menu), "Kick Cache", workingCopy.dungeonAutoKickCacheEnabled, mouseX, mouseY)
		drawToggleSetting(context, partyFinderGuiStatsBounds(menu), "PF GUI Stats", workingCopy.partyFinderGuiStatsEnabled, mouseX, mouseY)
		drawToggleSetting(context, partyFinderHighlightsBounds(menu), "PF Highlights", workingCopy.partyFinderHighlightsEnabled, mouseX, mouseY)
		drawToggleSetting(context, partyFinderMemberCountBounds(menu), "PF Member Count", workingCopy.partyFinderMemberCountEnabled, mouseX, mouseY)
		drawToggleSetting(context, partyFinderRightClickBounds(menu), "PF Right Click", workingCopy.partyFinderRightClickEnabled, mouseX, mouseY)
		drawOptionSetting(context, dungeonAutoKickClearCacheBounds(menu), "Clear Cache", "Click", mouseX, mouseY)
		drawInfoSetting(context, dungeonAutoKickStatusBounds(menu), "Current State", DungeonAutoKickFeature.statusLine(), mouseX, mouseY)
	}

	private fun drawPickaxeCooldownSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, pickaxeShowReadyBounds(menu), "Show When Ready", workingCopy.pickaxeAbilityCooldownShowReady, mouseX, mouseY)
		drawOptionSetting(context, pickaxeAlertDisclosureBounds(menu), "Alert", if (pickaxeAlertExpanded) "Expanded" else "Collapsed", mouseX, mouseY)
		if (pickaxeAlertExpanded) {
			drawToggleSetting(context, pickaxeAlertEnabledBounds(menu), "Enable Alert", workingCopy.pickaxeAbilityCooldownAlertEnabled, mouseX, mouseY)
			drawTextInputSetting(context, pickaxeAlertTextBounds(menu), "Alert Text", pickaxeAlertTextField, mouseX, mouseY)
			drawSoundSetting(context, pickaxeAlertSoundBounds(menu), "Alert Sound", workingCopy.pickaxeAbilityCooldownAlertSoundId, mouseX, mouseY)
			if (soundDropdownOpen) {
				drawSoundDropdown(context, menu, mouseX, mouseY)
			}
			drawSliderSetting(context, pickaxeAlertVolumeBounds(menu), "Volume", workingCopy.pickaxeAbilityCooldownAlertSoundVolume, 0.0f, 2.0f, mouseX, mouseY)
			drawSliderSetting(context, pickaxeAlertPitchBounds(menu), "Pitch", workingCopy.pickaxeAbilityCooldownAlertSoundPitch, 0.1f, 2.0f, mouseX, mouseY)
			drawButtonSetting(context, pickaxeAlertPreviewBounds(menu), "Preview Alert", mouseX, mouseY)
		}
		drawInfoSetting(context, pickaxeCurrentStateBounds(menu), "Current State", PickaxeAbilityCooldownFeature.statusLine(), mouseX, mouseY)
	}

	private fun drawFireFreezeSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, fireFreezeMobTimerBounds(menu), "Mob Timer", workingCopy.fireFreezeMobTimerEnabled, mouseX, mouseY)
		drawToggleSetting(context, fireFreezeFreezeTimerBounds(menu), "Freeze Timer", workingCopy.fireFreezeFreezeTimerEnabled, mouseX, mouseY)
		drawToggleSetting(context, fireFreezeStrongMobsOnlyBounds(menu), "Strong Mobs Only", workingCopy.fireFreezeStrongMobsOnly, mouseX, mouseY)
		drawToggleSetting(context, fireFreezeBoxBounds(menu), "Box Frozen Mobs", workingCopy.fireFreezeBoxFrozenMobsEnabled, mouseX, mouseY)
		drawToggleSetting(context, fireFreezeCustomCircleBounds(menu), "Custom Circle", workingCopy.fireFreezeCustomCircleEnabled, mouseX, mouseY)
		drawColorSetting(context, fireFreezeCircleColorBounds(menu), "Circle Color", ConfigField.FIRE_FREEZE_CIRCLE_COLOR, mouseX, mouseY)
		drawSliderSetting(context, fireFreezeLineWidthBounds(menu), "Radius Thickness", workingCopy.fireFreezeCircleLineWidth, 1.0f, 8.0f, mouseX, mouseY)
		drawToggleSetting(context, fireFreezeRefreezeAlertBounds(menu), "Refreeze Alert", workingCopy.fireFreezeRefreezeAlertEnabled, mouseX, mouseY)
		drawSoundSetting(context, fireFreezeAlertSoundBounds(menu), "Alert Sound", workingCopy.fireFreezeRefreezeAlertSoundId, mouseX, mouseY)
		if (soundDropdownOpen) {
			drawSoundDropdown(context, menu, mouseX, mouseY)
		}
		drawSliderSetting(context, fireFreezeAlertVolumeBounds(menu), "Volume", workingCopy.fireFreezeRefreezeAlertSoundVolume, 0.0f, 2.0f, mouseX, mouseY)
		drawSliderSetting(context, fireFreezeAlertPitchBounds(menu), "Pitch", workingCopy.fireFreezeRefreezeAlertSoundPitch, 0.1f, 2.0f, mouseX, mouseY)
		drawButtonSetting(context, fireFreezeAlertPreviewBounds(menu), "Preview Alert", mouseX, mouseY)
		if (colorPickerOpen) {
			drawColorPicker(context, menu, mouseX, mouseY)
		}
	}

	private fun drawMineshaftAutoWarpSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawTextInputSetting(context, mineshaftAutoWarpRuleBounds(menu), "Corpse Rule", mineshaftAutoWarpRuleField, mouseX, mouseY)
		drawTextInputSetting(context, mineshaftAutoWarpDelayBounds(menu), "Warp Delay (ms)", mineshaftAutoWarpDelayField, mouseX, mouseY)
		drawTextInputSetting(context, mineshaftAutoWarpWindowBounds(menu), "Warp Window (ms)", mineshaftAutoWarpWindowField, mouseX, mouseY)
		drawInfoSetting(context, mineshaftAutoWarpStatusBounds(menu), "Current State", MineshaftAutoWarpFeature.statusLine(), mouseX, mouseY)
	}

	private fun drawDeploybleSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawInfoSetting(context, settingRowBounds(menu, 0, TEXT_INPUT_SETTING_HEIGHT), "Alert", "Shows a HUD alert and sound at 10 seconds.", mouseX, mouseY)
		drawInfoSetting(context, settingRowBounds(menu, 1, TEXT_INPUT_SETTING_HEIGHT), "Types", "Totem, Black Hole, Umberella, Flare, Lantern", mouseX, mouseY)
		drawInfoSetting(context, settingRowBounds(menu, 2, TEXT_INPUT_SETTING_HEIGHT), "Status", DeploybleFeature.statusLine(), mouseX, mouseY)
	}

	private fun drawExperimentationSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, autoExperimentsAutoPairsBounds(menu), "Keep Items Visible", workingCopy.autoExperimentsAutoPairs, mouseX, mouseY)
		drawToggleSetting(context, autoExperimentsAutoCloseBounds(menu), "Auto Close", workingCopy.autoExperimentsAutoClose, mouseX, mouseY)
		drawToggleSetting(context, autoExperimentsGetMaxXpBounds(menu), "Get Max XP", workingCopy.autoExperimentsGetMaxXp, mouseX, mouseY)
		drawTextInputSetting(context, autoExperimentsClickDelayBounds(menu), "Click Delay (ms)", autoExperimentsClickDelayField, mouseX, mouseY)
		drawTextInputSetting(context, autoExperimentsDelayVarietyBounds(menu), "Delay Variety (ms)", autoExperimentsDelayVarietyField, mouseX, mouseY)
		drawTextInputSetting(context, autoExperimentsSerumCountBounds(menu), "Serum Count", autoExperimentsSerumCountField, mouseX, mouseY)
	}

	private fun drawAutoCroesusSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, autoCroesusEnabledBounds(menu), "AutoCroesus Enabled", workingCopy.autoCroesusModuleEnabled, mouseX, mouseY)
		drawToggleSetting(context, autoCroesusNoClickBounds(menu), "No Click", workingAutoCroesusConfig.noClick, mouseX, mouseY)
		drawToggleSetting(context, autoCroesusUseKismetsBounds(menu), "Use Kismets", workingAutoCroesusConfig.useKismets, mouseX, mouseY)
		drawTextInputSetting(context, autoCroesusKismetFloorsBounds(menu), "Kismet Floors", autoCroesusKismetFloorsField, mouseX, mouseY)
		drawTextInputSetting(context, autoCroesusKismetProfitBounds(menu), "Kismet Min Profit", autoCroesusKismetProfitField, mouseX, mouseY)
		drawToggleSetting(context, autoCroesusUseChestKeysBounds(menu), "Use Chest Keys", workingAutoCroesusConfig.useChestKeys, mouseX, mouseY)
		drawTextInputSetting(context, autoCroesusChestKeyProfitBounds(menu), "Chest Key Min Profit", autoCroesusChestKeyProfitField, mouseX, mouseY)
		drawTextInputSetting(context, autoCroesusClickDelayBounds(menu), "Click Delay (ms)", autoCroesusClickDelayField, mouseX, mouseY)
	}

	private fun drawDoorSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, settingRowBounds(menu, 0, SETTING_HEIGHT), "Mort Door Barrier", workingCopy.dungeonDoorEnabled, mouseX, mouseY)
		drawToggleSetting(context, settingRowBounds(menu, 1, SETTING_HEIGHT), "Debug", workingCopy.dungeonDoorDebugEnabled, mouseX, mouseY)
		drawOptionSetting(context, settingRowBounds(menu, 2, SETTING_HEIGHT), "Mode", MortDoorBarrierFeature.displayName(workingCopy.dungeonDoorMode), mouseX, mouseY)
	}

	private fun drawRedVignetteSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, settingRowBounds(menu, 0, SETTING_HEIGHT), "Fix Red Vignette", workingCopy.dungeonRedVignetteEnabled, mouseX, mouseY)
	}

	private fun drawTextInputSetting(
		context: DrawContext,
		row: Bounds,
		label: String,
		field: TextFieldWidget,
		mouseX: Int,
		mouseY: Int,
	) {
		val hovered = row.contains(mouseX, mouseY) || field.isFocused
		drawSettingBackground(context, row, hovered)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 4, TEXT_WHITE)
		context.fill(row.left + 8, row.top + 15, row.right - 8, row.top + 35, INPUT_BACKGROUND)
		context.fill(
			row.left + 8,
			row.top + 34,
			row.right - 8,
			row.top + 35,
			if (field.isFocused) ACCENT else 0x1EFFFFFF,
		)
	}

	private fun drawTextInputSetting(
		context: DrawContext,
		menu: Bounds,
		rowIndex: Int,
		label: String,
		field: TextFieldWidget,
		mouseX: Int,
		mouseY: Int,
	) {
		drawTextInputSetting(context, settingRowBounds(menu, rowIndex, TEXT_INPUT_SETTING_HEIGHT), label, field, mouseX, mouseY)
	}

	private fun drawToggleSetting(context: DrawContext, row: Bounds, label: String, enabled: Boolean, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 6, TEXT_WHITE)

		val switchWidth = 18
		val switchHeight = 6
		val switchX = row.right - switchWidth - 10
		val switchY = row.top + (SETTING_HEIGHT / 2) - (switchHeight / 2)
		context.fill(switchX, switchY, switchX + switchWidth, switchY + switchHeight, if (enabled) ACCENT_TRANS else 0x78282828)

		val knobX = switchX + if (enabled) switchWidth - 8 else 0
		context.fill(knobX, switchY - 1, knobX + 8, switchY + 7, if (enabled) ACCENT else 0xFFA0A0A0.toInt())
	}

	private fun drawSliderSetting(
		context: DrawContext,
		row: Bounds,
		label: String,
		value: Float,
		min: Float,
		max: Float,
		mouseX: Int,
		mouseY: Int,
	) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 4, TEXT_WHITE)
		context.drawTextWithShadow(textRenderer, String.format(Locale.ROOT, "%.2f", value), row.right - 42, row.top + 4, TEXT_MUTED)

		val barLeft = row.left + 8
		val barRight = row.right - 8
		val barY = row.bottom - 7
		val progress = ((value - min) / (max - min)).coerceIn(0.0f, 1.0f)
		val fillRight = barLeft + ((barRight - barLeft) * progress).toInt()
		context.fill(barLeft, barY, barRight, barY + 3, 0x78282828)
		context.fill(barLeft, barY, fillRight, barY + 3, ACCENT)
		context.fill(fillRight - 2, barY - 2, fillRight + 2, barY + 5, TEXT_WHITE)
	}

	private fun drawIntSliderSetting(
		context: DrawContext,
		row: Bounds,
		label: String,
		value: Int,
		min: Int,
		max: Int,
		mouseX: Int,
		mouseY: Int,
	) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 4, TEXT_WHITE)
		context.drawTextWithShadow(textRenderer, value.coerceIn(min, max).toString(), row.right - 18, row.top + 4, TEXT_MUTED)

		val barLeft = row.left + 8
		val barRight = row.right - 8
		val barY = row.bottom - 7
		val progress = ((value.coerceIn(min, max) - min).toFloat() / (max - min).coerceAtLeast(1)).coerceIn(0.0f, 1.0f)
		val fillRight = barLeft + ((barRight - barLeft) * progress).toInt()
		context.fill(barLeft, barY, barRight, barY + 3, 0x78282828)
		context.fill(barLeft, barY, fillRight, barY + 3, ACCENT)
		context.fill(fillRight - 2, barY - 2, fillRight + 2, barY + 5, TEXT_WHITE)
	}

	private fun drawSoundSetting(context: DrawContext, row: Bounds, label: String, soundId: String, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered || soundDropdownOpen)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 4, TEXT_WHITE)
		context.drawTextWithShadow(textRenderer, trimToWidth(SoundCatalog.displayName(soundId), 92), row.right - 100, row.top + 4, TEXT_MUTED)
		if (soundDropdownOpen) {
			context.fill(row.left, row.bottom - 1, row.right, row.bottom, ACCENT)
		}
	}

	private fun drawSoundDropdown(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		val searchField = activeSoundSearchField()
		val selectedSoundId = activeSelectedSoundId()
		val search = soundSearchBounds(menu)
		val list = soundListBounds(menu)
		context.fill(search.left - 4, search.top - 4, search.right + 4, list.bottom + 4, INPUT_BACKGROUND)
		context.fill(search.left, search.top, search.right, search.bottom, 0xC80F0F0F.toInt())
		context.fill(search.left, search.bottom - 1, search.right, search.bottom, if (searchField.isFocused) ACCENT else 0x1EFFFFFF)

		val filtered = SoundCatalog.filtered(searchField.text)
		val maxScroll = (filtered.size - SOUND_VISIBLE_ROWS).coerceAtLeast(0)
		soundScrollOffset = soundScrollOffset.coerceIn(0, maxScroll)

		context.enableScissor(list.left, list.top, list.right, list.bottom)
		filtered.drop(soundScrollOffset).take(SOUND_VISIBLE_ROWS).forEachIndexed { index, sound ->
			val row = Bounds(list.left, list.top + (index * SOUND_ROW_HEIGHT), list.right, list.top + ((index + 1) * SOUND_ROW_HEIGHT))
			val hovered = row.contains(mouseX, mouseY)
			val selected = sound.id == selectedSoundId
			if (hovered) {
				context.fill(row.left, row.top, row.right, row.bottom, HOVER)
			}
			val textColor = if (selected) ACCENT else if (hovered) TEXT_WHITE else TEXT_MUTED
			context.drawTextWithShadow(textRenderer, trimToWidth(sound.name, SOUND_LIST_TEXT_WIDTH), row.left + 4, row.top + 3, textColor)
		}
		context.disableScissor()
	}

	private fun drawMobModelSetting(context: DrawContext, row: Bounds, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered || mobModelDropdownOpen)
		context.drawTextWithShadow(textRenderer, "Mob Model", row.left + 8 + if (hovered) 2 else 0, row.top + 4, TEXT_WHITE)
		context.drawTextWithShadow(
			textRenderer,
			trimToWidth(MobModelCatalog.displayName(workingCopy.mobModelEntityType), 84),
			row.right - 92,
			row.top + 4,
			TEXT_MUTED,
		)
		if (mobModelDropdownOpen) {
			context.fill(row.left, row.bottom - 1, row.right, row.bottom, ACCENT)
		}
	}

	private fun drawMobModelDropdown(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		val search = mobModelSearchBounds(menu)
		val list = mobModelListBounds(menu)
		context.fill(search.left - 4, search.top - 4, search.right + 4, list.bottom + 4, INPUT_BACKGROUND)
		context.fill(search.left, search.top, search.right, search.bottom, 0xC80F0F0F.toInt())
		context.fill(search.left, search.bottom - 1, search.right, search.bottom, if (mobModelEntityTypeField.isFocused) ACCENT else 0x1EFFFFFF)

		val filtered = filteredMobModelOptions(mobModelEntityTypeField.text)
		val maxScroll = (filtered.size - MOB_MODEL_VISIBLE_ROWS).coerceAtLeast(0)
		mobModelScrollOffset = mobModelScrollOffset.coerceIn(0, maxScroll)

		context.enableScissor(list.left, list.top, list.right, list.bottom)
		filtered.drop(mobModelScrollOffset).take(MOB_MODEL_VISIBLE_ROWS).forEachIndexed { index, entityId ->
			val row = Bounds(list.left, list.top + (index * SOUND_ROW_HEIGHT), list.right, list.top + ((index + 1) * SOUND_ROW_HEIGHT))
			val hovered = row.contains(mouseX, mouseY)
			val selected = entityId == workingCopy.mobModelEntityType
			if (hovered) {
				context.fill(row.left, row.top, row.right, row.bottom, HOVER)
			}
			val textColor = if (selected) ACCENT else if (hovered) TEXT_WHITE else TEXT_MUTED
			context.drawTextWithShadow(textRenderer, trimToWidth(MobModelCatalog.displayName(entityId), SOUND_LIST_TEXT_WIDTH + 20), row.left + 4, row.top + 3, textColor)
		}
		context.disableScissor()
	}

	private fun drawMobModelVariantSetting(context: DrawContext, row: Bounds, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered || mobModelVariantDropdownOpen)
		context.drawTextWithShadow(textRenderer, "Variant", row.left + 8 + if (hovered) 2 else 0, row.top + 4, TEXT_WHITE)
		val selectedVariant = workingCopy.mobModelVariant.ifBlank { "Default" }
		context.drawTextWithShadow(
			textRenderer,
			trimToWidth(selectedVariant, 106),
			row.right - 114,
			row.top + 4,
			TEXT_MUTED,
		)
		if (mobModelVariantDropdownOpen) {
			context.fill(row.left, row.bottom - 1, row.right, row.bottom, ACCENT)
		}
	}

	private fun drawMobModelVariantDropdown(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		val search = mobModelVariantSearchBounds(menu)
		val list = mobModelVariantListBounds(menu)
		context.fill(search.left - 4, search.top - 4, search.right + 4, list.bottom + 4, INPUT_BACKGROUND)
		context.fill(search.left, search.top, search.right, search.bottom, 0xC80F0F0F.toInt())
		context.fill(search.left, search.bottom - 1, search.right, search.bottom, if (mobModelVariantField.isFocused) ACCENT else 0x1EFFFFFF)

		val filtered = filteredMobModelVariantOptions(mobModelVariantField.text)
		val maxScroll = (filtered.size - MOB_MODEL_VISIBLE_ROWS).coerceAtLeast(0)
		mobModelVariantScrollOffset = mobModelVariantScrollOffset.coerceIn(0, maxScroll)

		context.enableScissor(list.left, list.top, list.right, list.bottom)
		filtered.drop(mobModelVariantScrollOffset).take(MOB_MODEL_VISIBLE_ROWS).forEachIndexed { index, variantId ->
			val row = Bounds(list.left, list.top + (index * SOUND_ROW_HEIGHT), list.right, list.top + ((index + 1) * SOUND_ROW_HEIGHT))
			val hovered = row.contains(mouseX, mouseY)
			val selected = if (variantId == MobModelVariantCatalog.DEFAULT_OPTION) {
				workingCopy.mobModelVariant.isBlank()
			} else {
				variantId == workingCopy.mobModelVariant
			}
			if (hovered) {
				context.fill(row.left, row.top, row.right, row.bottom, HOVER)
			}
			val textColor = if (selected) ACCENT else if (hovered) TEXT_WHITE else TEXT_MUTED
			val label = if (variantId == MobModelVariantCatalog.DEFAULT_OPTION) "Default" else variantId
			context.drawTextWithShadow(textRenderer, trimToWidth(label, SOUND_LIST_TEXT_WIDTH + 20), row.left + 4, row.top + 3, textColor)
		}
		context.disableScissor()
	}

	private fun drawDungeonAutoKickFloorDropdown(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		val list = dungeonAutoKickFloorListBounds(menu)
		context.fill(list.left - 4, list.top - 4, list.right + 4, list.bottom + 4, INPUT_BACKGROUND)
		val maxScroll = (DUNGEON_AUTOKICK_FLOOR_OPTIONS.size - DUNGEON_AUTOKICK_FLOOR_VISIBLE_ROWS).coerceAtLeast(0)
		dungeonAutoKickFloorScrollOffset = dungeonAutoKickFloorScrollOffset.coerceIn(0, maxScroll)
		val current = "${if (workingCopy.dungeonAutoKickMasterMode) "M" else "F"}${workingCopy.dungeonAutoKickFloor}"

		context.enableScissor(list.left, list.top, list.right, list.bottom)
		DUNGEON_AUTOKICK_FLOOR_OPTIONS.drop(dungeonAutoKickFloorScrollOffset).take(DUNGEON_AUTOKICK_FLOOR_VISIBLE_ROWS).forEachIndexed { index, option ->
			val row = Bounds(list.left, list.top + (index * SOUND_ROW_HEIGHT), list.right, list.top + ((index + 1) * SOUND_ROW_HEIGHT))
			val hovered = row.contains(mouseX, mouseY)
			val selected = option == current
			if (hovered) {
				context.fill(row.left, row.top, row.right, row.bottom, HOVER)
			}
			if (selected) {
				context.fill(row.left, row.top, row.left + 2, row.bottom, ACCENT)
			}
			val textColor = if (selected) ACCENT else if (hovered) TEXT_WHITE else TEXT_MUTED
			context.drawTextWithShadow(textRenderer, option, row.left + 8, row.top + 3, textColor)
		}
		context.disableScissor()
	}

	private fun drawButtonSetting(context: DrawContext, row: Bounds, label: String, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered)
		if (hovered) {
			context.fill(row.left, row.top, row.right, row.bottom, 0x0AFFFFFF)
		}
		context.drawCenteredTextWithShadow(textRenderer, label, (row.left + row.right) / 2, row.top + 6, TEXT_WHITE)
		if (hovered) {
			val lineWidth = textRenderer.getWidth(label) + 10
			val lineLeft = ((row.left + row.right) / 2) - (lineWidth / 2)
			context.fill(lineLeft, row.top + 16, lineLeft + lineWidth, row.top + 17, ACCENT)
		}
	}

	private fun drawDisclosureSetting(context: DrawContext, row: Bounds, label: String, expanded: Boolean, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered)
		context.fill(row.left, row.top, row.left + 3, row.bottom, ACCENT)
		val marker = if (expanded) "v" else ">"
		context.drawTextWithShadow(textRenderer, marker, row.left + 10, row.top + 6, ACCENT)
		context.drawTextWithShadow(textRenderer, label, row.left + 24 + if (hovered) 2 else 0, row.top + 6, TEXT_WHITE)
	}

	private fun drawInfoSetting(context: DrawContext, row: Bounds, label: String, value: String, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 4, TEXT_WHITE)
		context.drawTextWithShadow(textRenderer, trimToWidth(value, TEXT_INPUT_WIDTH), row.left + 8, row.top + 20, TEXT_MUTED)
	}

	private fun drawCrosshairGridSetting(context: DrawContext, row: Bounds, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered)
		context.drawTextWithShadow(textRenderer, "Crosshair Grid", row.left + 8 + if (hovered) 2 else 0, row.top + 4, TEXT_WHITE)

		val cells = CustomCrosshairFeature.decode(workingCopy.customCrosshairPattern)
		val grid = crosshairGridEditorBounds(row)
		for (gridRow in 0 until CustomCrosshairFeature.GRID_SIZE) {
			for (gridColumn in 0 until CustomCrosshairFeature.GRID_SIZE) {
				val cell = crosshairCellBounds(grid, gridRow, gridColumn)
				val active = cells[gridRow * CustomCrosshairFeature.GRID_SIZE + gridColumn]
				val cellHovered = cell.contains(mouseX, mouseY)
				val fillColor = when {
					active && cellHovered -> ACCENT
					active -> SELECTED
					cellHovered -> HOVER
					else -> 0x44101010
				}
				context.fill(cell.left, cell.top, cell.right, cell.bottom, fillColor)
				context.fill(cell.left, cell.top, cell.right, cell.top + 1, 0x50FFFFFF)
				context.fill(cell.left, cell.bottom - 1, cell.right, cell.bottom, 0x50FFFFFF)
				context.fill(cell.left, cell.top, cell.left + 1, cell.bottom, 0x50FFFFFF)
				context.fill(cell.right - 1, cell.top, cell.right, cell.bottom, 0x50FFFFFF)
			}
		}
	}

	private fun drawOptionSetting(context: DrawContext, row: Bounds, label: String, value: String, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 6, TEXT_WHITE)
		context.drawTextWithShadow(textRenderer, trimToWidth(value, 110), row.right - 118, row.top + 6, TEXT_MUTED)
		if (hovered) {
			context.fill(row.right - 14, row.top + 7, row.right - 9, row.top + 12, ACCENT)
		}
	}

	private fun drawColorSetting(context: DrawContext, row: Bounds, label: String, field: ConfigField, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		val active = openColorField == field
		drawSettingBackground(context, row, hovered || active)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 6, TEXT_WHITE)
		drawColorPreview(context, row, colorFieldWidget(field).text)
		if (active) {
			context.fill(row.left, row.bottom - 1, row.right, row.bottom, ACCENT)
		}
	}

	private fun colorFieldWidget(field: ConfigField): TextFieldWidget {
		return when (field) {
			ConfigField.SHULKER_GLOW_COLOR -> shulkerGlowColorHexField
			ConfigField.SHULKER_PROJECTILE_GLOW_COLOR -> shulkerProjectileGlowColorHexField
			ConfigField.SHULKER_TRACER_LINE_COLOR -> shulkerTracerLineColorHexField
			ConfigField.PURPLE_TERRACOTTA_HIGHLIGHT_COLOR -> purpleTerracottaHighlightColorHexField
			ConfigField.PEST_ESP_COLOR -> pestEspColorHexField
			ConfigField.FIRE_FREEZE_CIRCLE_COLOR -> fireFreezeCircleColorHexField
			else -> shulkerGlowColorHexField
		}
	}

	private fun drawColorPreview(context: DrawContext, row: Bounds, hex: String) {
		val color = normalizedHexColor(hex)?.removePrefix("#")?.toInt(16) ?: return
		val swatchRight = row.right - 10
		val swatchLeft = swatchRight - 14
		val swatchTop = row.top + 4
		context.fill(swatchLeft - 1, swatchTop - 1, swatchRight + 1, swatchTop + 15, TEXT_WHITE)
		context.fill(swatchLeft, swatchTop, swatchRight, swatchTop + 14, 0xFF000000.toInt() or color)
	}

	private fun drawColorPicker(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		val field = openColorField ?: return
		val widget = colorFieldWidget(field)
		val currentColor = normalizedHexColor(widget.text)?.removePrefix("#")?.toInt(16) ?: DEFAULT_GLOW_COLOR
		val red = currentColor shr 16 and 0xFF
		val green = currentColor shr 8 and 0xFF
		val blue = currentColor and 0xFF
		val hsb = Color.RGBtoHSB(red, green, blue, null)
		val hue = hsb[0]
		val saturation = hsb[1]
		val brightness = hsb[2]

		context.drawCenteredTextWithShadow(textRenderer, "Colors", (menu.left + menu.right) / 2, colorTitleY(menu), ACCENT)

		val sv = colorSvBounds(menu)
		drawSaturationBrightnessBox(context, sv, hue)
		drawPickerCursor(context, sv.left + (saturation * sv.width()).toInt(), sv.top + ((1f - brightness) * sv.height()).toInt(), 4)

		val hueBar = colorHueBounds(menu)
		drawHueBar(context, hueBar)
		val hueY = hueBar.top + (hue * hueBar.height()).toInt()
		context.fill(hueBar.left - 2, hueY - 2, hueBar.right + 2, hueY + 3, TEXT_WHITE)
		context.fill(hueBar.left - 1, hueY - 1, hueBar.right + 1, hueY + 2, 0xFF202020.toInt())

		context.drawTextWithShadow(textRenderer, "Hex: ${normalizedHexColor(widget.text) ?: "#36C5F0"}", menu.left + 18, colorHexY(menu), TEXT_WHITE)
	}

	private fun drawSaturationBrightnessBox(context: DrawContext, bounds: Bounds, hue: Float) {
		val baseColor = Color.HSBtoRGB(hue, 1f, 1f) and 0xFFFFFF
		val width = bounds.width().coerceAtLeast(1)
		val height = bounds.height().coerceAtLeast(1)

		for (xOffset in 0 until width step COLOR_PICKER_STEP) {
			val saturation = xOffset.toFloat() / (width - 1).coerceAtLeast(1)
			for (yOffset in 0 until height step COLOR_PICKER_STEP) {
				val brightness = 1f - (yOffset.toFloat() / (height - 1).coerceAtLeast(1))
				val color = Color.HSBtoRGB(hue, saturation, brightness) and 0xFFFFFF
				context.fill(
					bounds.left + xOffset,
					bounds.top + yOffset,
					(bounds.left + xOffset + COLOR_PICKER_STEP).coerceAtMost(bounds.right),
					(bounds.top + yOffset + COLOR_PICKER_STEP).coerceAtMost(bounds.bottom),
					0xFF000000.toInt() or color,
				)
			}
		}

		context.fill(bounds.left, bounds.top, bounds.right, bounds.top + 1, 0x50FFFFFF)
		context.fill(bounds.left, bounds.bottom - 1, bounds.right, bounds.bottom, 0x80000000.toInt())
		context.fill(bounds.left, bounds.top, bounds.left + 1, bounds.bottom, 0x50FFFFFF)
		context.fill(bounds.right - 1, bounds.top, bounds.right, bounds.bottom, 0x80000000.toInt())
		context.fill(bounds.right - 14, bounds.top + 4, bounds.right - 4, bounds.top + 14, 0xFF000000.toInt() or baseColor)
	}

	private fun drawHueBar(context: DrawContext, bounds: Bounds) {
		val height = bounds.height().coerceAtLeast(1)
		for (yOffset in 0 until height step COLOR_PICKER_STEP) {
			val hue = yOffset.toFloat() / (height - 1).coerceAtLeast(1)
			val color = Color.HSBtoRGB(hue, 1f, 1f) and 0xFFFFFF
			context.fill(
				bounds.left,
				bounds.top + yOffset,
				bounds.right,
				(bounds.top + yOffset + COLOR_PICKER_STEP).coerceAtMost(bounds.bottom),
				0xFF000000.toInt() or color,
			)
		}
	}

	private fun drawPickerCursor(context: DrawContext, centerX: Int, centerY: Int, radius: Int) {
		context.fill(centerX - radius, centerY - radius, centerX + radius + 1, centerY - radius + 1, TEXT_WHITE)
		context.fill(centerX - radius, centerY + radius, centerX + radius + 1, centerY + radius + 1, TEXT_WHITE)
		context.fill(centerX - radius, centerY - radius, centerX - radius + 1, centerY + radius + 1, TEXT_WHITE)
		context.fill(centerX + radius, centerY - radius, centerX + radius + 1, centerY + radius + 1, TEXT_WHITE)
		context.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, 0xFF202020.toInt())
	}

	private fun drawSettingBackground(context: DrawContext, row: Bounds, hovered: Boolean) {
		context.fill(row.left, row.top, row.right, row.bottom, SETTING_BACKGROUND)
		if (hovered) {
			context.fill(row.left, row.top + 3, row.left + 2, row.bottom - 3, ACCENT)
		}
	}

	private fun drawTooltip(context: DrawContext, mouseX: Int, mouseY: Int) {
		if (openedSection != null) {
			return
		}
		val section = sectionAt(mouseX, mouseY) ?: return
		context.drawTooltip(textRenderer, Text.literal(section.description), mouseX, mouseY)
	}

	private fun sectionAt(mouseX: Int, mouseY: Int): ConfigSection? {
		var x = 20
		sectionRows.forEach { panel ->
			val result = sectionAt(panel, x, 20, mouseX, mouseY)
			if (result != null) {
				return result
			}
			x += 124
		}
		return null
	}

	private fun sectionAt(panel: ConfigPanel, x: Int, y: Int, mouseX: Int, mouseY: Int): ConfigSection? {
		if (mouseX < x || mouseX > x + PANEL_WIDTH || mouseY < y + PANEL_HEADER_HEIGHT) {
			return null
		}

		var rowY = y + PANEL_HEADER_HEIGHT
		filteredSections(panel.sections).forEach { section ->
			if (mouseY in rowY..(rowY + PANEL_ROW_HEIGHT)) {
				return section
			}
			rowY += PANEL_ROW_HEIGHT
		}
		return null
	}

	private fun filteredSections(sections: List<ConfigSection>): List<ConfigSection> {
		val query = searchField.text.trim()
		if (query.isBlank()) {
			return sections
		}

		return sections.filter {
			it.label.contains(query, ignoreCase = true) ||
				it.description.contains(query, ignoreCase = true)
		}
	}

	private fun settingsBounds(): Bounds {
		val menuWidth = POPUP_WIDTH.coerceAtMost((width - 40).coerceAtLeast(POPUP_WIDTH))
		val targetHeight = when (openedSection) {
			ConfigSection.SETUP -> SETUP_POPUP_HEIGHT
			ConfigSection.IRC_BRIDGE -> IRC_POPUP_HEIGHT
			ConfigSection.CHAT -> CHAT_POPUP_HEIGHT
			ConfigSection.HIDEONLEAF_HELPER -> HIDEONLEAF_POPUP_HEIGHT
			ConfigSection.PURPLE_TERRACOTTA -> PURPLE_TERRACOTTA_POPUP_HEIGHT
			ConfigSection.FLOOR_DROP_ESP -> 120
			ConfigSection.AUTO_SPRINT -> 160
			ConfigSection.TIME_CHANGER -> TIME_CHANGER_POPUP_HEIGHT
			ConfigSection.AUCTION_HOUSE -> AUCTION_HOUSE_POPUP_HEIGHT
			ConfigSection.SLAYER -> slayerPopupHeight()
			ConfigSection.PEST_ESP -> PEST_ESP_POPUP_HEIGHT
			ConfigSection.CORPSE_ESP -> CORPSE_ESP_POPUP_HEIGHT
			ConfigSection.MOB_MODEL -> mobModelPopupHeight()
			ConfigSection.CROSSHAIR -> CROSSHAIR_POPUP_HEIGHT
			ConfigSection.INVENTORY_PREVIEW -> INVENTORY_PREVIEW_POPUP_HEIGHT
			ConfigSection.SILENT_DISCONNECT -> SILENT_DISCONNECT_POPUP_HEIGHT
			ConfigSection.CHIMERA_DROP -> if (soundDropdownOpen) CHIMERA_DROP_POPUP_WITH_DROPDOWN_HEIGHT else CHIMERA_DROP_POPUP_HEIGHT
			ConfigSection.M5 -> M5_POPUP_HEIGHT
			ConfigSection.DUNGEON_AUTOKICK -> if (dungeonAutoKickFloorDropdownOpen) DUNGEON_AUTOKICK_POPUP_WITH_DROPDOWN_HEIGHT else DUNGEON_AUTOKICK_POPUP_HEIGHT
			ConfigSection.PICKAXE_COOLDOWN -> pickaxeCooldownPopupHeight()
			ConfigSection.FIRE_FREEZE -> if (soundDropdownOpen) FIRE_FREEZE_POPUP_WITH_DROPDOWN_HEIGHT else FIRE_FREEZE_POPUP_HEIGHT
			ConfigSection.MINESHAFT_AUTOWARP -> MINESHAFT_AUTOWARP_POPUP_HEIGHT
			ConfigSection.DEPLOYBLE -> 165
			ConfigSection.EXPERIMENTS -> 340
			ConfigSection.AUTO_CROESUS -> 335
			ConfigSection.DOOR -> 135
			ConfigSection.RED_VIGNETTE -> 100
			ConfigSection.STATUS -> STATUS_POPUP_HEIGHT
			else -> POPUP_HEIGHT
		}
		val menuHeight = targetHeight.coerceAtMost((height - 80).coerceAtLeast(targetHeight))
		val left = (width / 2) - (menuWidth / 2)
		val top = (height / 2) - (menuHeight / 2)
		return Bounds(left, top, left + menuWidth, top + menuHeight)
	}

	private fun pickaxeCooldownPopupHeight(): Int {
		return when {
			!pickaxeAlertExpanded -> PICKAXE_COOLDOWN_POPUP_COLLAPSED_HEIGHT
			soundDropdownOpen -> PICKAXE_COOLDOWN_POPUP_EXPANDED_WITH_DROPDOWN_HEIGHT
			else -> PICKAXE_COOLDOWN_POPUP_EXPANDED_HEIGHT
		}
	}

	private fun mobModelPopupHeight(): Int {
		return if (mobModelDropdownOpen || mobModelVariantDropdownOpen) MOB_MODEL_POPUP_WITH_DROPDOWN_HEIGHT else MOB_MODEL_POPUP_HEIGHT
	}

	private fun slayerPopupHeight(): Int {
		val menu = Bounds(0, 0, POPUP_WIDTH, 0)
		val lastRow = if (slayerMiscExpanded) slayerAnnouncerPreviewBounds(menu) else slayerMiscHeaderBounds(menu)
		return lastRow.bottom + 38
	}

	private fun handleSettingsClick(section: ConfigSection, mouseX: Int, mouseY: Int, button: Int): Boolean {
		if (button != LEFT_MOUSE_BUTTON) {
			return true
		}

		val menu = settingsBounds()
		if (section == ConfigSection.HIDEONLEAF_HELPER && settingRowBounds(menu, 0, SETTING_HEIGHT).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.shulkerGlowEnabled = !workingCopy.shulkerGlowEnabled
			return true
		}

		if (section == ConfigSection.IRC_BRIDGE && ircTestConnectionBounds(menu).contains(mouseX, mouseY)) {
			testConnection()
			return true
		}

		if (section == ConfigSection.HIDEONLEAF_HELPER) {
			if (!resetHideonleafTrackerBounds(menu).contains(mouseX, mouseY)) {
				awaitingHideonleafResetConfirmation = false
			}

			val clickedColorField = when {
				shulkerGlowColorBounds(menu).contains(mouseX, mouseY) -> ConfigField.SHULKER_GLOW_COLOR
				projectileGlowColorBounds(menu).contains(mouseX, mouseY) -> ConfigField.SHULKER_PROJECTILE_GLOW_COLOR
				tracerLineColorBounds(menu).contains(mouseX, mouseY) -> ConfigField.SHULKER_TRACER_LINE_COLOR
				else -> null
			}
			if (clickedColorField != null) {
				openColorField = if (openColorField == clickedColorField) null else clickedColorField
				soundDropdownOpen = false
				draggingColorPicker = null
				return true
			}

			if (tracerLineBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.LINE_MODE
				updateSliderFromMouse(mouseX, SliderDragTarget.LINE_MODE)
				return true
			}

			if (tracerLineWidthBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.LINE_WIDTH
				updateSliderFromMouse(mouseX, SliderDragTarget.LINE_WIDTH)
				return true
			}

			if (lostFightAlertBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.hideonleafLostFightAlertEnabled = !workingCopy.hideonleafLostFightAlertEnabled
				return true
			}

			if (shareDataBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.hideonleafShareDataEnabled = !workingCopy.hideonleafShareDataEnabled
				return true
			}

			if (lostFightSoundBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				openColorField = null
				mobModelDropdownOpen = false
				mobModelVariantDropdownOpen = false
				soundDropdownOpen = !soundDropdownOpen
				soundScrollOffset = 0
				layoutWidgets()
				return true
			}

			if (soundDropdownOpen && soundListBounds(menu).contains(mouseX, mouseY)) {
				val index = soundScrollOffset + ((mouseY - soundListBounds(menu).top) / SOUND_ROW_HEIGHT)
				val filtered = SoundCatalog.filtered(lostFightSoundSearchField.text)
				if (index in filtered.indices) {
					readWorkingCopyFromFields(updateStatus = false)
					workingCopy.hideonleafLostFightAlertSoundId = filtered[index].id
					soundDropdownOpen = false
					layoutWidgets()
				}
				return true
			}

			if (lostFightVolumeBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.ALERT_VOLUME
				updateSliderFromMouse(mouseX, SliderDragTarget.ALERT_VOLUME)
				return true
			}

			if (lostFightPitchBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.ALERT_PITCH
				updateSliderFromMouse(mouseX, SliderDragTarget.ALERT_PITCH)
				return true
			}

			if (playLostFightSoundBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				awaitingHideonleafResetConfirmation = false
				mod.playHideonleafLostFightSound(workingCopy)
				return true
			}

			if (resetHideonleafTrackerBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				if (!awaitingHideonleafResetConfirmation) {
					awaitingHideonleafResetConfirmation = true
					statusMessage = Text.literal("Click again to reset Hideonleaf total data.")
				} else {
					awaitingHideonleafResetConfirmation = false
					HideonleafShardTracker.resetTotal()
					statusMessage = Text.literal("Hideonleaf total data reset.")
				}
				return true
			}

			if (!colorPickerOpen) {
				return false
			}

			val target = when {
				colorSvBounds(menu).contains(mouseX, mouseY) -> ColorPickerDragTarget.SATURATION_BRIGHTNESS
				colorHueBounds(menu).contains(mouseX, mouseY) -> ColorPickerDragTarget.HUE
				else -> null
			}

			if (target != null) {
				draggingColorPicker = target
				updateColorFromPicker(mouseX, mouseY, target)
				return true
			}
		}

		if (section == ConfigSection.PURPLE_TERRACOTTA) {
			if (purpleTerracottaColorBounds(menu).contains(mouseX, mouseY)) {
				openColorField = if (openColorField == ConfigField.PURPLE_TERRACOTTA_HIGHLIGHT_COLOR) null else ConfigField.PURPLE_TERRACOTTA_HIGHLIGHT_COLOR
				soundDropdownOpen = false
				draggingColorPicker = null
				return true
			}

			if (!colorPickerOpen) {
				return false
			}

			val target = when {
				colorSvBounds(menu).contains(mouseX, mouseY) -> ColorPickerDragTarget.SATURATION_BRIGHTNESS
				colorHueBounds(menu).contains(mouseX, mouseY) -> ColorPickerDragTarget.HUE
				else -> null
			}

			if (target != null) {
				draggingColorPicker = target
				updateColorFromPicker(mouseX, mouseY, target)
				return true
			}
		}

		if (section == ConfigSection.WORMHOLE_FINDER) {
			if (settingRowBounds(menu, 0, SETTING_HEIGHT).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.wormholeDepartureAlertEnabled = !workingCopy.wormholeDepartureAlertEnabled
				return true
			}

			if (settingRowBounds(menu, 3, SETTING_HEIGHT).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				WormholeFinderFeature.triggerDepartureAlert(workingCopy)
				return true
			}
		}

		if (section == ConfigSection.AUTO_SPRINT) {
			if (settingRowBounds(menu, 0, SETTING_HEIGHT).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.autoSprintDisableWhenFullySubmerged = !workingCopy.autoSprintDisableWhenFullySubmerged
				return true
			}
		}

		if (section == ConfigSection.IRC_BRIDGE && coopRelayToggleBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.coopChatRelayEnabled = !workingCopy.coopChatRelayEnabled
			return true
		}

		if (section == ConfigSection.CHAT && chatImplosionHiderBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.chatImplosionHiderEnabled = !workingCopy.chatImplosionHiderEnabled
			return true
		}

		if (section == ConfigSection.TIME_CHANGER && timeChangerModeBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.timeChangerMode = (workingCopy.timeChangerMode + 1) % ClientTimeChanger.modeCount
			return true
		}

		if (section == ConfigSection.AUCTION_HOUSE && auctionHouseAutoCopyBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.auctionHouseAutoCopyUnderbidEnabled = !workingCopy.auctionHouseAutoCopyUnderbidEnabled
			return true
		}

		if (section == ConfigSection.AUCTION_HOUSE && highClassDiceTrackerBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.highClassDiceTrackerEnabled = !workingCopy.highClassDiceTrackerEnabled
			return true
		}


		if (section == ConfigSection.SLAYER) {
			if (slayerBlazeHeaderBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				slayerBlazeExpanded = !slayerBlazeExpanded
				layoutWidgets()
				return true
			}

			if (slayerBlazeExpanded && slayerBlazePhaseDisplayBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.slayerBlazePhaseDisplayEnabled = !workingCopy.slayerBlazePhaseDisplayEnabled
				return true
			}

			if (slayerBlazeExpanded && slayerBlazeColoredMobsBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.slayerBlazeColoredMobsEnabled = !workingCopy.slayerBlazeColoredMobsEnabled
				return true
			}

			if (slayerBlazeExpanded && slayerBlazeAutoDaggerBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.slayerBlazeAutoDaggerEnabled = !workingCopy.slayerBlazeAutoDaggerEnabled
				return true
			}

			if (slayerBlazeExpanded && slayerBlazeAutoDaggerDelayBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.slayerBlazeAutoDaggerDelayMaxTicks =
					if (workingCopy.slayerBlazeAutoDaggerDelayMaxTicks >= 5) 2 else workingCopy.slayerBlazeAutoDaggerDelayMaxTicks + 1
				return true
			}

			if (slayerBlazeExpanded && slayerBlazeAutoDaggerResetAfterBossBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.slayerBlazeAutoDaggerResetAfterBossEnabled = !workingCopy.slayerBlazeAutoDaggerResetAfterBossEnabled
				return true
			}

			if (slayerBlazeExpanded && slayerBlazeAutoDaggerDebugBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.slayerBlazeAutoDaggerDebugEnabled = !workingCopy.slayerBlazeAutoDaggerDebugEnabled
				return true
			}

			if (slayerMiscHeaderBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				slayerMiscExpanded = !slayerMiscExpanded
				if (!slayerMiscExpanded) {
					soundDropdownOpen = false
				}
				layoutWidgets()
				return true
			}

			if (slayerMiscExpanded && slayerRngMeterDisplayBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.slayerRngMeterDisplayEnabled = !workingCopy.slayerRngMeterDisplayEnabled
				return true
			}

			if (slayerMiscExpanded && slayerRngMeterOptimalRemovalBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.slayerRngMeterOptimalRemovalEnabled = !workingCopy.slayerRngMeterOptimalRemovalEnabled
				return true
			}

			if (slayerMiscExpanded && slayerRngMeterCompactModeBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.slayerRngMeterCompactMode = !workingCopy.slayerRngMeterCompactMode
				return true
			}

			if (slayerMiscExpanded && slayerRngMeterUseMagicFindBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.slayerRngMeterUseMagicFind = !workingCopy.slayerRngMeterUseMagicFind
				return true
			}

			if (slayerMiscExpanded && slayerRngMeterMagicFindBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.slayerRngMeterMagicFind =
					if (workingCopy.slayerRngMeterMagicFind >= 900) 0 else ((workingCopy.slayerRngMeterMagicFind + 25) / 25) * 25
				return true
			}

			if (slayerMiscExpanded && slayerSpawnAnnouncerBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.slayerSpawnAnnouncerEnabled = !workingCopy.slayerSpawnAnnouncerEnabled
				return true
			}

			if (slayerMiscExpanded && slayerAnnouncerSoundBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				mobModelDropdownOpen = false
				mobModelVariantDropdownOpen = false
				soundDropdownOpen = !soundDropdownOpen
				soundScrollOffset = 0
				layoutWidgets()
				return true
			}

			if (slayerMiscExpanded && soundDropdownOpen && soundListBounds(menu).contains(mouseX, mouseY)) {
				val index = soundScrollOffset + ((mouseY - soundListBounds(menu).top) / SOUND_ROW_HEIGHT)
				val filtered = SoundCatalog.filtered(activeSoundSearchField().text)
				if (index in filtered.indices) {
					readWorkingCopyFromFields(updateStatus = false)
					workingCopy.slayerSpawnAnnouncerSoundId = filtered[index].id
					soundDropdownOpen = false
					layoutWidgets()
				}
				return true
			}

			if (slayerMiscExpanded && slayerAnnouncerVolumeBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.SLAYER_ANNOUNCER_VOLUME
				updateSliderFromMouse(mouseX, SliderDragTarget.SLAYER_ANNOUNCER_VOLUME)
				return true
			}

			if (slayerMiscExpanded && slayerAnnouncerPitchBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.SLAYER_ANNOUNCER_PITCH
				updateSliderFromMouse(mouseX, SliderDragTarget.SLAYER_ANNOUNCER_PITCH)
				return true
			}

			if (slayerMiscExpanded && slayerAnnouncerPreviewBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				SlayerFeature.playPreview(workingCopy)
				return true
			}
		}

		if (section == ConfigSection.PEST_ESP) {
			if (pestEspTracerBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.pestEspTracerEnabled = !workingCopy.pestEspTracerEnabled
				return true
			}

			if (pestEspColorBounds(menu).contains(mouseX, mouseY)) {
				openColorField = if (openColorField == ConfigField.PEST_ESP_COLOR) null else ConfigField.PEST_ESP_COLOR
				soundDropdownOpen = false
				draggingColorPicker = null
				return true
			}

			if (!colorPickerOpen) {
				return false
			}

			val target = when {
				colorSvBounds(menu).contains(mouseX, mouseY) -> ColorPickerDragTarget.SATURATION_BRIGHTNESS
				colorHueBounds(menu).contains(mouseX, mouseY) -> ColorPickerDragTarget.HUE
				else -> null
			}

			if (target != null) {
				draggingColorPicker = target
				updateColorFromPicker(mouseX, mouseY, target)
				return true
			}
		}

		if (section == ConfigSection.FLOOR_DROP_ESP && floorDropEspTracerBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.floorDropEspTracerEnabled = !workingCopy.floorDropEspTracerEnabled
			return true
		}

		if (section == ConfigSection.CORPSE_ESP) {
			if (corpseEspLapisBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.corpseEspLapisEnabled = !workingCopy.corpseEspLapisEnabled
				return true
			}

			if (corpseEspTungstenBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.corpseEspTungstenEnabled = !workingCopy.corpseEspTungstenEnabled
				return true
			}

			if (corpseEspUmberBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.corpseEspUmberEnabled = !workingCopy.corpseEspUmberEnabled
				return true
			}

			if (corpseEspVanguardBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.corpseEspVanguardEnabled = !workingCopy.corpseEspVanguardEnabled
				return true
			}
		}

		if (section == ConfigSection.MOB_MODEL && mobModelBabyBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.mobModelBaby = !workingCopy.mobModelBaby
			return true
		}

		if (section == ConfigSection.MOB_MODEL && mobModelShowArmorBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.mobModelShowArmor = !workingCopy.mobModelShowArmor
			return true
		}

		if (section == ConfigSection.MOB_MODEL && mobModelShowHeldItemsBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.mobModelShowHeldItems = !workingCopy.mobModelShowHeldItems
			return true
		}

		if (section == ConfigSection.MOB_MODEL && mobModelScaleBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			draggingSlider = SliderDragTarget.MOB_MODEL_SCALE
			updateSliderFromMouse(mouseX, SliderDragTarget.MOB_MODEL_SCALE)
			return true
		}

		if (section == ConfigSection.MOB_MODEL && mobModelEntityTypeBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			soundDropdownOpen = false
			mobModelVariantDropdownOpen = false
			mobModelDropdownOpen = !mobModelDropdownOpen
			mobModelScrollOffset = 0
			if (mobModelDropdownOpen) {
				mobModelEntityTypeField.text = ""
				setFocused(mobModelEntityTypeField)
				mobModelEntityTypeField.setFocused(true)
			} else {
				setFocused(null)
				mobModelEntityTypeField.setFocused(false)
			}
			layoutWidgets()
			return true
		}

		if (section == ConfigSection.MOB_MODEL && mobModelDropdownOpen && mobModelListBounds(menu).contains(mouseX, mouseY)) {
			val index = mobModelScrollOffset + ((mouseY - mobModelListBounds(menu).top) / SOUND_ROW_HEIGHT)
			val filtered = filteredMobModelOptions(mobModelEntityTypeField.text)
			if (index in filtered.indices) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.mobModelEntityType = filtered[index]
				if (MobModelVariantCatalog.validate(workingCopy.mobModelEntityType, workingCopy.mobModelVariant) != null) {
					workingCopy.mobModelVariant = ""
				}
				mobModelEntityTypeField.text = ""
				setFocused(null)
				mobModelEntityTypeField.setFocused(false)
				mobModelDropdownOpen = false
				layoutWidgets()
			}
			return true
		}

		if (section == ConfigSection.MOB_MODEL && mobModelVariantBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			val options = mobModelVariantOptions()
			if (options.isEmpty()) {
				statusMessage = Text.literal("This mob has no configurable variants.")
				return true
			}
			soundDropdownOpen = false
			mobModelDropdownOpen = false
			mobModelVariantDropdownOpen = !mobModelVariantDropdownOpen
			mobModelVariantScrollOffset = 0
			if (mobModelVariantDropdownOpen) {
				mobModelVariantField.text = ""
				setFocused(mobModelVariantField)
				mobModelVariantField.setFocused(true)
			} else {
				setFocused(null)
				mobModelVariantField.setFocused(false)
			}
			layoutWidgets()
			return true
		}

		if (section == ConfigSection.MOB_MODEL && mobModelVariantDropdownOpen && mobModelVariantListBounds(menu).contains(mouseX, mouseY)) {
			val index = mobModelVariantScrollOffset + ((mouseY - mobModelVariantListBounds(menu).top) / SOUND_ROW_HEIGHT)
			val filtered = filteredMobModelVariantOptions(mobModelVariantField.text)
			if (index in filtered.indices) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.mobModelVariant = if (filtered[index] == MobModelVariantCatalog.DEFAULT_OPTION) "" else filtered[index]
				mobModelVariantField.text = ""
				setFocused(null)
				mobModelVariantField.setFocused(false)
				mobModelVariantDropdownOpen = false
				layoutWidgets()
			}
			return true
		}

		if (section == ConfigSection.INVENTORY_PREVIEW && inventoryPreviewShowArmorBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.inventoryPreviewShowArmor = !workingCopy.inventoryPreviewShowArmor
			return true
		}

		if (section == ConfigSection.CROSSHAIR && crosshairShowInFirstPersonBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.customCrosshairShowInFirstPerson = !workingCopy.customCrosshairShowInFirstPerson
			return true
		}

		if (section == ConfigSection.CROSSHAIR && crosshairVisibleInF5Bounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.customCrosshairVisibleInF5 = !workingCopy.customCrosshairVisibleInF5
			return true
		}

		if (section == ConfigSection.CROSSHAIR && crosshairResetBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.customCrosshairPattern = CustomCrosshairFeature.resetPattern()
			return true
		}

		if (section == ConfigSection.CROSSHAIR && crosshairGridBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			val grid = crosshairGridEditorBounds(crosshairGridBounds(menu))
			val column = (mouseX - grid.left) / CROSSHAIR_GRID_CELL_SIZE
			val row = (mouseY - grid.top) / CROSSHAIR_GRID_CELL_SIZE
			if (row in 0 until CustomCrosshairFeature.GRID_SIZE && column in 0 until CustomCrosshairFeature.GRID_SIZE) {
				workingCopy.customCrosshairPattern = CustomCrosshairFeature.toggleCell(workingCopy.customCrosshairPattern, row, column)
			}
			return true
		}

		if (section == ConfigSection.M5) {
			if (m5LividFinderBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.m5LividFinderEnabled = !workingCopy.m5LividFinderEnabled
				return true
			}

			if (m5TracerBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.m5TracerEnabled = !workingCopy.m5TracerEnabled
				return true
			}

			if (m5IceSprayBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.m5IceSprayTimerEnabled = !workingCopy.m5IceSprayTimerEnabled
				return true
			}

			if (m5RagAxeBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.m5RagAxeAlertEnabled = !workingCopy.m5RagAxeAlertEnabled
				return true
			}
		}

		if (section == ConfigSection.DUNGEON_AUTOKICK) {
			readWorkingCopyFromFields(updateStatus = false)
			when {
				dungeonAutoKickStatsDisplayBounds(menu).contains(mouseX, mouseY) -> workingCopy.dungeonAutoKickStatsDisplayEnabled = !workingCopy.dungeonAutoKickStatsDisplayEnabled
				dungeonAutoKickKickLineBounds(menu).contains(mouseX, mouseY) -> workingCopy.dungeonAutoKickSendKickLineEnabled = !workingCopy.dungeonAutoKickSendKickLineEnabled
				dungeonAutoKickAutoKickBounds(menu).contains(mouseX, mouseY) -> workingCopy.dungeonAutoKickAutoKickEnabled = !workingCopy.dungeonAutoKickAutoKickEnabled
				dungeonAutoKickFloorBounds(menu).contains(mouseX, mouseY) -> {
					dungeonAutoKickFloorDropdownOpen = !dungeonAutoKickFloorDropdownOpen
					dungeonAutoKickFloorScrollOffset = selectedDungeonAutoKickFloorIndex().coerceAtLeast(0)
						.coerceAtMost((DUNGEON_AUTOKICK_FLOOR_OPTIONS.size - DUNGEON_AUTOKICK_FLOOR_VISIBLE_ROWS).coerceAtLeast(0))
					layoutWidgets()
				}
				dungeonAutoKickFloorDropdownOpen && dungeonAutoKickFloorListBounds(menu).contains(mouseX, mouseY) -> {
					val index = dungeonAutoKickFloorScrollOffset + ((mouseY - dungeonAutoKickFloorListBounds(menu).top) / SOUND_ROW_HEIGHT)
					val selected = DUNGEON_AUTOKICK_FLOOR_OPTIONS.getOrNull(index)
					if (selected != null) {
						workingCopy.dungeonAutoKickMasterMode = selected.startsWith("M")
						workingCopy.dungeonAutoKickFloor = selected.substring(1)
						dungeonAutoKickFloorDropdownOpen = false
						layoutWidgets()
					}
				}
				dungeonAutoKickApiOffBounds(menu).contains(mouseX, mouseY) -> workingCopy.dungeonAutoKickApiOffKickEnabled = !workingCopy.dungeonAutoKickApiOffKickEnabled
				dungeonAutoKickInformBounds(menu).contains(mouseX, mouseY) -> workingCopy.dungeonAutoKickInformKickedEnabled = !workingCopy.dungeonAutoKickInformKickedEnabled
				dungeonAutoKickCacheBounds(menu).contains(mouseX, mouseY) -> workingCopy.dungeonAutoKickCacheEnabled = !workingCopy.dungeonAutoKickCacheEnabled
				partyFinderGuiStatsBounds(menu).contains(mouseX, mouseY) -> workingCopy.partyFinderGuiStatsEnabled = !workingCopy.partyFinderGuiStatsEnabled
				partyFinderHighlightsBounds(menu).contains(mouseX, mouseY) -> workingCopy.partyFinderHighlightsEnabled = !workingCopy.partyFinderHighlightsEnabled
				partyFinderMemberCountBounds(menu).contains(mouseX, mouseY) -> workingCopy.partyFinderMemberCountEnabled = !workingCopy.partyFinderMemberCountEnabled
				partyFinderRightClickBounds(menu).contains(mouseX, mouseY) -> workingCopy.partyFinderRightClickEnabled = !workingCopy.partyFinderRightClickEnabled
				dungeonAutoKickClearCacheBounds(menu).contains(mouseX, mouseY) -> {
					DungeonAutoKickFeature.clearKickCache()
					statusMessage = Text.literal("Dungeon AutoKick cache cleared.")
				}
				else -> return false
			}
			return true
		}

		if (section == ConfigSection.PICKAXE_COOLDOWN) {
			if (pickaxeShowReadyBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.pickaxeAbilityCooldownShowReady = !workingCopy.pickaxeAbilityCooldownShowReady
				return true
			}

			if (pickaxeAlertDisclosureBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				pickaxeAlertExpanded = !pickaxeAlertExpanded
				if (!pickaxeAlertExpanded) {
					soundDropdownOpen = false
				}
				layoutWidgets()
				return true
			}

			if (!pickaxeAlertExpanded) {
				return false
			}

			if (pickaxeAlertEnabledBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.pickaxeAbilityCooldownAlertEnabled = !workingCopy.pickaxeAbilityCooldownAlertEnabled
				return true
			}

			if (pickaxeAlertSoundBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				mobModelDropdownOpen = false
				mobModelVariantDropdownOpen = false
				soundDropdownOpen = !soundDropdownOpen
				soundScrollOffset = 0
				layoutWidgets()
				return true
			}

			if (soundDropdownOpen && soundListBounds(menu).contains(mouseX, mouseY)) {
				val index = soundScrollOffset + ((mouseY - soundListBounds(menu).top) / SOUND_ROW_HEIGHT)
				val filtered = SoundCatalog.filtered(activeSoundSearchField().text)
				if (index in filtered.indices) {
					readWorkingCopyFromFields(updateStatus = false)
					workingCopy.pickaxeAbilityCooldownAlertSoundId = filtered[index].id
					soundDropdownOpen = false
					layoutWidgets()
				}
				return true
			}

			if (pickaxeAlertVolumeBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.PICKAXE_ALERT_VOLUME
				updateSliderFromMouse(mouseX, SliderDragTarget.PICKAXE_ALERT_VOLUME)
				return true
			}

			if (pickaxeAlertPitchBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.PICKAXE_ALERT_PITCH
				updateSliderFromMouse(mouseX, SliderDragTarget.PICKAXE_ALERT_PITCH)
				return true
			}

			if (pickaxeAlertPreviewBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				PickaxeAbilityCooldownFeature.playAlertPreview(workingCopy)
				return true
			}
		}

		if (section == ConfigSection.CHIMERA_DROP) {
			if (chimeraDropSoundBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				mobModelDropdownOpen = false
				mobModelVariantDropdownOpen = false
				soundDropdownOpen = !soundDropdownOpen
				soundScrollOffset = 0
				layoutWidgets()
				return true
			}

			if (soundDropdownOpen && soundListBounds(menu).contains(mouseX, mouseY)) {
				val index = soundScrollOffset + ((mouseY - soundListBounds(menu).top) / SOUND_ROW_HEIGHT)
				val filtered = SoundCatalog.filtered(activeSoundSearchField().text)
				if (index in filtered.indices) {
					readWorkingCopyFromFields(updateStatus = false)
					workingCopy.chimeraBookDropEffectsSoundId = filtered[index].id
					soundDropdownOpen = false
					layoutWidgets()
				}
				return true
			}

			if (chimeraDropVolumeBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.CHIMERA_DROP_VOLUME
				updateSliderFromMouse(mouseX, SliderDragTarget.CHIMERA_DROP_VOLUME)
				return true
			}

			if (chimeraDropPitchBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.CHIMERA_DROP_PITCH
				updateSliderFromMouse(mouseX, SliderDragTarget.CHIMERA_DROP_PITCH)
				return true
			}

			if (chimeraDropTestBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				statusMessage = if (ChimeraBookDropEffectsFeature.runTest(workingCopy)) {
					Text.literal("Triggered Chimera book effect test.")
				} else {
					Text.literal("Module is disabled.")
				}
				return true
			}
		}

		if (section == ConfigSection.FIRE_FREEZE) {
			when {
				fireFreezeMobTimerBounds(menu).contains(mouseX, mouseY) -> workingCopy.fireFreezeMobTimerEnabled = !workingCopy.fireFreezeMobTimerEnabled
				fireFreezeFreezeTimerBounds(menu).contains(mouseX, mouseY) -> workingCopy.fireFreezeFreezeTimerEnabled = !workingCopy.fireFreezeFreezeTimerEnabled
				fireFreezeStrongMobsOnlyBounds(menu).contains(mouseX, mouseY) -> workingCopy.fireFreezeStrongMobsOnly = !workingCopy.fireFreezeStrongMobsOnly
				fireFreezeBoxBounds(menu).contains(mouseX, mouseY) -> workingCopy.fireFreezeBoxFrozenMobsEnabled = !workingCopy.fireFreezeBoxFrozenMobsEnabled
				fireFreezeCustomCircleBounds(menu).contains(mouseX, mouseY) -> workingCopy.fireFreezeCustomCircleEnabled = !workingCopy.fireFreezeCustomCircleEnabled
				fireFreezeCircleColorBounds(menu).contains(mouseX, mouseY) -> {
					openColorField = if (openColorField == ConfigField.FIRE_FREEZE_CIRCLE_COLOR) null else ConfigField.FIRE_FREEZE_CIRCLE_COLOR
					soundDropdownOpen = false
				}
				colorPickerOpen && colorSvBounds(menu).contains(mouseX, mouseY) -> {
					draggingColorPicker = ColorPickerDragTarget.SATURATION_BRIGHTNESS
					updateColorFromPicker(mouseX, mouseY, ColorPickerDragTarget.SATURATION_BRIGHTNESS)
				}
				colorPickerOpen && colorHueBounds(menu).contains(mouseX, mouseY) -> {
					draggingColorPicker = ColorPickerDragTarget.HUE
					updateColorFromPicker(mouseX, mouseY, ColorPickerDragTarget.HUE)
				}
				fireFreezeLineWidthBounds(menu).contains(mouseX, mouseY) -> {
					draggingSlider = SliderDragTarget.FIRE_FREEZE_LINE_WIDTH
					updateSliderFromMouse(mouseX, SliderDragTarget.FIRE_FREEZE_LINE_WIDTH)
				}
				fireFreezeRefreezeAlertBounds(menu).contains(mouseX, mouseY) -> workingCopy.fireFreezeRefreezeAlertEnabled = !workingCopy.fireFreezeRefreezeAlertEnabled
				fireFreezeAlertSoundBounds(menu).contains(mouseX, mouseY) -> {
					openColorField = null
					soundDropdownOpen = !soundDropdownOpen
					soundScrollOffset = 0
					layoutWidgets()
				}
				soundDropdownOpen && soundListBounds(menu).contains(mouseX, mouseY) -> {
					val index = soundScrollOffset + ((mouseY - soundListBounds(menu).top) / SOUND_ROW_HEIGHT)
					val filtered = SoundCatalog.filtered(activeSoundSearchField().text)
					if (index in filtered.indices) {
						workingCopy.fireFreezeRefreezeAlertSoundId = filtered[index].id
						soundDropdownOpen = false
						layoutWidgets()
					}
				}
				fireFreezeAlertVolumeBounds(menu).contains(mouseX, mouseY) -> {
					draggingSlider = SliderDragTarget.FIRE_FREEZE_ALERT_VOLUME
					updateSliderFromMouse(mouseX, SliderDragTarget.FIRE_FREEZE_ALERT_VOLUME)
				}
				fireFreezeAlertPitchBounds(menu).contains(mouseX, mouseY) -> {
					draggingSlider = SliderDragTarget.FIRE_FREEZE_ALERT_PITCH
					updateSliderFromMouse(mouseX, SliderDragTarget.FIRE_FREEZE_ALERT_PITCH)
				}
				fireFreezeAlertPreviewBounds(menu).contains(mouseX, mouseY) -> FireFreezeFeature.playAlertPreview(workingCopy)
				else -> return false
			}
			readWorkingCopyFromFields(updateStatus = false)
			return true
		}

		if (section == ConfigSection.EXPERIMENTS && autoExperimentsAutoCloseBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.autoExperimentsAutoClose = !workingCopy.autoExperimentsAutoClose
			return true
		}

		if (section == ConfigSection.EXPERIMENTS && autoExperimentsAutoPairsBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.autoExperimentsAutoPairs = !workingCopy.autoExperimentsAutoPairs
			return true
		}

		if (section == ConfigSection.EXPERIMENTS && autoExperimentsGetMaxXpBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.autoExperimentsGetMaxXp = !workingCopy.autoExperimentsGetMaxXp
			return true
		}

		if (section == ConfigSection.AUTO_CROESUS && autoCroesusEnabledBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.autoCroesusModuleEnabled = !workingCopy.autoCroesusModuleEnabled
			return true
		}

		if (section == ConfigSection.AUTO_CROESUS && autoCroesusNoClickBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingAutoCroesusConfig.noClick = !workingAutoCroesusConfig.noClick
			return true
		}

		if (section == ConfigSection.AUTO_CROESUS && autoCroesusUseKismetsBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingAutoCroesusConfig.useKismets = !workingAutoCroesusConfig.useKismets
			return true
		}

		if (section == ConfigSection.AUTO_CROESUS && autoCroesusUseChestKeysBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingAutoCroesusConfig.useChestKeys = !workingAutoCroesusConfig.useChestKeys
			return true
		}

		if (section == ConfigSection.DOOR && settingRowBounds(menu, 0, SETTING_HEIGHT).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.dungeonDoorEnabled = !workingCopy.dungeonDoorEnabled
			return true
		}

		if (section == ConfigSection.DOOR && settingRowBounds(menu, 1, SETTING_HEIGHT).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.dungeonDoorDebugEnabled = !workingCopy.dungeonDoorDebugEnabled
			return true
		}

		if (section == ConfigSection.DOOR && settingRowBounds(menu, 2, SETTING_HEIGHT).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.dungeonDoorMode = (workingCopy.dungeonDoorMode + 1) % MortDoorBarrierFeature.modeCount
			return true
		}

		if (section == ConfigSection.RED_VIGNETTE && settingRowBounds(menu, 0, SETTING_HEIGHT).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.dungeonRedVignetteEnabled = !workingCopy.dungeonRedVignetteEnabled
			return true
		}

		if (section == ConfigSection.STATUS && settingRowBounds(menu, 0, SETTING_HEIGHT).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.checkForUpdatesEnabled = !workingCopy.checkForUpdatesEnabled
			return true
		}

		if (section == ConfigSection.STATUS && settingRowBounds(menu, 1, SETTING_HEIGHT).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.autoUpdateEnabled = !workingCopy.autoUpdateEnabled
			return true
		}

		if (section == ConfigSection.STATUS && updateCheckNowBounds(menu).contains(mouseX, mouseY)) {
			checkForUpdatesNow()
			return true
		}

		if (section == ConfigSection.STATUS && hudEditorBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			mod.openHudEditorScreen(this)
			return true
		}

		return false
	}

	private fun textFieldBounds(section: ConfigSection, field: ConfigField, menu: Bounds): Bounds? {
		return when (section) {
			ConfigSection.SETUP -> when (field) {
				else -> null
			}

			ConfigSection.IRC_BRIDGE -> when (field) {
				ConfigField.IRC_SERVER_URL -> settingRowBounds(menu, 0, TEXT_INPUT_SETTING_HEIGHT)
				ConfigField.AUTH_TOKEN -> settingRowBounds(menu, 1, TEXT_INPUT_SETTING_HEIGHT)
				ConfigField.POLL_INTERVAL -> settingRowBounds(menu, 2, TEXT_INPUT_SETTING_HEIGHT)
				ConfigField.IRC_FORMAT -> settingRowBounds(menu, 3, TEXT_INPUT_SETTING_HEIGHT)
				else -> null
			}

			ConfigSection.SLAYER -> when (field) {
				ConfigField.SLAYER_ANNOUNCER_TEXT -> if (slayerMiscExpanded) slayerAnnouncerTextBounds(menu) else null
				else -> null
			}

			ConfigSection.MOB_MODEL -> when (field) {
				ConfigField.MOB_MODEL_ENTITY_TYPE -> null
				ConfigField.MOB_MODEL_VARIANT -> null
				else -> null
			}

			ConfigSection.EXPERIMENTS -> when (field) {
				ConfigField.AUTO_EXPERIMENTS_CLICK_DELAY -> autoExperimentsClickDelayBounds(menu)
				ConfigField.AUTO_EXPERIMENTS_DELAY_VARIETY -> autoExperimentsDelayVarietyBounds(menu)
				ConfigField.AUTO_EXPERIMENTS_SERUM_COUNT -> autoExperimentsSerumCountBounds(menu)
				else -> null
			}

			ConfigSection.AUTO_CROESUS -> when (field) {
				ConfigField.AUTO_CROESUS_KISMET_FLOORS -> autoCroesusKismetFloorsBounds(menu)
				ConfigField.AUTO_CROESUS_KISMET_MIN_PROFIT -> autoCroesusKismetProfitBounds(menu)
				ConfigField.AUTO_CROESUS_CHEST_KEY_MIN_PROFIT -> autoCroesusChestKeyProfitBounds(menu)
				ConfigField.AUTO_CROESUS_CLICK_DELAY -> autoCroesusClickDelayBounds(menu)
				else -> null
			}

			ConfigSection.DUNGEON_AUTOKICK -> when (field) {
				ConfigField.DUNGEON_AUTOKICK_MAX_PB -> dungeonAutoKickMaxPbBounds(menu)
				ConfigField.DUNGEON_AUTOKICK_MIN_SECRETS -> dungeonAutoKickMinSecretsBounds(menu)
				ConfigField.DUNGEON_AUTOKICK_MIN_MP -> dungeonAutoKickMinMpBounds(menu)
				else -> null
			}

			ConfigSection.PICKAXE_COOLDOWN -> when (field) {
				ConfigField.PICKAXE_ALERT_TEXT -> if (pickaxeAlertExpanded) pickaxeAlertTextBounds(menu) else null
				else -> null
			}

			ConfigSection.MINESHAFT_AUTOWARP -> when (field) {
				ConfigField.MINESHAFT_AUTOWARP_RULE -> mineshaftAutoWarpRuleBounds(menu)
				ConfigField.MINESHAFT_AUTOWARP_DELAY -> mineshaftAutoWarpDelayBounds(menu)
				ConfigField.MINESHAFT_AUTOWARP_WINDOW -> mineshaftAutoWarpWindowBounds(menu)
				else -> null
			}

			else -> null
		}
	}

	private fun parseNonNegativeLong(raw: String): Long? {
		val normalized = raw.trim().replace(",", "").replace("_", "")
		if (normalized.isBlank()) {
			return null
		}
		return normalized.toLongOrNull()?.takeIf { it >= 0L }
	}

	private fun parseDurationSeconds(raw: String): Int? {
		val normalized = raw.trim()
			.replace(",", "")
			.replace("_", "")
		if (normalized.isBlank()) {
			return null
		}
		if (!normalized.contains(':')) {
			return normalized.toIntOrNull()?.takeIf { it >= 0 }
		}

		val parts = normalized.split(":")
		if (parts.size != 2) {
			return null
		}
		val minutes = parts[0].toIntOrNull() ?: return null
		val seconds = parts[1].toIntOrNull() ?: return null
		if (minutes < 0 || seconds !in 0..59) {
			return null
		}
		return minutes * 60 + seconds
	}

	private fun parseAutoCroesusFloors(raw: String): ArrayList<String>? {
		val floors = linkedSetOf<String>()
		val trimmed = raw.trim()
		if (trimmed.isBlank()) {
			return arrayListOf()
		}
		trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
			val normalized = token.uppercase(Locale.ROOT)
			if (!AUTO_CROESUS_FLOOR_PATTERN.matches(normalized)) {
				return null
			}
			floors += normalized
		}
		return ArrayList(floors)
	}

	private fun persistAutoCroesusConfig() {
		AcDataStore.config = copyOf(workingAutoCroesusConfig)
		AcDataStore.saveConfig()
	}

	private fun normalizeMobModelEntityType(value: String): String? {
		return MobModelCatalog.normalize(value)
	}

	private fun mobModelStatusLine(): String {
		val catalogCount = MobModelCatalog.count()
		if (catalogCount == 0) {
			return "Mob catalog is empty"
		}

		if (!workingCopy.mobModelModuleEnabled) {
			return "Disabled ($catalogCount mobs available)"
		}

		val entityId = normalizeMobModelEntityType(workingCopy.mobModelEntityType) ?: return "Select a mob"
		val variant = MobModelVariantCatalog.normalize(workingCopy.mobModelVariant)
		val variantError = MobModelVariantCatalog.validate(entityId, variant)
		if (variantError != null) {
			return variantError
		}

		val scaleLabel = String.format(Locale.ROOT, "%.2fx", workingCopy.mobModelScale)
		return if (MobModelCatalog.resolve(entityId) != null) {
			val variantLabel = if (variant.isBlank()) "default" else variant
			"Will render as ${MobModelCatalog.displayName(entityId)} [$variantLabel, $scaleLabel] ($catalogCount mobs)"
		} else {
			"Invalid mob id: $entityId ($catalogCount mobs)"
		}
	}

	private fun mobModelVariantOptions(): List<String> {
		val entityId = normalizeMobModelEntityType(workingCopy.mobModelEntityType) ?: return emptyList()
		return MobModelVariantCatalog.options(entityId)
	}

	private fun normalizedHexColor(value: String): String? {
		val candidate = value.trim().removePrefix("#")
		if (!HEX_COLOR_PATTERN.matches(candidate)) {
			return null
		}
		return "#${candidate.uppercase(Locale.ROOT)}"
	}

	private fun updateColorFromPicker(mouseX: Int, mouseY: Int, target: ColorPickerDragTarget) {
		val menu = settingsBounds()
		val widget = colorFieldWidget(openColorField ?: return)
		val currentColor = normalizedHexColor(widget.text)?.removePrefix("#")?.toInt(16) ?: DEFAULT_GLOW_COLOR
		val red = currentColor shr 16 and 0xFF
		val green = currentColor shr 8 and 0xFF
		val blue = currentColor and 0xFF
		val hsb = Color.RGBtoHSB(red, green, blue, null)

		var hue = hsb[0]
		var saturation = hsb[1]
		var brightness = hsb[2]

		when (target) {
			ColorPickerDragTarget.SATURATION_BRIGHTNESS -> {
				val bounds = colorSvBounds(menu)
				saturation = ((mouseX - bounds.left).toFloat() / bounds.width().coerceAtLeast(1)).coerceIn(0f, 1f)
				brightness = (1f - ((mouseY - bounds.top).toFloat() / bounds.height().coerceAtLeast(1))).coerceIn(0f, 1f)
			}
			ColorPickerDragTarget.HUE -> {
				val bounds = colorHueBounds(menu)
				hue = ((mouseY - bounds.top).toFloat() / bounds.height().coerceAtLeast(1)).coerceIn(0f, 1f)
			}
		}

		val rgb = Color.HSBtoRGB(hue, saturation, brightness) and 0xFFFFFF
		widget.text = String.format(Locale.ROOT, "#%06X", rgb)
		readWorkingCopyFromFields(updateStatus = false)
	}

	private fun updateSliderFromMouse(mouseX: Int, target: SliderDragTarget) {
		val menu = settingsBounds()
		val bounds = when (target) {
			SliderDragTarget.LINE_MODE -> tracerLineBounds(menu)
			SliderDragTarget.LINE_WIDTH -> tracerLineWidthBounds(menu)
			SliderDragTarget.MOB_MODEL_SCALE -> mobModelScaleBounds(menu)
			SliderDragTarget.ALERT_VOLUME -> lostFightVolumeBounds(menu)
			SliderDragTarget.ALERT_PITCH -> lostFightPitchBounds(menu)
			SliderDragTarget.PICKAXE_ALERT_VOLUME -> pickaxeAlertVolumeBounds(menu)
			SliderDragTarget.PICKAXE_ALERT_PITCH -> pickaxeAlertPitchBounds(menu)
			SliderDragTarget.CHIMERA_DROP_VOLUME -> chimeraDropVolumeBounds(menu)
			SliderDragTarget.CHIMERA_DROP_PITCH -> chimeraDropPitchBounds(menu)
			SliderDragTarget.FIRE_FREEZE_ALERT_VOLUME -> fireFreezeAlertVolumeBounds(menu)
			SliderDragTarget.FIRE_FREEZE_ALERT_PITCH -> fireFreezeAlertPitchBounds(menu)
			SliderDragTarget.FIRE_FREEZE_LINE_WIDTH -> fireFreezeLineWidthBounds(menu)
			SliderDragTarget.SLAYER_ANNOUNCER_VOLUME -> slayerAnnouncerVolumeBounds(menu)
			SliderDragTarget.SLAYER_ANNOUNCER_PITCH -> slayerAnnouncerPitchBounds(menu)
		}
		val min = when (target) {
			SliderDragTarget.LINE_MODE -> 0.0f
			SliderDragTarget.LINE_WIDTH -> 1.0f
			SliderDragTarget.MOB_MODEL_SCALE -> 0.25f
			SliderDragTarget.ALERT_VOLUME -> 0.0f
			SliderDragTarget.ALERT_PITCH -> 0.1f
			SliderDragTarget.PICKAXE_ALERT_VOLUME -> 0.0f
			SliderDragTarget.PICKAXE_ALERT_PITCH -> 0.1f
			SliderDragTarget.CHIMERA_DROP_VOLUME -> 0.0f
			SliderDragTarget.CHIMERA_DROP_PITCH -> 0.1f
			SliderDragTarget.FIRE_FREEZE_ALERT_VOLUME -> 0.0f
			SliderDragTarget.FIRE_FREEZE_ALERT_PITCH -> 0.1f
			SliderDragTarget.FIRE_FREEZE_LINE_WIDTH -> 1.0f
			SliderDragTarget.SLAYER_ANNOUNCER_VOLUME -> 0.0f
			SliderDragTarget.SLAYER_ANNOUNCER_PITCH -> 0.1f
		}
		val max = when (target) {
			SliderDragTarget.LINE_MODE -> 3.0f
			SliderDragTarget.LINE_WIDTH -> 8.0f
			SliderDragTarget.MOB_MODEL_SCALE -> 4.0f
			SliderDragTarget.ALERT_VOLUME -> 2.0f
			SliderDragTarget.ALERT_PITCH -> 2.0f
			SliderDragTarget.PICKAXE_ALERT_VOLUME -> 2.0f
			SliderDragTarget.PICKAXE_ALERT_PITCH -> 2.0f
			SliderDragTarget.CHIMERA_DROP_VOLUME -> 2.0f
			SliderDragTarget.CHIMERA_DROP_PITCH -> 2.0f
			SliderDragTarget.FIRE_FREEZE_ALERT_VOLUME -> 2.0f
			SliderDragTarget.FIRE_FREEZE_ALERT_PITCH -> 2.0f
			SliderDragTarget.FIRE_FREEZE_LINE_WIDTH -> 8.0f
			SliderDragTarget.SLAYER_ANNOUNCER_VOLUME -> 2.0f
			SliderDragTarget.SLAYER_ANNOUNCER_PITCH -> 2.0f
		}
		val barLeft = bounds.left + 8
		val barRight = bounds.right - 8
		val progress = ((mouseX - barLeft).toFloat() / (barRight - barLeft).coerceAtLeast(1)).coerceIn(0.0f, 1.0f)
		val rawValue = min + ((max - min) * progress)
		val value = when (target) {
			SliderDragTarget.LINE_MODE -> roundToStep(rawValue, 1.0f)
			SliderDragTarget.LINE_WIDTH -> roundToStep(rawValue, 0.1f)
			SliderDragTarget.MOB_MODEL_SCALE -> roundToStep(rawValue, 0.05f)
			SliderDragTarget.ALERT_VOLUME -> roundToStep(rawValue, 0.05f)
			SliderDragTarget.ALERT_PITCH -> roundToStep(rawValue, 0.05f)
			SliderDragTarget.PICKAXE_ALERT_VOLUME -> roundToStep(rawValue, 0.05f)
			SliderDragTarget.PICKAXE_ALERT_PITCH -> roundToStep(rawValue, 0.05f)
			SliderDragTarget.CHIMERA_DROP_VOLUME -> roundToStep(rawValue, 0.05f)
			SliderDragTarget.CHIMERA_DROP_PITCH -> roundToStep(rawValue, 0.05f)
			SliderDragTarget.FIRE_FREEZE_ALERT_VOLUME -> roundToStep(rawValue, 0.05f)
			SliderDragTarget.FIRE_FREEZE_ALERT_PITCH -> roundToStep(rawValue, 0.05f)
			SliderDragTarget.FIRE_FREEZE_LINE_WIDTH -> roundToStep(rawValue, 0.1f)
			SliderDragTarget.SLAYER_ANNOUNCER_VOLUME -> roundToStep(rawValue, 0.05f)
			SliderDragTarget.SLAYER_ANNOUNCER_PITCH -> roundToStep(rawValue, 0.05f)
		}.coerceIn(min, max)

		when (target) {
			SliderDragTarget.LINE_MODE -> {
				workingCopy.shulkerTracerLineMode = value.toInt()
				workingCopy.shulkerTracerLineEnabled = value.toInt() > 0
			}
			SliderDragTarget.LINE_WIDTH -> workingCopy.shulkerTracerLineWidth = value
			SliderDragTarget.MOB_MODEL_SCALE -> workingCopy.mobModelScale = value
			SliderDragTarget.ALERT_VOLUME -> workingCopy.hideonleafLostFightAlertSoundVolume = value
			SliderDragTarget.ALERT_PITCH -> workingCopy.hideonleafLostFightAlertSoundPitch = value
			SliderDragTarget.PICKAXE_ALERT_VOLUME -> workingCopy.pickaxeAbilityCooldownAlertSoundVolume = value
			SliderDragTarget.PICKAXE_ALERT_PITCH -> workingCopy.pickaxeAbilityCooldownAlertSoundPitch = value
			SliderDragTarget.CHIMERA_DROP_VOLUME -> workingCopy.chimeraBookDropEffectsSoundVolume = value
			SliderDragTarget.CHIMERA_DROP_PITCH -> workingCopy.chimeraBookDropEffectsSoundPitch = value
			SliderDragTarget.FIRE_FREEZE_ALERT_VOLUME -> workingCopy.fireFreezeRefreezeAlertSoundVolume = value
			SliderDragTarget.FIRE_FREEZE_ALERT_PITCH -> workingCopy.fireFreezeRefreezeAlertSoundPitch = value
			SliderDragTarget.FIRE_FREEZE_LINE_WIDTH -> workingCopy.fireFreezeCircleLineWidth = value
			SliderDragTarget.SLAYER_ANNOUNCER_VOLUME -> workingCopy.slayerSpawnAnnouncerSoundVolume = value
			SliderDragTarget.SLAYER_ANNOUNCER_PITCH -> workingCopy.slayerSpawnAnnouncerSoundPitch = value
		}
	}

	private fun roundToStep(value: Float, step: Float): Float {
		return (kotlin.math.round(value / step) * step)
	}

	private fun colorSvBounds(menu: Bounds): Bounds {
		val top = colorPickerTop(menu)
		return Bounds(menu.left + 48, top + 20, menu.right - 18, top + 92)
	}

	private fun colorHueBounds(menu: Bounds): Bounds {
		val top = colorPickerTop(menu)
		return Bounds(menu.left + 18, top + 20, menu.left + 36, top + 92)
	}

	private fun colorTitleY(menu: Bounds): Int {
		return colorPickerTop(menu) + 4
	}

	private fun colorHexY(menu: Bounds): Int {
		return colorPickerTop(menu) + 100
	}

	private fun shulkerGlowColorBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 65, menu.right - 10, menu.top + 85)
	}

	private fun purpleTerracottaColorBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 60)
	}

	private fun purpleTerracottaBlockIdBounds(menu: Bounds): Bounds {
		val top = purpleTerracottaColorBounds(menu).bottom + SETTING_GAP + colorPickerSpaceAfter(ConfigField.PURPLE_TERRACOTTA_HIGHLIGHT_COLOR)
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun pestEspColorBounds(menu: Bounds): Bounds {
		val top = pestEspTracerBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun floorDropEspTracerBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 40 + SETTING_HEIGHT)
	}

	private fun floorDropEspDetectionBounds(menu: Bounds): Bounds {
		val top = floorDropEspTracerBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun pestEspTracerBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 40 + SETTING_HEIGHT)
	}

	private fun corpseEspLapisBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 40 + SETTING_HEIGHT)
	}

	private fun corpseEspLapisColorBounds(menu: Bounds): Bounds {
		val top = corpseEspLapisBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun corpseEspTungstenBounds(menu: Bounds): Bounds {
		val top = corpseEspLapisColorBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun corpseEspTungstenColorBounds(menu: Bounds): Bounds {
		val top = corpseEspTungstenBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun corpseEspUmberBounds(menu: Bounds): Bounds {
		val top = corpseEspTungstenColorBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun corpseEspUmberColorBounds(menu: Bounds): Bounds {
		val top = corpseEspUmberBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun corpseEspVanguardBounds(menu: Bounds): Bounds {
		val top = corpseEspUmberColorBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun corpseEspVanguardColorBounds(menu: Bounds): Bounds {
		val top = corpseEspVanguardBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun projectileGlowColorBounds(menu: Bounds): Bounds {
		val top = shulkerGlowColorBounds(menu).bottom + SETTING_GAP + colorPickerSpaceAfter(ConfigField.SHULKER_GLOW_COLOR)
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun tracerLineColorBounds(menu: Bounds): Bounds {
		val top = projectileGlowColorBounds(menu).bottom + SETTING_GAP + colorPickerSpaceAfter(ConfigField.SHULKER_PROJECTILE_GLOW_COLOR)
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun tracerLineBounds(menu: Bounds): Bounds {
		val top = tracerLineColorBounds(menu).bottom + SETTING_GAP + colorPickerSpaceAfter(ConfigField.SHULKER_TRACER_LINE_COLOR)
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun colorPickerTop(menu: Bounds): Int {
		val field = openColorField ?: return menu.top + 90
		return when (field) {
			ConfigField.PURPLE_TERRACOTTA_HIGHLIGHT_COLOR -> purpleTerracottaColorBounds(menu).bottom + SETTING_GAP
			ConfigField.PEST_ESP_COLOR -> pestEspColorBounds(menu).bottom + SETTING_GAP
			ConfigField.FIRE_FREEZE_CIRCLE_COLOR -> fireFreezeCircleColorBounds(menu).bottom + SETTING_GAP
			ConfigField.SHULKER_GLOW_COLOR -> shulkerGlowColorBounds(menu).bottom + SETTING_GAP
			ConfigField.SHULKER_PROJECTILE_GLOW_COLOR -> projectileGlowColorBounds(menu).bottom + SETTING_GAP
			ConfigField.SHULKER_TRACER_LINE_COLOR -> tracerLineColorBounds(menu).bottom + SETTING_GAP
			else -> menu.top + 90
		}
	}

	private fun colorPickerSpaceAfter(field: ConfigField): Int {
		return if (openColorField == field) COLOR_PICKER_BLOCK_HEIGHT else 0
	}

	private fun tracerLineWidthBounds(menu: Bounds): Bounds {
		val top = tracerLineBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun lostFightAlertBounds(menu: Bounds): Bounds {
		val top = tracerLineWidthBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun lostFightSoundBounds(menu: Bounds): Bounds {
		val top = shareDataBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun shareDataBounds(menu: Bounds): Bounds {
		val top = lostFightAlertBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun soundSearchBounds(menu: Bounds): Bounds {
		val top = activeSoundAnchorBounds(menu).bottom + 4
		return Bounds(menu.left + 18, top, menu.right - 18, top + 18)
	}

	private fun soundListBounds(menu: Bounds): Bounds {
		val top = soundSearchBounds(menu).bottom + 4
		return Bounds(menu.left + 18, top, menu.right - 18, top + (SOUND_VISIBLE_ROWS * SOUND_ROW_HEIGHT))
	}

	private fun lostFightVolumeBounds(menu: Bounds): Bounds {
		val top = if (soundDropdownOpen) soundListBounds(menu).bottom + SETTING_GAP else lostFightSoundBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun lostFightPitchBounds(menu: Bounds): Bounds {
		val top = lostFightVolumeBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun playLostFightSoundBounds(menu: Bounds): Bounds {
		val top = lostFightPitchBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun resetHideonleafTrackerBounds(menu: Bounds): Bounds {
		val top = playLostFightSoundBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun pickaxeShowReadyBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 40 + SETTING_HEIGHT)
	}

	private fun pickaxeAlertDisclosureBounds(menu: Bounds): Bounds {
		val top = pickaxeShowReadyBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun pickaxeAlertEnabledBounds(menu: Bounds): Bounds {
		val top = pickaxeAlertDisclosureBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun pickaxeAlertTextBounds(menu: Bounds): Bounds {
		val top = pickaxeAlertEnabledBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun pickaxeAlertSoundBounds(menu: Bounds): Bounds {
		val top = pickaxeAlertTextBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun pickaxeAlertVolumeBounds(menu: Bounds): Bounds {
		val top = if (soundDropdownOpen) soundListBounds(menu).bottom + SETTING_GAP else pickaxeAlertSoundBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun pickaxeAlertPitchBounds(menu: Bounds): Bounds {
		val top = pickaxeAlertVolumeBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun pickaxeAlertPreviewBounds(menu: Bounds): Bounds {
		val top = pickaxeAlertPitchBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun pickaxeCurrentStateBounds(menu: Bounds): Bounds {
		val top = if (pickaxeAlertExpanded) pickaxeAlertPreviewBounds(menu).bottom + SETTING_GAP else pickaxeAlertDisclosureBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun chimeraDropSoundBounds(menu: Bounds): Bounds {
		val top = settingRowBounds(menu, 1, TEXT_INPUT_SETTING_HEIGHT).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun chimeraDropVolumeBounds(menu: Bounds): Bounds {
		val top = if (soundDropdownOpen) soundListBounds(menu).bottom + SETTING_GAP else chimeraDropSoundBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun chimeraDropPitchBounds(menu: Bounds): Bounds {
		val top = chimeraDropVolumeBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun chimeraDropTestBounds(menu: Bounds): Bounds {
		val top = chimeraDropPitchBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun fireFreezeMobTimerBounds(menu: Bounds): Bounds = settingRowBounds(menu, 0, SETTING_HEIGHT)

	private fun fireFreezeFreezeTimerBounds(menu: Bounds): Bounds = settingRowBounds(menu, 1, SETTING_HEIGHT)

	private fun fireFreezeStrongMobsOnlyBounds(menu: Bounds): Bounds = settingRowBounds(menu, 2, SETTING_HEIGHT)

	private fun fireFreezeBoxBounds(menu: Bounds): Bounds = settingRowBounds(menu, 3, SETTING_HEIGHT)

	private fun fireFreezeCustomCircleBounds(menu: Bounds): Bounds = settingRowBounds(menu, 4, SETTING_HEIGHT)

	private fun fireFreezeCircleColorBounds(menu: Bounds): Bounds = settingRowBounds(menu, 5, SETTING_HEIGHT)

	private fun fireFreezeLineWidthBounds(menu: Bounds): Bounds {
		val top = fireFreezeCircleColorBounds(menu).bottom + SETTING_GAP + colorPickerSpaceAfter(ConfigField.FIRE_FREEZE_CIRCLE_COLOR)
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun fireFreezeRefreezeAlertBounds(menu: Bounds): Bounds {
		val top = fireFreezeLineWidthBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun fireFreezeAlertSoundBounds(menu: Bounds): Bounds {
		val top = fireFreezeRefreezeAlertBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun fireFreezeAlertVolumeBounds(menu: Bounds): Bounds {
		val top = if (soundDropdownOpen) soundListBounds(menu).bottom + SETTING_GAP else fireFreezeAlertSoundBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun fireFreezeAlertPitchBounds(menu: Bounds): Bounds {
		val top = fireFreezeAlertVolumeBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun fireFreezeAlertPreviewBounds(menu: Bounds): Bounds {
		val top = fireFreezeAlertPitchBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun mineshaftAutoWarpRuleBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 40 + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun mineshaftAutoWarpDelayBounds(menu: Bounds): Bounds {
		val top = mineshaftAutoWarpRuleBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun mineshaftAutoWarpWindowBounds(menu: Bounds): Bounds {
		val top = mineshaftAutoWarpDelayBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun mineshaftAutoWarpStatusBounds(menu: Bounds): Bounds {
		val top = mineshaftAutoWarpWindowBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun activeSoundAnchorBounds(menu: Bounds): Bounds {
		return when (openedSection) {
			ConfigSection.PICKAXE_COOLDOWN -> pickaxeAlertSoundBounds(menu)
			ConfigSection.CHIMERA_DROP -> chimeraDropSoundBounds(menu)
			ConfigSection.FIRE_FREEZE -> fireFreezeAlertSoundBounds(menu)
			ConfigSection.SLAYER -> slayerAnnouncerSoundBounds(menu)
			else -> lostFightSoundBounds(menu)
		}
	}

	private fun mobModelSearchBounds(menu: Bounds): Bounds {
		val top = mobModelEntityTypeBounds(menu).bottom + 4
		return Bounds(menu.left + 18, top, menu.right - 18, top + 18)
	}

	private fun mobModelListBounds(menu: Bounds): Bounds {
		val top = mobModelSearchBounds(menu).bottom + 4
		return Bounds(menu.left + 18, top, menu.right - 18, top + (MOB_MODEL_VISIBLE_ROWS * SOUND_ROW_HEIGHT))
	}

	private fun mobModelVariantSearchBounds(menu: Bounds): Bounds {
		val top = mobModelVariantBounds(menu).bottom + 4
		return Bounds(menu.left + 18, top, menu.right - 18, top + 18)
	}

	private fun mobModelVariantListBounds(menu: Bounds): Bounds {
		val top = mobModelVariantSearchBounds(menu).bottom + 4
		return Bounds(menu.left + 18, top, menu.right - 18, top + (MOB_MODEL_VISIBLE_ROWS * SOUND_ROW_HEIGHT))
	}

	private fun settingRowBounds(menu: Bounds, rowIndex: Int, rowHeight: Int): Bounds {
		val rowTop = menu.top + 40 + (rowIndex * (rowHeight + SETTING_GAP))
		val rowLeft = menu.left + 10
		return Bounds(rowLeft, rowTop, rowLeft + SETTING_WIDTH, rowTop + rowHeight)
	}

	private fun experimentationRowBounds(menu: Bounds, rowIndex: Int, rowHeight: Int): Bounds {
		val layout = intArrayOf(
			SETTING_HEIGHT,
			SETTING_HEIGHT,
			SETTING_HEIGHT,
			SETTING_HEIGHT,
			SETTING_HEIGHT,
			TEXT_INPUT_SETTING_HEIGHT,
			TEXT_INPUT_SETTING_HEIGHT,
			TEXT_INPUT_SETTING_HEIGHT,
		)
		var rowTop = menu.top + 40
		for (index in 0 until rowIndex.coerceAtMost(layout.size)) {
			rowTop += layout[index] + SETTING_GAP
			if (index == 4) {
				rowTop += EXPERIMENTS_SECTION_GAP
			}
		}
		val rowLeft = menu.left + 10
		return Bounds(rowLeft, rowTop, rowLeft + SETTING_WIDTH, rowTop + rowHeight)
	}

	private fun coopRelayToggleBounds(menu: Bounds): Bounds {
		val rowTop = menu.top + 40 + (4 * (TEXT_INPUT_SETTING_HEIGHT + SETTING_GAP))
		return Bounds(menu.left + 10, rowTop, menu.left + 10 + SETTING_WIDTH, rowTop + SETTING_HEIGHT)
	}

	private fun chatImplosionHiderBounds(menu: Bounds): Bounds = settingRowBounds(menu, 0, SETTING_HEIGHT)

	private fun chatImplosionExampleBounds(menu: Bounds): Bounds {
		val top = chatImplosionHiderBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun ircTestConnectionBounds(menu: Bounds): Bounds {
		val rowTop = coopRelayToggleBounds(menu).bottom + SETTING_GAP
		val rowLeft = menu.left + 10
		return Bounds(rowLeft, rowTop, rowLeft + SETTING_WIDTH, rowTop + SETTING_HEIGHT)
	}

	private fun hudEditorBounds(menu: Bounds): Bounds {
		val rowTop = updaterInfoBounds(menu).bottom + SETTING_GAP
		val rowLeft = menu.left + 10
		return Bounds(rowLeft, rowTop, rowLeft + SETTING_WIDTH, rowTop + SETTING_HEIGHT)
	}

	private fun updateCheckNowBounds(menu: Bounds): Bounds {
		val rowTop = menu.top + 40 + (2 * (SETTING_HEIGHT + SETTING_GAP))
		val rowLeft = menu.left + 10
		return Bounds(rowLeft, rowTop, rowLeft + SETTING_WIDTH, rowTop + SETTING_HEIGHT)
	}

	private fun updaterInfoBounds(menu: Bounds): Bounds {
		val top = updateCheckNowBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun mobModelEntityTypeBounds(menu: Bounds): Bounds {
		return settingRowBounds(menu, 0, TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun mobModelVariantBounds(menu: Bounds): Bounds {
		val top = if (mobModelDropdownOpen) mobModelListBounds(menu).bottom + SETTING_GAP else mobModelEntityTypeBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun mobModelBabyBounds(menu: Bounds): Bounds {
		val top = if (mobModelVariantDropdownOpen) mobModelVariantListBounds(menu).bottom + SETTING_GAP else mobModelVariantBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun mobModelShowArmorBounds(menu: Bounds): Bounds {
		val top = mobModelBabyBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun mobModelShowHeldItemsBounds(menu: Bounds): Bounds {
		val top = mobModelShowArmorBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun mobModelScaleBounds(menu: Bounds): Bounds {
		val top = mobModelShowHeldItemsBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun mobModelStatusBounds(menu: Bounds): Bounds {
		val top = mobModelScaleBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun inventoryPreviewShowArmorBounds(menu: Bounds): Bounds {
		return settingRowBounds(menu, 0, SETTING_HEIGHT)
	}

	private fun inventoryPreviewHudInfoBounds(menu: Bounds): Bounds {
		return settingRowBounds(menu, 1, TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun crosshairShowInFirstPersonBounds(menu: Bounds): Bounds {
		return settingRowBounds(menu, 0, SETTING_HEIGHT)
	}

	private fun crosshairVisibleInF5Bounds(menu: Bounds): Bounds {
		return settingRowBounds(menu, 1, SETTING_HEIGHT)
	}

	private fun crosshairResetBounds(menu: Bounds): Bounds {
		return settingRowBounds(menu, 2, SETTING_HEIGHT)
	}

	private fun crosshairGridBounds(menu: Bounds): Bounds {
		val top = crosshairResetBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + CROSSHAIR_GRID_SETTING_HEIGHT)
	}

	private fun crosshairGridEditorBounds(row: Bounds): Bounds {
		val size = CustomCrosshairFeature.GRID_SIZE * CROSSHAIR_GRID_CELL_SIZE
		val left = row.left + (row.width() - size) / 2
		val top = row.top + 18
		return Bounds(left, top, left + size, top + size)
	}

	private fun crosshairCellBounds(grid: Bounds, row: Int, column: Int): Bounds {
		val left = grid.left + column * CROSSHAIR_GRID_CELL_SIZE
		val top = grid.top + row * CROSSHAIR_GRID_CELL_SIZE
		return Bounds(left, top, left + CROSSHAIR_GRID_CELL_SIZE, top + CROSSHAIR_GRID_CELL_SIZE)
	}

	private fun m5LividFinderBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 40 + SETTING_HEIGHT)
	}

	private fun m5TracerBounds(menu: Bounds): Bounds {
		val top = m5LividFinderBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun m5IceSprayBounds(menu: Bounds): Bounds {
		val top = m5TracerBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun m5RagAxeBounds(menu: Bounds): Bounds {
		val top = m5IceSprayBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun m5StatusBounds(menu: Bounds): Bounds {
		val top = m5RagAxeBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun dungeonAutoKickRowAfter(previous: Bounds, rowHeight: Int): Bounds {
		val top = previous.bottom + SETTING_GAP
		return Bounds(previous.left, top, previous.right, top + rowHeight)
	}

	private fun dungeonAutoKickStatsDisplayBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 40 + SETTING_HEIGHT)
	}

	private fun dungeonAutoKickKickLineBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(dungeonAutoKickStatsDisplayBounds(menu), SETTING_HEIGHT)
	}

	private fun dungeonAutoKickAutoKickBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(dungeonAutoKickKickLineBounds(menu), SETTING_HEIGHT)
	}

	private fun dungeonAutoKickFloorBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(dungeonAutoKickAutoKickBounds(menu), SETTING_HEIGHT)
	}

	private fun dungeonAutoKickFloorListBounds(menu: Bounds): Bounds {
		val top = dungeonAutoKickFloorBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 18, top, menu.right - 18, top + (DUNGEON_AUTOKICK_FLOOR_VISIBLE_ROWS * SOUND_ROW_HEIGHT))
	}

	private fun dungeonAutoKickAfterFloorBounds(menu: Bounds, rowHeight: Int): Bounds {
		val previous = if (dungeonAutoKickFloorDropdownOpen) dungeonAutoKickFloorListBounds(menu) else dungeonAutoKickFloorBounds(menu)
		return dungeonAutoKickRowAfter(previous, rowHeight)
	}

	private fun dungeonAutoKickMaxPbBounds(menu: Bounds): Bounds {
		return dungeonAutoKickAfterFloorBounds(menu, TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun dungeonAutoKickMinSecretsBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(dungeonAutoKickMaxPbBounds(menu), TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun dungeonAutoKickMinMpBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(dungeonAutoKickMinSecretsBounds(menu), TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun dungeonAutoKickApiOffBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(dungeonAutoKickMinMpBounds(menu), SETTING_HEIGHT)
	}

	private fun dungeonAutoKickInformBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(dungeonAutoKickApiOffBounds(menu), SETTING_HEIGHT)
	}

	private fun dungeonAutoKickCacheBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(dungeonAutoKickInformBounds(menu), SETTING_HEIGHT)
	}

	private fun dungeonAutoKickClearCacheBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(partyFinderRightClickBounds(menu), SETTING_HEIGHT)
	}

	private fun dungeonAutoKickStatusBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(dungeonAutoKickClearCacheBounds(menu), TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun partyFinderGuiStatsBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(dungeonAutoKickCacheBounds(menu), SETTING_HEIGHT)
	}

	private fun partyFinderHighlightsBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(partyFinderGuiStatsBounds(menu), SETTING_HEIGHT)
	}

	private fun partyFinderMemberCountBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(partyFinderHighlightsBounds(menu), SETTING_HEIGHT)
	}

	private fun partyFinderRightClickBounds(menu: Bounds): Bounds {
		return dungeonAutoKickRowAfter(partyFinderMemberCountBounds(menu), SETTING_HEIGHT)
	}

	private fun selectedDungeonAutoKickFloorIndex(): Int {
		val current = "${if (workingCopy.dungeonAutoKickMasterMode) "M" else "F"}${workingCopy.dungeonAutoKickFloor}"
		return DUNGEON_AUTOKICK_FLOOR_OPTIONS.indexOf(current)
	}

	private fun timeChangerModeBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 40 + SETTING_HEIGHT)
	}

	private fun auctionHouseAutoCopyBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 40 + SETTING_HEIGHT)
	}

	private fun highClassDiceTrackerBounds(menu: Bounds): Bounds {
		return slayerRowAfter(auctionHouseAutoCopyBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerRowAfter(previous: Bounds, rowHeight: Int): Bounds {
		val top = previous.bottom + SETTING_GAP
		return Bounds(previous.left, top, previous.right, top + rowHeight)
	}

	private fun slayerBlazeHeaderBounds(menu: Bounds): Bounds {
		return settingRowBounds(menu, 0, SETTING_HEIGHT)
	}

	private fun slayerBlazePhaseDisplayBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerBlazeHeaderBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerBlazeColoredMobsBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerBlazePhaseDisplayBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerBlazeAutoDaggerBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerBlazeColoredMobsBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerBlazeAutoDaggerDelayBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerBlazeAutoDaggerBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerBlazeAutoDaggerResetAfterBossBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerBlazeAutoDaggerDelayBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerBlazeAutoDaggerDebugBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerBlazeAutoDaggerResetAfterBossBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerMiscHeaderBounds(menu: Bounds): Bounds {
		val previous = if (slayerBlazeExpanded) slayerBlazeAutoDaggerDebugBounds(menu) else slayerBlazeHeaderBounds(menu)
		return slayerRowAfter(previous, SETTING_HEIGHT)
	}

	private fun slayerRngMeterDisplayBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerMiscHeaderBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerRngMeterOptimalRemovalBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerRngMeterDisplayBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerRngMeterCompactModeBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerRngMeterOptimalRemovalBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerRngMeterUseMagicFindBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerRngMeterCompactModeBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerRngMeterMagicFindBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerRngMeterUseMagicFindBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerSpawnAnnouncerBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerRngMeterMagicFindBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerAnnouncerTextBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerSpawnAnnouncerBounds(menu), TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun slayerAnnouncerSoundBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerAnnouncerTextBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerAnnouncerPreviewBounds(menu: Bounds): Bounds {
		return slayerRowAfter(slayerAnnouncerPitchBounds(menu), SETTING_HEIGHT)
	}

	private fun slayerAnnouncerVolumeBounds(menu: Bounds): Bounds {
		val top = if (soundDropdownOpen) soundListBounds(menu).bottom + SETTING_GAP else slayerAnnouncerSoundBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun slayerAnnouncerPitchBounds(menu: Bounds): Bounds {
		val top = slayerAnnouncerVolumeBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun autoExperimentsClickDelayBounds(menu: Bounds): Bounds {
		return experimentationRowBounds(menu, 3, TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun autoExperimentsDelayVarietyBounds(menu: Bounds): Bounds {
		return experimentationRowBounds(menu, 4, TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun autoExperimentsAutoCloseBounds(menu: Bounds): Bounds {
		return experimentationRowBounds(menu, 1, SETTING_HEIGHT)
	}

	private fun autoExperimentsSerumCountBounds(menu: Bounds): Bounds {
		return experimentationRowBounds(menu, 5, TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun autoExperimentsGetMaxXpBounds(menu: Bounds): Bounds {
		return experimentationRowBounds(menu, 2, SETTING_HEIGHT)
	}

	private fun autoExperimentsAutoPairsBounds(menu: Bounds): Bounds {
		return experimentationRowBounds(menu, 0, SETTING_HEIGHT)
	}

	private fun autoCroesusEnabledBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 40 + SETTING_HEIGHT)
	}

	private fun autoCroesusNoClickBounds(menu: Bounds): Bounds {
		val top = autoCroesusEnabledBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun autoCroesusUseKismetsBounds(menu: Bounds): Bounds {
		val top = autoCroesusNoClickBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun autoCroesusKismetFloorsBounds(menu: Bounds): Bounds {
		val top = autoCroesusUseKismetsBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun autoCroesusKismetProfitBounds(menu: Bounds): Bounds {
		val top = autoCroesusKismetFloorsBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun autoCroesusUseChestKeysBounds(menu: Bounds): Bounds {
		val top = autoCroesusKismetProfitBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun autoCroesusChestKeyProfitBounds(menu: Bounds): Bounds {
		val top = autoCroesusUseChestKeysBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun autoCroesusClickDelayBounds(menu: Bounds): Bounds {
		val top = autoCroesusChestKeyProfitBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun trimToWidth(value: String, maxWidth: Int): String {
		if (textRenderer.getWidth(value) <= maxWidth) {
			return value
		}

		var trimmed = value
		while (trimmed.length > 3 && textRenderer.getWidth("$trimmed...") > maxWidth) {
			trimmed = trimmed.dropLast(1)
		}
		return "$trimmed..."
	}

	private fun activeSoundSearchField(): TextFieldWidget {
		return when (openedSection) {
			ConfigSection.PICKAXE_COOLDOWN -> pickaxeAlertSoundSearchField
			ConfigSection.CHIMERA_DROP -> chimeraDropSoundSearchField
			ConfigSection.FIRE_FREEZE -> fireFreezeAlertSoundSearchField
			ConfigSection.SLAYER -> slayerAnnouncerSoundSearchField
			else -> lostFightSoundSearchField
		}
	}

	private fun activeSelectedSoundId(): String {
		return when (openedSection) {
			ConfigSection.PICKAXE_COOLDOWN -> workingCopy.pickaxeAbilityCooldownAlertSoundId
			ConfigSection.CHIMERA_DROP -> workingCopy.chimeraBookDropEffectsSoundId
			ConfigSection.FIRE_FREEZE -> workingCopy.fireFreezeRefreezeAlertSoundId
			ConfigSection.SLAYER -> workingCopy.slayerSpawnAnnouncerSoundId
			else -> workingCopy.hideonleafLostFightAlertSoundId
		}
	}

	private fun filteredMobModelOptions(query: String): List<String> {
		val normalizedQuery = query.trim().lowercase(Locale.ROOT)
		return MobModelCatalog.ids()
			.filter { entityId ->
				normalizedQuery.isBlank() ||
					entityId.contains(normalizedQuery) ||
					MobModelCatalog.displayName(entityId).lowercase(Locale.ROOT).contains(normalizedQuery)
			}
	}

	private fun filteredMobModelVariantOptions(query: String): List<String> {
		val normalizedQuery = query.trim().lowercase(Locale.ROOT)
		return mobModelVariantOptions()
			.filter { variantId ->
				normalizedQuery.isBlank() || variantId.contains(normalizedQuery)
			}
	}

	private fun setVisible(widget: TextFieldWidget, visible: Boolean) {
		widget.visible = visible
		widget.setEditable(visible)
		widget.setFocusUnlocked(visible)
		if (!visible) {
			widget.setFocused(false)
		}
	}

	private data class ConfigPanel(val title: String, val sections: List<ConfigSection>)

	private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
		fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
		fun width(): Int = right - left
		fun height(): Int = bottom - top
	}

	private enum class ColorPickerDragTarget {
		SATURATION_BRIGHTNESS,
		HUE,
	}

	private enum class SliderDragTarget {
		LINE_MODE,
		LINE_WIDTH,
		MOB_MODEL_SCALE,
		ALERT_VOLUME,
		ALERT_PITCH,
		PICKAXE_ALERT_VOLUME,
		PICKAXE_ALERT_PITCH,
		CHIMERA_DROP_VOLUME,
		CHIMERA_DROP_PITCH,
		FIRE_FREEZE_LINE_WIDTH,
		FIRE_FREEZE_ALERT_VOLUME,
		FIRE_FREEZE_ALERT_PITCH,
		SLAYER_ANNOUNCER_VOLUME,
		SLAYER_ANNOUNCER_PITCH,
	}

	private enum class ConfigSection(
		val label: String,
		val description: String,
		val toggleable: Boolean = false,
	) {
		SETUP("Setup", "Fixed mod API endpoint and global status."),
		IRC_BRIDGE("IRC Bridge", "IRC server, auth, polling, and message format.", toggleable = true),
		CHAT("Chat", "Client-side chat cleanup and message hiders.", toggleable = true),
		HIDEONLEAF_HELPER("Hideonleaf Helper", "Shulker glow and Hideonleaf fight alerts.", toggleable = true),
		PURPLE_TERRACOTTA("Purple Terracotta", "Highlights purple terracotta blocks through walls.", toggleable = true),
		FLOOR_DROP_ESP("Floor Drop ESP", "Highlights Galatea floor drops made from three grouped string displays.", toggleable = true),
		WORMHOLE_FINDER("Wormhole Finder", "Shows a water-surface ring and tracer for the active wormhole.", toggleable = true),
		AUTO_SPRINT("Auto Sprint", "Automatically starts sprinting while moving forward.", toggleable = true),
		TIME_CHANGER("Time Changer", "Client-side world time presets.", toggleable = true),
		AUCTION_HOUSE("Auction House", "Copies BIN underbids and tracks High Class Dice LBIN sell signals.", toggleable = true),
		SLAYER("Slayer", "Slayer helpers including Blaze boss phase displays and Autopet spawn announcements.", toggleable = true),
		PEST_ESP("Pest ESP", "Highlights named Garden pests through walls.", toggleable = true),
		CORPSE_ESP("Corpse ESP", "Highlights Glacite Mineshaft corpses by armor-stand helmet ID.", toggleable = true),
		MOB_MODEL("Mob Model", "Replaces the player model client-side with any living mob model and syncs it through the backend.", toggleable = true),
		CROSSHAIR("Crosshair", "Overrides the vanilla or texturepack crosshair with a custom editable grid.", toggleable = true),
		INVENTORY_PREVIEW("Inventory Preview", "Shows your inventory as a HUD element with optional armor slot rendering.", toggleable = true),
		SILENT_DISCONNECT("Silent Disconnect", "Sets your Hypixel status offline on disconnect and restores it on rejoin.", toggleable = true),
		CHIMERA_DROP("Chimera Drop", "Shows the Totem-style screen effect when a Chimera book drops.", toggleable = true),
		M5("M5", "Livid finder, Ice Spray timer, and Rag Axe alert for Master Mode Floor 5.", toggleable = true),
		DUNGEON_AUTOKICK("Dungeon AutoKick", "Odin-style Party Finder stats and requirement-based autokick using the Xclipsen backend.", toggleable = true),
		PICKAXE_COOLDOWN("Pickaxe Cooldown", "HUD for mining ability cooldowns from the Hypixel tab list.", toggleable = true),
		FIRE_FREEZE("Fire Freeze", "SkyHanni-style Fire Freeze timers, circle, mob boxes, and refreeze alert.", toggleable = true),
		MINESHAFT_AUTOWARP("Mineshaft AutoWarp", "Auto-requests lead and party-warps when configured corpse counts are found.", toggleable = true),
		DEPLOYBLE("Deployble", "Alerts when your deployable items are about to expire.", toggleable = true),
		AUTO_CROESUS("AutoCroesus", "Dungeon chest autoclaimer module with its original /ac command set.", toggleable = true),
		EXPERIMENTS("Experimentation", "Shizo-style auto experiments plus SkyHanni keep-items-visible for Superpairs.", toggleable = true),
		DOOR("Door", "Turns the disappearing blocks behind Mort into local barrier blocks using relative offsets.", toggleable = true),
		RED_VIGNETTE("Red Vignette", "Matches Devonian's client-side click fix for the red vignette.", toggleable = true),
		STATUS("Status", "Current config path and backend state."),
	}

	private enum class ConfigField(val section: ConfigSection) {
		IRC_SERVER_URL(ConfigSection.IRC_BRIDGE),
		AUTH_TOKEN(ConfigSection.IRC_BRIDGE),
		POLL_INTERVAL(ConfigSection.IRC_BRIDGE),
		IRC_FORMAT(ConfigSection.IRC_BRIDGE),
		AUTO_EXPERIMENTS_CLICK_DELAY(ConfigSection.EXPERIMENTS),
		AUTO_EXPERIMENTS_DELAY_VARIETY(ConfigSection.EXPERIMENTS),
		AUTO_EXPERIMENTS_SERUM_COUNT(ConfigSection.EXPERIMENTS),
		AUTO_CROESUS_CLICK_DELAY(ConfigSection.AUTO_CROESUS),
		AUTO_CROESUS_KISMET_MIN_PROFIT(ConfigSection.AUTO_CROESUS),
		AUTO_CROESUS_KISMET_FLOORS(ConfigSection.AUTO_CROESUS),
		AUTO_CROESUS_CHEST_KEY_MIN_PROFIT(ConfigSection.AUTO_CROESUS),
		SHULKER_GLOW_COLOR(ConfigSection.HIDEONLEAF_HELPER),
		SHULKER_PROJECTILE_GLOW_COLOR(ConfigSection.HIDEONLEAF_HELPER),
		SHULKER_TRACER_LINE_COLOR(ConfigSection.HIDEONLEAF_HELPER),
		PURPLE_TERRACOTTA_HIGHLIGHT_COLOR(ConfigSection.PURPLE_TERRACOTTA),
		PEST_ESP_COLOR(ConfigSection.PEST_ESP),
		FIRE_FREEZE_CIRCLE_COLOR(ConfigSection.FIRE_FREEZE),
		MOB_MODEL_ENTITY_TYPE(ConfigSection.MOB_MODEL),
		MOB_MODEL_VARIANT(ConfigSection.MOB_MODEL),
		PICKAXE_ALERT_TEXT(ConfigSection.PICKAXE_COOLDOWN),
		SLAYER_ANNOUNCER_TEXT(ConfigSection.SLAYER),
		MINESHAFT_AUTOWARP_RULE(ConfigSection.MINESHAFT_AUTOWARP),
		MINESHAFT_AUTOWARP_DELAY(ConfigSection.MINESHAFT_AUTOWARP),
		MINESHAFT_AUTOWARP_WINDOW(ConfigSection.MINESHAFT_AUTOWARP),
		DUNGEON_AUTOKICK_MAX_PB(ConfigSection.DUNGEON_AUTOKICK),
		DUNGEON_AUTOKICK_MIN_SECRETS(ConfigSection.DUNGEON_AUTOKICK),
		DUNGEON_AUTOKICK_MIN_MP(ConfigSection.DUNGEON_AUTOKICK),
	}

	companion object {
		private val AUTO_CROESUS_FLOOR_PATTERN = Regex("^[FM][1-7]$")
		private const val ACCENT = 0xFF36C5F0.toInt()
		private const val ACCENT_TRANS = 0x7836C5F0
		private const val PANEL_HEADER = 0xE6141414.toInt()
		private const val PANEL_BODY = 0xB40F0F0F.toInt()
		private const val HOVER = 0x22FFFFFF
		private const val SELECTED = 0x6636C5F0
		private const val POPUP_BACKGROUND = 0xF0141414.toInt()
		private const val SETTING_BACKGROUND = 0x640A0A0A
		private const val INPUT_BACKGROUND = 0xB40A0A0A.toInt()
		private const val TEXT_WHITE = 0xFFFFFFFF.toInt()
		private const val TEXT_PRIMARY = 0xFFE8E8E8.toInt()
		private const val TEXT_DISABLED = 0xFF8B8B8B.toInt()
		private const val TEXT_MUTED = 0xFFA0A0A0.toInt()
		private const val TEXT_ERROR = 0xFFFF8080.toInt()
		private const val TEXT_SUCCESS = 0xFFA0FFA0.toInt()
		private const val LEFT_MOUSE_BUTTON = 0
		private const val RIGHT_MOUSE_BUTTON = 1
		private const val PANEL_WIDTH = 110
		private const val PANEL_HEADER_HEIGHT = 22
		private const val PANEL_ROW_HEIGHT = 16
		private const val POPUP_WIDTH = 200
		private const val POPUP_HEIGHT = 250
		private const val SETUP_POPUP_HEIGHT = 120
		private const val IRC_POPUP_HEIGHT = 330
		private const val CHAT_POPUP_HEIGHT = 145
		private const val HIDEONLEAF_POPUP_HEIGHT = 500
		private const val PURPLE_TERRACOTTA_POPUP_HEIGHT = 230
		private const val TIME_CHANGER_POPUP_HEIGHT = 100
		private const val AUCTION_HOUSE_POPUP_HEIGHT = 150
		private const val PEST_ESP_POPUP_HEIGHT = 230
		private const val CORPSE_ESP_POPUP_HEIGHT = 410
		private const val MOB_MODEL_POPUP_HEIGHT = 325
		private const val MOB_MODEL_POPUP_WITH_DROPDOWN_HEIGHT = 455
		private const val CROSSHAIR_POPUP_HEIGHT = 265
		private const val INVENTORY_PREVIEW_POPUP_HEIGHT = 165
		private const val SILENT_DISCONNECT_POPUP_HEIGHT = 185
		private const val CHIMERA_DROP_POPUP_HEIGHT = 380
		private const val CHIMERA_DROP_POPUP_WITH_DROPDOWN_HEIGHT = 480
		private const val M5_POPUP_HEIGHT = 190
		private const val DUNGEON_AUTOKICK_POPUP_HEIGHT = 610
		private const val DUNGEON_AUTOKICK_POPUP_WITH_DROPDOWN_HEIGHT = 690
		private const val STATUS_POPUP_HEIGHT = 255
		private const val PICKAXE_COOLDOWN_POPUP_COLLAPSED_HEIGHT = 145
		private const val PICKAXE_COOLDOWN_POPUP_EXPANDED_HEIGHT = 320
		private const val PICKAXE_COOLDOWN_POPUP_EXPANDED_WITH_DROPDOWN_HEIGHT = 420
		private const val FIRE_FREEZE_POPUP_HEIGHT = 410
		private const val FIRE_FREEZE_POPUP_WITH_DROPDOWN_HEIGHT = 510
		private const val MINESHAFT_AUTOWARP_POPUP_HEIGHT = 230
		private const val SETTING_WIDTH = 180
		private const val SETTING_HEIGHT = 20
		private const val TEXT_INPUT_SETTING_HEIGHT = 38
		private const val TEXT_INPUT_WIDTH = 164
		private const val COLOR_INPUT_WIDTH = 134
		private const val SETTING_GAP = 5
		private const val EXPERIMENTS_SECTION_GAP = 12
		private const val SEARCH_WIDTH = 150
		private const val SOUND_VISIBLE_ROWS = 6
		private const val MOB_MODEL_VISIBLE_ROWS = 7
		private const val DUNGEON_AUTOKICK_FLOOR_VISIBLE_ROWS = 5
		private const val SOUND_ROW_HEIGHT = 15
		private val DUNGEON_AUTOKICK_FLOOR_OPTIONS = listOf("F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7")
		private const val SOUND_LIST_TEXT_WIDTH = 145
		private const val CROSSHAIR_GRID_CELL_SIZE = 18
		private const val CROSSHAIR_GRID_SETTING_HEIGHT = 150
		private const val DEFAULT_GLOW_COLOR = 0x36C5F0
		private const val COLOR_PICKER_STEP = 2
		private const val COLOR_PICKER_BLOCK_HEIGHT = 122
		private val HEX_COLOR_PATTERN = Regex("[0-9a-fA-F]{6}")

		private fun copyOf(source: BridgeConfig): BridgeConfig = source.copy()

		private fun copyOf(source: AcConfig): AcConfig = AcConfig().also {
			it.lastApiUpdate = source.lastApiUpdate
			it.minClickDelay = source.minClickDelay
			it.noClick = source.noClick
			it.useKismets = source.useKismets
			it.kismetMinProfit = source.kismetMinProfit
			it.kismetFloors = ArrayList(source.kismetFloors)
			it.useChestKeys = source.useChestKeys
			it.chestKeyMinProfit = source.chestKeyMinProfit
		}
	}
}
