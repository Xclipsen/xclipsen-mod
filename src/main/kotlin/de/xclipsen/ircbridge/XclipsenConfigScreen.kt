package de.xclipsen.ircbridge

import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.GuiGraphicsExtractor as GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.io.IOException
import java.util.Locale

class XclipsenConfigScreen(
	private val parent: Screen?,
	private val mod: XclipsenIrcBridgeClient,
) : Screen(Component.literal("Xclipsen Settings")) {
	private val textRenderer: Font
		get() = font
	private var EditBox.text: String
		get() = value
		set(value) = setValue(value)

	private fun GuiGraphicsExtractor.drawTextWithShadow(font: Font, text: String, x: Int, y: Int, color: Int) {
		text(font, text, x, y, color, true)
	}

	private fun GuiGraphicsExtractor.drawTextWithShadow(font: Font, text: Component, x: Int, y: Int, color: Int) {
		text(font, text, x, y, color, true)
	}

	private fun GuiGraphicsExtractor.drawCenteredTextWithShadow(font: Font, text: String, x: Int, y: Int, color: Int) {
		centeredText(font, text, x, y, color)
	}

	private fun GuiGraphicsExtractor.drawCenteredTextWithShadow(font: Font, text: Component, x: Int, y: Int, color: Int) {
		centeredText(font, text, x, y, color)
	}

	private fun GuiGraphicsExtractor.drawTooltip(font: Font, text: Component, x: Int, y: Int) {
		setTooltipForNextFrame(font, text, x, y)
	}

	private var workingCopy: BridgeConfig = copyOf(mod.config())
	private var selectedSection = ConfigSection.SETUP
	private var openedSection: ConfigSection? = null
	private var openColorField: ConfigField? = null
	private var draggingColorPicker: ColorPickerDragTarget? = null
	private var soundDropdownOpen = false
	private var soundScrollOffset = 0
	private var settingsScrollOffset = 0
	private var focusedModuleIndex = -1
	private var focusedSettingIndex = -1
	private val focusableSettingRows = mutableListOf<Bounds>()
	private val focusableSettingFields = mutableListOf<EditBox?>()
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
	private var statusMessage: Component = Component.empty()
	private val colorPickerOpen: Boolean
		get() = openColorField != null

	private lateinit var searchField: EditBox
	private lateinit var ircServerBaseUrlField: EditBox
	private lateinit var backendAuthTokenField: EditBox
	private lateinit var backendPollIntervalField: EditBox
	private lateinit var ircFormatField: EditBox
	private lateinit var autoExperimentsClickDelayField: EditBox
	private lateinit var autoExperimentsDelayVarietyField: EditBox
	private lateinit var autoExperimentsSerumCountField: EditBox
	private lateinit var shulkerGlowColorHexField: EditBox
	private lateinit var shulkerProjectileGlowColorHexField: EditBox
	private lateinit var shulkerTracerLineColorHexField: EditBox
	private lateinit var purpleTerracottaHighlightColorHexField: EditBox
	private lateinit var pestEspColorHexField: EditBox
	private lateinit var fireFreezeCircleColorHexField: EditBox
	private lateinit var mobModelEntityTypeField: EditBox
	private lateinit var mobModelVariantField: EditBox
	private lateinit var pickaxeAlertTextField: EditBox
	private lateinit var slayerAnnouncerTextField: EditBox
	private lateinit var mineshaftAutoWarpRuleField: EditBox
	private lateinit var mineshaftAutoWarpDelayField: EditBox
	private lateinit var mineshaftAutoWarpWindowField: EditBox
	private lateinit var dungeonAutoKickMaxPbField: EditBox
	private lateinit var dungeonAutoKickMinSecretsField: EditBox
	private lateinit var dungeonAutoKickMinMpField: EditBox
	private lateinit var lostFightSoundSearchField: EditBox
	private lateinit var wormholeSoundSearchField: EditBox
	private lateinit var pickaxeAlertSoundSearchField: EditBox
	private lateinit var fireFreezeAlertSoundSearchField: EditBox
	private lateinit var chimeraDropSoundSearchField: EditBox
	private lateinit var slayerAnnouncerSoundSearchField: EditBox

	private val fields = mutableMapOf<ConfigField, EditBox>()
	private val sectionRows = ConfigCategory.entries.map { category ->
		ConfigPanel(category.name, ConfigSection.entries.filter { it.category == category })
	}

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
		wormholeSoundSearchField = addField(0, 0, 150, "", "Search sound...")
		pickaxeAlertSoundSearchField = addField(0, 0, 150, "", "Search sound...")
		fireFreezeAlertSoundSearchField = addField(0, 0, 150, "", "Search sound...")
		chimeraDropSoundSearchField = addField(0, 0, 150, "", "Search sound...")
		slayerAnnouncerSoundSearchField = addField(0, 0, 150, "", "Search sound...")
		layoutWidgets()
	}

	override fun onClose() {
		readWorkingCopyFromFields(updateStatus = false)?.let {
			try {
				mod.saveAndApplyConfig(it)
			} catch (_: IOException) {
			}
		}
		minecraft.setScreen(parent)
	}

	override fun isPauseScreen(): Boolean = false

	override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		context.fill(0, 0, width, height, XclipsenUiTokens.SURFACE_EDITOR_OVERLAY)

		drawPanels(context, mouseX, mouseY)
		drawSearch(context)
		drawSettingsMenu(context, mouseX, mouseY)
		drawTooltip(context, mouseX, mouseY)

		super.extractRenderState(context, mouseX, mouseY, delta)
	}

	override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
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

			if (!settingsBodyBounds(menu).contains(mouseX, mouseY)) {
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
			statusMessage = Component.empty()
			if (button == LEFT_MOUSE_BUTTON && clickedSection.toggleable) {
				clickedSection.toggle(workingCopy)
				saveModuleState()
			} else if ((button == LEFT_MOUSE_BUTTON && !clickedSection.toggleable) ||
				(button == RIGHT_MOUSE_BUTTON && clickedSection.hasSettings)
			) {
				openSection(clickedSection)
			}
			layoutWidgets()
			return true
		}

		return super.mouseClicked(click, doubled)
	}

	override fun keyPressed(input: KeyEvent): Boolean {
		if (input.key() == GLFW.GLFW_KEY_ESCAPE && openedSection != null) {
			closeOpenedSection()
			return true
		}
		if (input.key() == GLFW.GLFW_KEY_TAB) {
			if (openedSection == null) {
				val modules = visibleModuleRows()
				if (modules.isNotEmpty()) {
					focusedModuleIndex = cycleIndex(focusedModuleIndex, modules.size, input.modifiers() and GLFW.GLFW_MOD_SHIFT != 0)
				}
			} else if (focusableSettingRows.isNotEmpty()) {
				focusedSettingIndex = cycleIndex(focusedSettingIndex, focusableSettingRows.size, input.modifiers() and GLFW.GLFW_MOD_SHIFT != 0)
				val field = focusableSettingFields.getOrNull(focusedSettingIndex)
				setFocused(field)
				field?.setFocused(true)
				ensureFocusedSettingVisible()
			}
			return true
		}
		if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_SPACE) {
			if (openedSection == null) {
				visibleModuleRows().getOrNull(focusedModuleIndex)?.let { row ->
					return activateModule(row.section)
				}
			} else {
				focusableSettingFields.getOrNull(focusedSettingIndex)?.let { field ->
					setFocused(field)
					field.setFocused(true)
					return true
				}
				focusableSettingRows.getOrNull(focusedSettingIndex)?.let { row ->
					return handleSettingsClick(openedSection ?: return false, (row.left + row.right) / 2, (row.top + row.bottom) / 2, LEFT_MOUSE_BUTTON)
				}
			}
		}
		if ((input.key() == GLFW.GLFW_KEY_LEFT || input.key() == GLFW.GLFW_KEY_RIGHT) && openedSection != null) {
			if (adjustFocusedSetting(if (input.key() == GLFW.GLFW_KEY_LEFT) -1 else 1)) return true
		}

		return super.keyPressed(input)
	}

	override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
		if (click.button() < 0) {
			return false
		}

		val dragTarget = draggingColorPicker
		val sliderTarget = draggingSlider
		if ((openedSection == ConfigSection.HIDEONLEAF_HELPER || openedSection == ConfigSection.WORMHOLE_FINDER || openedSection == ConfigSection.PICKAXE_COOLDOWN || openedSection == ConfigSection.FIRE_FREEZE || openedSection == ConfigSection.MOB_MODEL || openedSection == ConfigSection.CHIMERA_DROP || openedSection == ConfigSection.SLAYER) && sliderTarget != null) {
			updateSliderFromMouse(click.x().toInt(), sliderTarget)
			return true
		}

		if ((openedSection == ConfigSection.HIDEONLEAF_HELPER || openedSection == ConfigSection.PURPLE_TERRACOTTA || openedSection == ConfigSection.PEST_ESP || openedSection == ConfigSection.FIRE_FREEZE) && dragTarget != null) {
			updateColorFromPicker(click.x().toInt(), click.y().toInt(), dragTarget)
			return true
		}

		return super.mouseDragged(click, offsetX, offsetY)
	}

	override fun mouseReleased(click: MouseButtonEvent): Boolean {
		draggingColorPicker = null
		draggingSlider = null
		if (click.button() < 0) {
			return false
		}
		return super.mouseReleased(click)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
		val contentMenu = settingsContentBounds()
		val settingsBody = settingsBodyBounds(settingsBounds())
		if ((openedSection == ConfigSection.HIDEONLEAF_HELPER || openedSection == ConfigSection.WORMHOLE_FINDER || openedSection == ConfigSection.PICKAXE_COOLDOWN || openedSection == ConfigSection.FIRE_FREEZE || openedSection == ConfigSection.CHIMERA_DROP || openedSection == ConfigSection.SLAYER) && soundDropdownOpen) {
			val list = soundListBounds(contentMenu)
			if (settingsBody.contains(mouseX.toInt(), mouseY.toInt()) && list.contains(mouseX.toInt(), mouseY.toInt())) {
				val filtered = SoundCatalog.filtered(activeSoundSearchField().text)
				val maxScroll = (filtered.size - SOUND_VISIBLE_ROWS).coerceAtLeast(0)
				soundScrollOffset = (soundScrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
				return true
			}
		}

		if (openedSection == ConfigSection.MOB_MODEL && mobModelDropdownOpen) {
			val list = mobModelListBounds(contentMenu)
			if (settingsBody.contains(mouseX.toInt(), mouseY.toInt()) && list.contains(mouseX.toInt(), mouseY.toInt())) {
				val filtered = filteredMobModelOptions(mobModelEntityTypeField.text)
				val maxScroll = (filtered.size - MOB_MODEL_VISIBLE_ROWS).coerceAtLeast(0)
				mobModelScrollOffset = (mobModelScrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
				return true
			}
		}

		if (openedSection == ConfigSection.MOB_MODEL && mobModelVariantDropdownOpen) {
			val list = mobModelVariantListBounds(contentMenu)
			if (settingsBody.contains(mouseX.toInt(), mouseY.toInt()) && list.contains(mouseX.toInt(), mouseY.toInt())) {
				val filtered = filteredMobModelVariantOptions(mobModelVariantField.text)
				val maxScroll = (filtered.size - MOB_MODEL_VISIBLE_ROWS).coerceAtLeast(0)
				mobModelVariantScrollOffset = (mobModelVariantScrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
				return true
			}
		}

		if (openedSection == ConfigSection.DUNGEON_AUTOKICK && dungeonAutoKickFloorDropdownOpen) {
			val list = dungeonAutoKickFloorListBounds(contentMenu)
			if (settingsBody.contains(mouseX.toInt(), mouseY.toInt()) && list.contains(mouseX.toInt(), mouseY.toInt())) {
				val maxScroll = (DUNGEON_AUTOKICK_FLOOR_OPTIONS.size - DUNGEON_AUTOKICK_FLOOR_VISIBLE_ROWS).coerceAtLeast(0)
				dungeonAutoKickFloorScrollOffset = (dungeonAutoKickFloorScrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
				return true
			}
		}
		openedSection?.let {
			val body = settingsBodyBounds(settingsBounds())
			if (body.contains(mouseX.toInt(), mouseY.toInt())) {
				settingsScrollOffset = (settingsScrollOffset - (verticalAmount * SETTINGS_SCROLL_STEP).toInt())
					.coerceIn(0, maxSettingsScroll())
				layoutWidgets()
				return true
			}
		}

		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
	}

	private fun saveModuleState() {
		try {
			mod.saveAndApplyConfig(workingCopy)
		} catch (_: IOException) {
			statusMessage = Component.literal("Failed to save module state.")
		}
	}

	private fun openSection(section: ConfigSection) {
		openedSection = section
		settingsScrollOffset = 0
		focusedSettingIndex = -1
		openColorField = null
		soundDropdownOpen = false
		mobModelDropdownOpen = false
		mobModelVariantDropdownOpen = false
		dungeonAutoKickFloorDropdownOpen = false
		draggingColorPicker = null
		draggingSlider = null
	}

	private fun closeOpenedSection() {
		readWorkingCopyFromFields(updateStatus = false)
		openedSection = null
		settingsScrollOffset = 0
		focusedSettingIndex = -1
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
			statusMessage = Component.literal("Saved.")
			onClose()
		} catch (_: IOException) {
			statusMessage = Component.literal("Failed to save config.")
		}
	}

	private fun testConnection() {
		val candidate = readWorkingCopyFromFields(updateStatus = true) ?: run {
			return
		}
		statusMessage = Component.literal("Testing connection...")
		mod.testBackendConnection(candidate) { status ->
			if (minecraft?.screen === this) {
				statusMessage = Component.literal(XclipsenIrcBridgeClient.formatStatus(status))
			}
		}
	}

	private fun checkForUpdatesNow() {
		val candidate = readWorkingCopyFromFields(updateStatus = true) ?: return
		try {
			mod.saveAndApplyConfig(candidate)
			statusMessage = if (ModUpdateChecker.requestCheckNow()) {
				Component.literal("Started update check.")
			} else {
				Component.literal("Update check already running or disabled.")
			}
		} catch (_: IOException) {
			statusMessage = Component.literal("Failed to save config.")
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
		candidate.shardTrackerEnabled = workingCopy.shardTrackerEnabled
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
		candidate.experimentationTableModuleEnabled = workingCopy.experimentationTableModuleEnabled
		candidate.autoExperimentsAutoClose = workingCopy.autoExperimentsAutoClose
		candidate.autoExperimentsAutoPairs = workingCopy.autoExperimentsAutoPairs
		candidate.autoExperimentsGetMaxXp = workingCopy.autoExperimentsGetMaxXp
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
		candidate.itemUpdateFixModuleEnabled = workingCopy.itemUpdateFixModuleEnabled
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
		candidate.pickobulusHelperModuleEnabled = workingCopy.pickobulusHelperModuleEnabled
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
		candidate.shulkerGlowColorHex = normalizedColor(shulkerGlowColorHexField.text) ?: run {
			if (updateStatus) statusMessage = Component.literal("Glow color must be #RRGGBB.")
			return null
		}
		candidate.shulkerProjectileGlowColorHex = normalizedColor(shulkerProjectileGlowColorHexField.text) ?: run {
			if (updateStatus) statusMessage = Component.literal("Projectile color must be #RRGGBB.")
			return null
		}
		candidate.shulkerTracerLineColorHex = normalizedColor(shulkerTracerLineColorHexField.text) ?: run {
			if (updateStatus) statusMessage = Component.literal("Line color must be #RRGGBB.")
			return null
		}
		candidate.purpleTerracottaHighlightColorHex = normalizedColor(purpleTerracottaHighlightColorHexField.text) ?: run {
			if (updateStatus) statusMessage = Component.literal("Purple terracotta color must be #RRGGBB.")
			return null
		}
		candidate.pestEspColorHex = normalizedColor(pestEspColorHexField.text) ?: run {
			if (updateStatus) statusMessage = Component.literal("Pest ESP color must be #RRGGBB.")
			return null
		}
		candidate.fireFreezeCircleColorHex = normalizedColor(fireFreezeCircleColorHexField.text) ?: run {
			if (updateStatus) statusMessage = Component.literal("Fire Freeze circle color must be #RRGGBB.")
			return null
		}
		candidate.fireFreezeCircleLineWidth = candidate.fireFreezeCircleLineWidth.coerceIn(1.0f, 8.0f)
		val normalizedMobModelEntityType = normalizeMobModelEntityType(candidate.mobModelEntityType)
		val resolvedMobModelEntityType = normalizedMobModelEntityType?.let(MobModelCatalog::resolve)
		candidate.mobModelEntityType = when {
			resolvedMobModelEntityType != null -> normalizedMobModelEntityType
			candidate.mobModelModuleEnabled && normalizedMobModelEntityType == null -> {
				if (updateStatus) statusMessage = Component.literal("Mob model id must be a valid entity like minecraft:zombie.")
				return null
			}
			candidate.mobModelModuleEnabled -> {
				if (updateStatus) statusMessage = Component.literal("Mob model entity must be a living mob on this client.")
				return null
			}
			else -> "minecraft:zombie"
		}
		candidate.mobModelVariant = MobModelVariantCatalog.normalize(candidate.mobModelVariant)
		candidate.mobModelScale = candidate.mobModelScale.coerceIn(0.25f, 4.0f)
		MobModelVariantCatalog.validate(candidate.mobModelEntityType, candidate.mobModelVariant)?.let { variantError ->
			if (updateStatus) statusMessage = Component.literal(variantError)
			return null
		}

		try {
			candidate.backendPollIntervalMs = backendPollIntervalField.text.trim().toLong()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Component.literal("Poll interval must be a number.")
			return null
		}

		if (candidate.backendPollIntervalMs < 500L) {
			if (updateStatus) statusMessage = Component.literal("Poll interval must be at least 500 ms.")
			return null
		}

		try {
			candidate.autoExperimentsClickDelayMs = autoExperimentsClickDelayField.text.trim().toInt()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Component.literal("Auto Experiments click delay must be a number.")
			return null
		}
		if (candidate.autoExperimentsClickDelayMs < 50) {
			if (updateStatus) statusMessage = Component.literal("Auto Experiments click delay must be at least 50 ms.")
			return null
		}

		try {
			candidate.autoExperimentsDelayVarietyMs = autoExperimentsDelayVarietyField.text.trim().toInt()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Component.literal("Auto Experiments delay variety must be a number.")
			return null
		}
		if (candidate.autoExperimentsDelayVarietyMs < 0) {
			if (updateStatus) statusMessage = Component.literal("Auto Experiments delay variety must be at least 0 ms.")
			return null
		}

		try {
			candidate.autoExperimentsSerumCount = autoExperimentsSerumCountField.text.trim().toInt()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Component.literal("Auto Experiments serum count must be a number.")
			return null
		}
		if (candidate.autoExperimentsSerumCount !in 0..3) {
			if (updateStatus) statusMessage = Component.literal("Auto Experiments serum count must be between 0 and 3.")
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
			if (updateStatus) statusMessage = Component.literal(mineshaftRuleError)
			return null
		}

		try {
			candidate.mineshaftAutoWarpDelayMs = mineshaftAutoWarpDelayField.text.trim().toLong()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Component.literal("Mineshaft AutoWarp delay must be a number.")
			return null
		}
		if (candidate.mineshaftAutoWarpDelayMs < 500L) {
			if (updateStatus) statusMessage = Component.literal("Mineshaft AutoWarp delay must be at least 500 ms.")
			return null
		}

		try {
			candidate.mineshaftAutoWarpWindowMs = mineshaftAutoWarpWindowField.text.trim().toLong()
		} catch (_: NumberFormatException) {
			if (updateStatus) statusMessage = Component.literal("Mineshaft AutoWarp window must be a number.")
			return null
		}
		if (candidate.mineshaftAutoWarpWindowMs !in 5_000L..60_000L) {
			if (updateStatus) statusMessage = Component.literal("Mineshaft AutoWarp window must be between 5000 and 60000 ms.")
			return null
		}

		candidate.dungeonAutoKickMaxPbSeconds = parseDurationSeconds(dungeonAutoKickMaxPbField.text) ?: run {
			if (updateStatus) statusMessage = Component.literal("Dungeon AutoKick PB must be seconds or m:ss.")
			return null
		}
		if (candidate.dungeonAutoKickMaxPbSeconds !in 60..900) {
			if (updateStatus) statusMessage = Component.literal("Dungeon AutoKick PB must be between 60 and 900 seconds.")
			return null
		}

		candidate.dungeonAutoKickMinSecretsThousands = dungeonAutoKickMinSecretsField.text.trim().toIntOrNull() ?: run {
			if (updateStatus) statusMessage = Component.literal("Dungeon AutoKick secrets must be a number.")
			return null
		}
		if (candidate.dungeonAutoKickMinSecretsThousands !in 0..200) {
			if (updateStatus) statusMessage = Component.literal("Dungeon AutoKick secrets must be between 0 and 200k.")
			return null
		}

		candidate.dungeonAutoKickMinMagicalPower = dungeonAutoKickMinMpField.text.trim().toIntOrNull() ?: run {
			if (updateStatus) statusMessage = Component.literal("Dungeon AutoKick MP must be a number.")
			return null
		}
		if (candidate.dungeonAutoKickMinMagicalPower !in 0..2500) {
			if (updateStatus) statusMessage = Component.literal("Dungeon AutoKick MP must be between 0 and 2500.")
			return null
		}

		workingCopy = candidate
		return candidate
	}

	private fun registerField(field: ConfigField, value: String, placeholder: String): EditBox {
		val widget = addField(0, 0, 260, value, placeholder)
		fields[field] = widget
		return widget
	}

	private fun addField(x: Int, y: Int, width: Int, value: String, placeholder: String): EditBox {
		val field = EditBox(font, x, y, width, 20, Component.empty())
		field.setMaxLength(512)
		field.value = value
		field.setHint(Component.literal(placeholder))
		field.setBordered(false)
		addRenderableWidget(field)
		return field
	}

	private fun layoutWidgets() {
		val menu = settingsContentBounds()
		val body = settingsBodyBounds(settingsBounds())
		val section = openedSection

		fields.forEach { (field, widget) ->
			val row = section?.let { textFieldBounds(it, field, menu) }
			if (row != null) {
				val inputWidth = if (field == ConfigField.SHULKER_GLOW_COLOR) COLOR_INPUT_WIDTH else TEXT_INPUT_WIDTH
				widget.setRectangle(inputWidth, 20, row.left + 8, row.top + 15)
				setVisible(widget, row.intersects(body))
			} else {
				setVisible(widget, false)
			}
		}

		val slayerSoundDropdownVisible = section == ConfigSection.SLAYER && slayerMiscExpanded
		if ((section == ConfigSection.HIDEONLEAF_HELPER || section == ConfigSection.WORMHOLE_FINDER || section == ConfigSection.PICKAXE_COOLDOWN || section == ConfigSection.FIRE_FREEZE || section == ConfigSection.CHIMERA_DROP || slayerSoundDropdownVisible) && soundDropdownOpen) {
			val search = soundSearchBounds(menu)
			activeSoundSearchField().setRectangle(search.width(), 18, search.left, search.top)
			setVisible(activeSoundSearchField(), search.intersects(body))
		} else {
			setVisible(lostFightSoundSearchField, false)
			setVisible(wormholeSoundSearchField, false)
			setVisible(pickaxeAlertSoundSearchField, false)
			setVisible(fireFreezeAlertSoundSearchField, false)
			setVisible(chimeraDropSoundSearchField, false)
			setVisible(slayerAnnouncerSoundSearchField, false)
		}

		if (section == ConfigSection.MOB_MODEL && mobModelDropdownOpen) {
			val search = mobModelSearchBounds(menu)
			mobModelEntityTypeField.setRectangle(search.width(), 18, search.left, search.top)
			setVisible(mobModelEntityTypeField, search.intersects(body))
		} else {
			setVisible(mobModelEntityTypeField, false)
		}

		if (section == ConfigSection.MOB_MODEL && mobModelVariantDropdownOpen) {
			val search = mobModelVariantSearchBounds(menu)
			mobModelVariantField.setRectangle(search.width(), 18, search.left, search.top)
			setVisible(mobModelVariantField, search.intersects(body))
		} else {
			setVisible(mobModelVariantField, false)
		}

		searchField.setRectangle(SEARCH_WIDTH, 22, (width / 2) - (SEARCH_WIDTH / 2), height - 40)
	}

	private fun drawPanels(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		panelPositions().forEach { position ->
			drawPanel(context, position.panel, position.x, position.y, mouseX, mouseY)
		}
	}

	private fun drawPanel(context: GuiGraphics, panel: ConfigPanel, x: Int, y: Int, mouseX: Int, mouseY: Int) {
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
			val enabled = section.isEnabled(workingCopy)
			val focused = visibleModuleRows().getOrNull(focusedModuleIndex)?.section == section
			context.fill(x, rowY, x + PANEL_WIDTH, rowY + PANEL_ROW_HEIGHT, PANEL_BODY)
			if (section.toggleable && enabled) {
				context.fill(x, rowY, x + PANEL_WIDTH, rowY + PANEL_ROW_HEIGHT, SELECTED)
				context.fill(x, rowY, x + 2, rowY + PANEL_ROW_HEIGHT, ACCENT)
			} else if (!section.toggleable && section == selectedSection) {
				context.fill(x, rowY, x + PANEL_WIDTH, rowY + PANEL_ROW_HEIGHT, XclipsenUiTokens.SURFACE_HOVER)
			}
			if (hovered) {
				context.fill(x, rowY, x + PANEL_WIDTH, rowY + PANEL_ROW_HEIGHT, HOVER)
			}
			if (focused) {
				context.fill(x, rowY, x + 2, rowY + PANEL_ROW_HEIGHT, ACCENT)
			}
			val textColor = if (section.toggleable && !enabled) TEXT_DISABLED else TEXT_PRIMARY
			context.drawCenteredTextWithShadow(textRenderer, section.label, x + PANEL_WIDTH / 2, rowY + 4, textColor)
			rowY += PANEL_ROW_HEIGHT
		}
	}

	private fun drawSearch(context: GuiGraphics) {
		val x = (width / 2) - (SEARCH_WIDTH / 2)
		val y = height - 40
		context.fill(x, y, x + SEARCH_WIDTH, y + 22, XclipsenUiTokens.SURFACE_INPUT)
		context.fill(x, y + 20, x + SEARCH_WIDTH, y + 22, if (searchField.isFocused) ACCENT else XclipsenUiTokens.BORDER_SUBTLE)
	}

	private fun drawSettingsMenu(context: GuiGraphics, mouseX: Int, mouseY: Int) {
		val section = openedSection ?: return
		val menu = settingsBounds()
		context.fill(menu.left, menu.top, menu.right, menu.bottom, POPUP_BACKGROUND)
		context.fill(menu.left, menu.top, menu.right, menu.top + 2, ACCENT)
		context.drawCenteredTextWithShadow(textRenderer, section.label.uppercase(), (menu.left + menu.right) / 2, menu.top + 10, TEXT_WHITE)
		context.fill(menu.left + 10, menu.top + 28, menu.right - 10, menu.top + 29, XclipsenUiTokens.BORDER_SUBTLE)
		val body = settingsBodyBounds(menu)
		val contentMenu = settingsContentBounds()
		focusableSettingRows.clear()
		focusableSettingFields.clear()
		context.enableScissor(body.left, body.top, body.right, body.bottom)

		when (section) {
			ConfigSection.SETUP -> drawSetupSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.IRC_BRIDGE -> drawIrcBridgeSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.CHAT -> drawChatSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.HIDEONLEAF_HELPER -> drawHideonleafHelperSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.PURPLE_TERRACOTTA -> drawPurpleTerracottaSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.FLOOR_DROP_ESP -> drawFloorDropEspSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.WORMHOLE_FINDER -> drawWormholeFinderSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.AUTO_SPRINT -> drawAutoSprintSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.TIME_CHANGER -> drawTimeChangerSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.AUCTION_HOUSE -> drawAuctionHouseSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.SLAYER -> drawSlayerSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.PEST_ESP -> drawPestEspSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.CORPSE_ESP -> drawCorpseEspSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.MOB_MODEL -> drawMobModelSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.CROSSHAIR -> drawCrosshairSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.INVENTORY_PREVIEW -> drawInventoryPreviewSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.SILENT_DISCONNECT, ConfigSection.ITEM_UPDATE_FIX, ConfigSection.DEPLOYBLE -> Unit
			ConfigSection.CHIMERA_DROP -> drawChimeraDropSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.M5 -> drawM5Settings(context, contentMenu, mouseX, mouseY)
			ConfigSection.DUNGEON_AUTOKICK -> drawDungeonAutoKickSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.PICKAXE_COOLDOWN -> drawPickaxeCooldownSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.FIRE_FREEZE -> drawFireFreezeSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.MINESHAFT_AUTOWARP -> drawMineshaftAutoWarpSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.EXPERIMENTS -> drawExperimentationSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.DOOR -> drawDoorSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.RED_VIGNETTE -> drawRedVignetteSettings(context, contentMenu, mouseX, mouseY)
			ConfigSection.STATUS -> drawStatusSettings(context, contentMenu, mouseX, mouseY)
		}
		context.disableScissor()
		focusedSettingIndex = focusedSettingIndex.coerceAtMost(focusableSettingRows.lastIndex)

		if (statusMessage.string.isNotEmpty()) {
			val color = if (statusMessage.string.startsWith("Failed") || statusMessage.string.startsWith("Poll")) TEXT_ERROR else TEXT_SUCCESS
			context.drawCenteredTextWithShadow(textRenderer, statusMessage, (menu.left + menu.right) / 2, menu.bottom - 18, color)
		}
	}

	private fun drawSetupSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawInfoSetting(context, settingRowBounds(menu, 0, TEXT_INPUT_SETTING_HEIGHT), "Mod API", BridgeConfigManager.MOD_BACKEND_BASE_URL, mouseX, mouseY)
	}

	private fun drawFloorDropEspSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, floorDropEspTracerBounds(menu), "Tracer to nearest", workingCopy.floorDropEspTracerEnabled, mouseX, mouseY)
	}

	private fun drawIrcBridgeSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawTextInputSetting(context, menu, 0, "IRC Server URL", ircServerBaseUrlField, mouseX, mouseY)
		drawTextInputSetting(context, menu, 1, "IRC Auth Token", backendAuthTokenField, mouseX, mouseY)
		drawTextInputSetting(context, menu, 2, "Poll Interval (ms)", backendPollIntervalField, mouseX, mouseY)
		drawTextInputSetting(context, menu, 3, "IRC Format", ircFormatField, mouseX, mouseY)
		drawToggleSetting(context, coopRelayToggleBounds(menu), "Co-op Relay", workingCopy.coopChatRelayEnabled, mouseX, mouseY)
		drawButtonSetting(context, ircTestConnectionBounds(menu), "Test IRC Server", mouseX, mouseY)
	}

	private fun drawChatSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, chatImplosionHiderBounds(menu), "Implosion Hider", workingCopy.chatImplosionHiderEnabled, mouseX, mouseY)
	}

	private fun drawHideonleafHelperSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, settingRowBounds(menu, 0, SETTING_HEIGHT), "Shulker Glow", workingCopy.shulkerGlowEnabled, mouseX, mouseY)
		drawColorSetting(context, shulkerGlowColorBounds(menu), "Shulker Color", ConfigField.SHULKER_GLOW_COLOR, mouseX, mouseY)
		drawColorSetting(context, projectileGlowColorBounds(menu), "Projectile Color", ConfigField.SHULKER_PROJECTILE_GLOW_COLOR, mouseX, mouseY)
		drawColorSetting(context, tracerLineColorBounds(menu), "Line Color", ConfigField.SHULKER_TRACER_LINE_COLOR, mouseX, mouseY)
		drawIntSliderSetting(context, tracerLineBounds(menu), "Shulker Line", workingCopy.shulkerTracerLineMode, 0, 3, mouseX, mouseY)
		drawSliderSetting(context, tracerLineWidthBounds(menu), "Line Width", workingCopy.shulkerTracerLineWidth, 1.0f, 8.0f, mouseX, mouseY)
		drawToggleSetting(context, lostFightAlertBounds(menu), "Lost Fight Alert", workingCopy.hideonleafLostFightAlertEnabled, mouseX, mouseY)
		drawToggleSetting(context, shareDataBounds(menu), "Share Data", workingCopy.hideonleafShareDataEnabled, mouseX, mouseY)
		drawToggleSetting(context, shardTrackerBounds(menu), "Shard Tracker", workingCopy.shardTrackerEnabled, mouseX, mouseY)
		drawSoundSetting(context, lostFightSoundBounds(menu), "Alert Sound", workingCopy.hideonleafLostFightAlertSoundId, mouseX, mouseY, workingCopy.hideonleafLostFightAlertEnabled)
		if (soundDropdownOpen && workingCopy.hideonleafLostFightAlertEnabled) {
			drawSoundDropdown(context, menu, mouseX, mouseY)
		}
		drawSliderSetting(context, lostFightVolumeBounds(menu), "Volume", workingCopy.hideonleafLostFightAlertSoundVolume, 0.0f, 2.0f, mouseX, mouseY, workingCopy.hideonleafLostFightAlertEnabled)
		drawSliderSetting(context, lostFightPitchBounds(menu), "Pitch", workingCopy.hideonleafLostFightAlertSoundPitch, 0.1f, 2.0f, mouseX, mouseY, workingCopy.hideonleafLostFightAlertEnabled)
		drawButtonSetting(context, playLostFightSoundBounds(menu), "Play Sound", mouseX, mouseY, workingCopy.hideonleafLostFightAlertEnabled)
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

	private fun drawPurpleTerracottaSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawColorSetting(context, purpleTerracottaColorBounds(menu), "Outline Color", ConfigField.PURPLE_TERRACOTTA_HIGHLIGHT_COLOR, mouseX, mouseY)
		if (colorPickerOpen) {
			drawColorPicker(context, menu, mouseX, mouseY)
		}
	}

	private fun drawWormholeFinderSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, settingRowBounds(menu, 0, SETTING_HEIGHT), "Departure Alert", workingCopy.wormholeDepartureAlertEnabled, mouseX, mouseY)
		drawSoundSetting(context, wormholeSoundBounds(menu), "Alert Sound", workingCopy.wormholeDepartureAlertSoundId, mouseX, mouseY, workingCopy.wormholeDepartureAlertEnabled)
		if (soundDropdownOpen && workingCopy.wormholeDepartureAlertEnabled) drawSoundDropdown(context, menu, mouseX, mouseY)
		drawSliderSetting(context, wormholeVolumeBounds(menu), "Volume", workingCopy.wormholeDepartureAlertSoundVolume, 0.0f, 2.0f, mouseX, mouseY, workingCopy.wormholeDepartureAlertEnabled)
		drawSliderSetting(context, wormholePitchBounds(menu), "Pitch", workingCopy.wormholeDepartureAlertSoundPitch, 0.1f, 2.0f, mouseX, mouseY, workingCopy.wormholeDepartureAlertEnabled)
		drawButtonSetting(context, wormholePreviewBounds(menu), "Preview Alert", mouseX, mouseY, workingCopy.wormholeDepartureAlertEnabled)
	}

	private fun drawAutoSprintSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, settingRowBounds(menu, 0, SETTING_HEIGHT), "Disable Fully Underwater", workingCopy.autoSprintDisableWhenFullySubmerged, mouseX, mouseY)
	}

	private fun drawStatusSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, settingRowBounds(menu, 0, SETTING_HEIGHT), "Check for Updates", workingCopy.checkForUpdatesEnabled, mouseX, mouseY)
		drawToggleSetting(context, settingRowBounds(menu, 1, SETTING_HEIGHT), "Auto-Update", workingCopy.autoUpdateEnabled, mouseX, mouseY, workingCopy.checkForUpdatesEnabled)
		drawButtonSetting(context, updateCheckNowBounds(menu), "Check Now", mouseX, mouseY)
		drawInfoSetting(context, updaterInfoBounds(menu), "Updater", ModUpdateChecker.statusLine(), mouseX, mouseY)
		drawButtonSetting(context, hudEditorBounds(menu), "Open HUD Editor", mouseX, mouseY)
	}

	private fun drawChimeraDropSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawSoundSetting(context, chimeraDropSoundBounds(menu), "Sound", workingCopy.chimeraBookDropEffectsSoundId, mouseX, mouseY)
		if (soundDropdownOpen) {
			drawSoundDropdown(context, menu, mouseX, mouseY)
		}
		drawSliderSetting(context, chimeraDropVolumeBounds(menu), "Volume", workingCopy.chimeraBookDropEffectsSoundVolume, 0.0f, 2.0f, mouseX, mouseY)
		drawSliderSetting(context, chimeraDropPitchBounds(menu), "Pitch", workingCopy.chimeraBookDropEffectsSoundPitch, 0.1f, 2.0f, mouseX, mouseY)
		drawButtonSetting(context, chimeraDropTestBounds(menu), "Test Effect", mouseX, mouseY)
	}

	private fun drawTimeChangerSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawOptionSetting(context, timeChangerModeBounds(menu), "Time", ClientTimeChanger.displayName(workingCopy.timeChangerMode), mouseX, mouseY)
	}

	private fun drawAuctionHouseSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, auctionHouseAutoCopyBounds(menu), "Auto Copy Underbid", workingCopy.auctionHouseAutoCopyUnderbidEnabled, mouseX, mouseY)
		drawToggleSetting(context, highClassDiceTrackerBounds(menu), "High Class Dice Sell Tracker", workingCopy.highClassDiceTrackerEnabled, mouseX, mouseY)
	}

	private fun drawSlayerSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawDisclosureSetting(context, slayerBlazeHeaderBounds(menu), "Blaze", slayerBlazeExpanded, mouseX, mouseY)
		if (slayerBlazeExpanded) {
			drawToggleSetting(context, slayerBlazePhaseDisplayBounds(menu), "Phase Display", workingCopy.slayerBlazePhaseDisplayEnabled, mouseX, mouseY)
			drawToggleSetting(context, slayerBlazeColoredMobsBounds(menu), "Colored Mobs", workingCopy.slayerBlazeColoredMobsEnabled, mouseX, mouseY)
		}

		drawDisclosureSetting(context, slayerMiscHeaderBounds(menu), "Misc", slayerMiscExpanded, mouseX, mouseY)
			if (slayerMiscExpanded) {
				drawToggleSetting(context, slayerRngMeterDisplayBounds(menu), "RNG Meter Display", workingCopy.slayerRngMeterDisplayEnabled, mouseX, mouseY)
				drawToggleSetting(context, slayerRngMeterOptimalRemovalBounds(menu), "Optimal Removal Hint", workingCopy.slayerRngMeterOptimalRemovalEnabled, mouseX, mouseY)
				drawToggleSetting(context, slayerRngMeterCompactModeBounds(menu), "Compact Mode", workingCopy.slayerRngMeterCompactMode, mouseX, mouseY)
				drawToggleSetting(context, slayerRngMeterUseMagicFindBounds(menu), "Use Magic Find", workingCopy.slayerRngMeterUseMagicFind, mouseX, mouseY)
				drawOptionSetting(context, slayerRngMeterMagicFindBounds(menu), "Magic Find", workingCopy.slayerRngMeterMagicFind.coerceIn(0, 900).toString(), mouseX, mouseY)
				drawToggleSetting(context, slayerSpawnAnnouncerBounds(menu), "Spawn Announcer", workingCopy.slayerSpawnAnnouncerEnabled, mouseX, mouseY)
			drawTextInputSetting(context, slayerAnnouncerTextBounds(menu), "Announcer Text", slayerAnnouncerTextField, mouseX, mouseY, workingCopy.slayerSpawnAnnouncerEnabled)
			drawSoundSetting(context, slayerAnnouncerSoundBounds(menu), "Announcer Sound", workingCopy.slayerSpawnAnnouncerSoundId, mouseX, mouseY, workingCopy.slayerSpawnAnnouncerEnabled)
			if (soundDropdownOpen && workingCopy.slayerSpawnAnnouncerEnabled) {
				drawSoundDropdown(context, menu, mouseX, mouseY)
			}
			drawSliderSetting(context, slayerAnnouncerVolumeBounds(menu), "Volume", workingCopy.slayerSpawnAnnouncerSoundVolume, 0.0f, 2.0f, mouseX, mouseY, workingCopy.slayerSpawnAnnouncerEnabled)
			drawSliderSetting(context, slayerAnnouncerPitchBounds(menu), "Pitch", workingCopy.slayerSpawnAnnouncerSoundPitch, 0.1f, 2.0f, mouseX, mouseY, workingCopy.slayerSpawnAnnouncerEnabled)
			drawButtonSetting(context, slayerAnnouncerPreviewBounds(menu), "Preview Announcer", mouseX, mouseY, workingCopy.slayerSpawnAnnouncerEnabled)
		}
	}

	private fun drawPestEspSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, pestEspTracerBounds(menu), "Tracer Line", workingCopy.pestEspTracerEnabled, mouseX, mouseY)
		drawColorSetting(context, pestEspColorBounds(menu), "Highlight Color", ConfigField.PEST_ESP_COLOR, mouseX, mouseY)
		if (colorPickerOpen) {
			drawColorPicker(context, menu, mouseX, mouseY)
		}
	}

	private fun drawCorpseEspSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, corpseEspLapisBounds(menu), "Lapis ESP", workingCopy.corpseEspLapisEnabled, mouseX, mouseY)
		drawToggleSetting(context, corpseEspTungstenBounds(menu), "Tungsten ESP", workingCopy.corpseEspTungstenEnabled, mouseX, mouseY)
		drawToggleSetting(context, corpseEspUmberBounds(menu), "Umber ESP", workingCopy.corpseEspUmberEnabled, mouseX, mouseY)
		drawToggleSetting(context, corpseEspVanguardBounds(menu), "Vanguard ESP", workingCopy.corpseEspVanguardEnabled, mouseX, mouseY)
	}

	private fun drawMobModelSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
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
	}

	private fun drawInventoryPreviewSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, inventoryPreviewShowArmorBounds(menu), "Show Armor", workingCopy.inventoryPreviewShowArmor, mouseX, mouseY)
	}

	private fun drawCrosshairSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, crosshairShowInFirstPersonBounds(menu), "Custom Crosshair", workingCopy.customCrosshairShowInFirstPerson, mouseX, mouseY)
		drawToggleSetting(context, crosshairVisibleInF5Bounds(menu), "Visible In F5", workingCopy.customCrosshairVisibleInF5, mouseX, mouseY)
		drawButtonSetting(context, crosshairResetBounds(menu), "Reset Grid", mouseX, mouseY)
		drawCrosshairGridSetting(context, crosshairGridBounds(menu), mouseX, mouseY)
	}

	private fun drawM5Settings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, m5LividFinderBounds(menu), "Livid Finder", workingCopy.m5LividFinderEnabled, mouseX, mouseY)
		drawToggleSetting(context, m5TracerBounds(menu), "Tracer", workingCopy.m5TracerEnabled, mouseX, mouseY)
		drawToggleSetting(context, m5IceSprayBounds(menu), "Ice Spray Timer", workingCopy.m5IceSprayTimerEnabled, mouseX, mouseY)
		drawToggleSetting(context, m5RagAxeBounds(menu), "Rag Axe Alert", workingCopy.m5RagAxeAlertEnabled, mouseX, mouseY)
	}

	private fun drawDungeonAutoKickSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
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
	}

	private fun drawPickaxeCooldownSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, pickaxeShowReadyBounds(menu), "Show When Ready", workingCopy.pickaxeAbilityCooldownShowReady, mouseX, mouseY)
		drawToggleSetting(context, pickobulusHelperBounds(menu), "Pickobulus Helper", workingCopy.pickobulusHelperModuleEnabled, mouseX, mouseY)
		drawOptionSetting(context, pickaxeAlertDisclosureBounds(menu), "Alert", if (pickaxeAlertExpanded) "Expanded" else "Collapsed", mouseX, mouseY)
		if (pickaxeAlertExpanded) {
			drawToggleSetting(context, pickaxeAlertEnabledBounds(menu), "Enable Alert", workingCopy.pickaxeAbilityCooldownAlertEnabled, mouseX, mouseY)
			drawTextInputSetting(context, pickaxeAlertTextBounds(menu), "Alert Text", pickaxeAlertTextField, mouseX, mouseY, workingCopy.pickaxeAbilityCooldownAlertEnabled)
			drawSoundSetting(context, pickaxeAlertSoundBounds(menu), "Alert Sound", workingCopy.pickaxeAbilityCooldownAlertSoundId, mouseX, mouseY, workingCopy.pickaxeAbilityCooldownAlertEnabled)
			if (soundDropdownOpen && workingCopy.pickaxeAbilityCooldownAlertEnabled) {
				drawSoundDropdown(context, menu, mouseX, mouseY)
			}
			drawSliderSetting(context, pickaxeAlertVolumeBounds(menu), "Volume", workingCopy.pickaxeAbilityCooldownAlertSoundVolume, 0.0f, 2.0f, mouseX, mouseY, workingCopy.pickaxeAbilityCooldownAlertEnabled)
			drawSliderSetting(context, pickaxeAlertPitchBounds(menu), "Pitch", workingCopy.pickaxeAbilityCooldownAlertSoundPitch, 0.1f, 2.0f, mouseX, mouseY, workingCopy.pickaxeAbilityCooldownAlertEnabled)
			drawButtonSetting(context, pickaxeAlertPreviewBounds(menu), "Preview Alert", mouseX, mouseY, workingCopy.pickaxeAbilityCooldownAlertEnabled)
		}
	}

	private fun drawFireFreezeSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, fireFreezeMobTimerBounds(menu), "Mob Timer", workingCopy.fireFreezeMobTimerEnabled, mouseX, mouseY)
		drawToggleSetting(context, fireFreezeFreezeTimerBounds(menu), "Freeze Timer", workingCopy.fireFreezeFreezeTimerEnabled, mouseX, mouseY)
		drawToggleSetting(context, fireFreezeStrongMobsOnlyBounds(menu), "Strong Mobs Only", workingCopy.fireFreezeStrongMobsOnly, mouseX, mouseY)
		drawToggleSetting(context, fireFreezeBoxBounds(menu), "Box Frozen Mobs", workingCopy.fireFreezeBoxFrozenMobsEnabled, mouseX, mouseY)
		drawToggleSetting(context, fireFreezeCustomCircleBounds(menu), "Custom Circle", workingCopy.fireFreezeCustomCircleEnabled, mouseX, mouseY)
		drawColorSetting(context, fireFreezeCircleColorBounds(menu), "Circle Color", ConfigField.FIRE_FREEZE_CIRCLE_COLOR, mouseX, mouseY)
		drawSliderSetting(context, fireFreezeLineWidthBounds(menu), "Radius Thickness", workingCopy.fireFreezeCircleLineWidth, 1.0f, 8.0f, mouseX, mouseY)
		drawToggleSetting(context, fireFreezeRefreezeAlertBounds(menu), "Refreeze Alert", workingCopy.fireFreezeRefreezeAlertEnabled, mouseX, mouseY)
		drawSoundSetting(context, fireFreezeAlertSoundBounds(menu), "Alert Sound", workingCopy.fireFreezeRefreezeAlertSoundId, mouseX, mouseY, workingCopy.fireFreezeRefreezeAlertEnabled)
		if (soundDropdownOpen && workingCopy.fireFreezeRefreezeAlertEnabled) {
			drawSoundDropdown(context, menu, mouseX, mouseY)
		}
		drawSliderSetting(context, fireFreezeAlertVolumeBounds(menu), "Volume", workingCopy.fireFreezeRefreezeAlertSoundVolume, 0.0f, 2.0f, mouseX, mouseY, workingCopy.fireFreezeRefreezeAlertEnabled)
		drawSliderSetting(context, fireFreezeAlertPitchBounds(menu), "Pitch", workingCopy.fireFreezeRefreezeAlertSoundPitch, 0.1f, 2.0f, mouseX, mouseY, workingCopy.fireFreezeRefreezeAlertEnabled)
		drawButtonSetting(context, fireFreezeAlertPreviewBounds(menu), "Preview Alert", mouseX, mouseY, workingCopy.fireFreezeRefreezeAlertEnabled)
		if (colorPickerOpen) {
			drawColorPicker(context, menu, mouseX, mouseY)
		}
	}

	private fun drawMineshaftAutoWarpSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawTextInputSetting(context, mineshaftAutoWarpRuleBounds(menu), "Corpse Rule", mineshaftAutoWarpRuleField, mouseX, mouseY)
		drawTextInputSetting(context, mineshaftAutoWarpDelayBounds(menu), "Warp Delay (ms)", mineshaftAutoWarpDelayField, mouseX, mouseY)
		drawTextInputSetting(context, mineshaftAutoWarpWindowBounds(menu), "Warp Window (ms)", mineshaftAutoWarpWindowField, mouseX, mouseY)
	}

	private fun drawExperimentationSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, autoExperimentsAutoPairsBounds(menu), "Keep Items Visible", workingCopy.autoExperimentsAutoPairs, mouseX, mouseY)
		drawToggleSetting(context, autoExperimentsAutoCloseBounds(menu), "Auto Close", workingCopy.autoExperimentsAutoClose, mouseX, mouseY)
		drawToggleSetting(context, autoExperimentsGetMaxXpBounds(menu), "Get Max XP", workingCopy.autoExperimentsGetMaxXp, mouseX, mouseY)
		drawTextInputSetting(context, autoExperimentsClickDelayBounds(menu), "Click Delay (ms)", autoExperimentsClickDelayField, mouseX, mouseY)
		drawTextInputSetting(context, autoExperimentsDelayVarietyBounds(menu), "Delay Variety (ms)", autoExperimentsDelayVarietyField, mouseX, mouseY)
		drawTextInputSetting(context, autoExperimentsSerumCountBounds(menu), "Serum Count", autoExperimentsSerumCountField, mouseX, mouseY)
	}

	private fun drawDoorSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, settingRowBounds(menu, 0, SETTING_HEIGHT), "Mort Door Barrier", workingCopy.dungeonDoorEnabled, mouseX, mouseY)
		drawToggleSetting(context, settingRowBounds(menu, 1, SETTING_HEIGHT), "Debug", workingCopy.dungeonDoorDebugEnabled, mouseX, mouseY)
		drawOptionSetting(context, settingRowBounds(menu, 2, SETTING_HEIGHT), "Mode", MortDoorBarrierFeature.displayName(workingCopy.dungeonDoorMode), mouseX, mouseY)
	}

	private fun drawRedVignetteSettings(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawToggleSetting(context, settingRowBounds(menu, 0, SETTING_HEIGHT), "Fix Red Vignette", workingCopy.dungeonRedVignetteEnabled, mouseX, mouseY)
	}

	private fun drawTextInputSetting(
		context: GuiGraphics,
		row: Bounds,
		label: String,
		field: EditBox,
		mouseX: Int,
		mouseY: Int,
		enabled: Boolean = true,
	) {
		val hovered = enabled && (row.contains(mouseX, mouseY) || field.isFocused)
		drawSettingBackground(context, row, hovered, enabled, interactive = true)
		if (enabled && focusableSettingFields.isNotEmpty()) focusableSettingFields[focusableSettingFields.lastIndex] = field
		field.setEditable(enabled)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 4, if (enabled) TEXT_WHITE else TEXT_DISABLED)
		context.fill(row.left + 8, row.top + 15, row.right - 8, row.top + 35, INPUT_BACKGROUND)
		context.fill(
			row.left + 8,
			row.top + 34,
			row.right - 8,
			row.top + 35,
			if (field.isFocused && enabled) ACCENT else XclipsenUiTokens.BORDER_SUBTLE,
		)
	}

	private fun drawTextInputSetting(
		context: GuiGraphics,
		menu: Bounds,
		rowIndex: Int,
		label: String,
		field: EditBox,
		mouseX: Int,
		mouseY: Int,
		enabled: Boolean = true,
	) {
		drawTextInputSetting(context, settingRowBounds(menu, rowIndex, TEXT_INPUT_SETTING_HEIGHT), label, field, mouseX, mouseY, enabled)
	}

	private fun drawToggleSetting(context: GuiGraphics, row: Bounds, label: String, value: Boolean, mouseX: Int, mouseY: Int, enabled: Boolean = true) {
		val hovered = enabled && row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered, enabled, interactive = true)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 6, if (enabled) TEXT_WHITE else TEXT_DISABLED)

		val switchWidth = 18
		val switchHeight = 6
		val switchX = row.right - switchWidth - 10
		val switchY = row.top + (SETTING_HEIGHT / 2) - (switchHeight / 2)
		context.fill(switchX, switchY, switchX + switchWidth, switchY + switchHeight, if (value && enabled) ACCENT_TRANS else XclipsenUiTokens.TEXT_DISABLED)

		val knobX = switchX + if (value) switchWidth - 8 else 0
		context.fill(knobX, switchY - 1, knobX + 8, switchY + 7, if (value && enabled) ACCENT else TEXT_DISABLED)
	}

	private fun drawSliderSetting(
		context: GuiGraphics,
		row: Bounds,
		label: String,
		value: Float,
		min: Float,
		max: Float,
		mouseX: Int,
		mouseY: Int,
		enabled: Boolean = true,
	) {
		val hovered = enabled && row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered, enabled, interactive = true)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 4, if (enabled) TEXT_WHITE else TEXT_DISABLED)
		context.drawTextWithShadow(textRenderer, String.format(Locale.ROOT, "%.2f", value), row.right - 42, row.top + 4, TEXT_MUTED)

		val barLeft = row.left + 8
		val barRight = row.right - 8
		val barY = row.bottom - 7
		val progress = ((value - min) / (max - min)).coerceIn(0.0f, 1.0f)
		val fillRight = barLeft + ((barRight - barLeft) * progress).toInt()
		context.fill(barLeft, barY, barRight, barY + 3, XclipsenUiTokens.TEXT_DISABLED)
		context.fill(barLeft, barY, fillRight, barY + 3, if (enabled) ACCENT else TEXT_DISABLED)
		context.fill(fillRight - 2, barY - 2, fillRight + 2, barY + 5, if (enabled) TEXT_WHITE else TEXT_DISABLED)
	}

	private fun drawIntSliderSetting(
		context: GuiGraphics,
		row: Bounds,
		label: String,
		value: Int,
		min: Int,
		max: Int,
		mouseX: Int,
		mouseY: Int,
	) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered, interactive = true)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 4, TEXT_WHITE)
		context.drawTextWithShadow(textRenderer, value.coerceIn(min, max).toString(), row.right - 18, row.top + 4, TEXT_MUTED)

		val barLeft = row.left + 8
		val barRight = row.right - 8
		val barY = row.bottom - 7
		val progress = ((value.coerceIn(min, max) - min).toFloat() / (max - min).coerceAtLeast(1)).coerceIn(0.0f, 1.0f)
		val fillRight = barLeft + ((barRight - barLeft) * progress).toInt()
		context.fill(barLeft, barY, barRight, barY + 3, ClientColor.argb(TEXT_DISABLED, 120))
		context.fill(barLeft, barY, fillRight, barY + 3, ACCENT)
		context.fill(fillRight - 2, barY - 2, fillRight + 2, barY + 5, TEXT_WHITE)
	}

	private fun drawSoundSetting(context: GuiGraphics, row: Bounds, label: String, soundId: String, mouseX: Int, mouseY: Int, enabled: Boolean = true) {
		val hovered = enabled && row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered || (soundDropdownOpen && enabled), enabled, interactive = true)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 4, if (enabled) TEXT_WHITE else TEXT_DISABLED)
		context.drawTextWithShadow(textRenderer, trimToWidth(SoundCatalog.displayName(soundId), 92), row.right - 100, row.top + 4, TEXT_MUTED)
		if (soundDropdownOpen) {
			context.fill(row.left, row.bottom - 1, row.right, row.bottom, ACCENT)
		}
	}

	private fun drawSoundDropdown(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		val searchField = activeSoundSearchField()
		val selectedSoundId = activeSelectedSoundId()
		val search = soundSearchBounds(menu)
		val list = soundListBounds(menu)
		context.fill(search.left - 4, search.top - 4, search.right + 4, list.bottom + 4, INPUT_BACKGROUND)
		context.fill(search.left, search.top, search.right, search.bottom, INPUT_BACKGROUND)
		context.fill(search.left, search.bottom - 1, search.right, search.bottom, if (searchField.isFocused) ACCENT else XclipsenUiTokens.BORDER_SUBTLE)

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

	private fun drawMobModelSetting(context: GuiGraphics, row: Bounds, mouseX: Int, mouseY: Int) {
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

	private fun drawMobModelDropdown(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		val search = mobModelSearchBounds(menu)
		val list = mobModelListBounds(menu)
		context.fill(search.left - 4, search.top - 4, search.right + 4, list.bottom + 4, INPUT_BACKGROUND)
		context.fill(search.left, search.top, search.right, search.bottom, INPUT_BACKGROUND)
		context.fill(search.left, search.bottom - 1, search.right, search.bottom, if (mobModelEntityTypeField.isFocused) ACCENT else XclipsenUiTokens.BORDER_SUBTLE)

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

	private fun drawMobModelVariantSetting(context: GuiGraphics, row: Bounds, mouseX: Int, mouseY: Int) {
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

	private fun drawMobModelVariantDropdown(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		val search = mobModelVariantSearchBounds(menu)
		val list = mobModelVariantListBounds(menu)
		context.fill(search.left - 4, search.top - 4, search.right + 4, list.bottom + 4, INPUT_BACKGROUND)
		context.fill(search.left, search.top, search.right, search.bottom, INPUT_BACKGROUND)
		context.fill(search.left, search.bottom - 1, search.right, search.bottom, if (mobModelVariantField.isFocused) ACCENT else XclipsenUiTokens.BORDER_SUBTLE)

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

	private fun drawDungeonAutoKickFloorDropdown(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
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

	private fun drawButtonSetting(context: GuiGraphics, row: Bounds, label: String, mouseX: Int, mouseY: Int, enabled: Boolean = true) {
		val hovered = enabled && row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered, enabled, interactive = true)
		if (hovered) {
			context.fill(row.left, row.top, row.right, row.bottom, ClientColor.argb(TEXT_WHITE, 10))
		}
		context.drawCenteredTextWithShadow(textRenderer, label, (row.left + row.right) / 2, row.top + 6, if (enabled) TEXT_WHITE else TEXT_DISABLED)
		if (hovered) {
			val lineWidth = textRenderer.width(label) + 10
			val lineLeft = ((row.left + row.right) / 2) - (lineWidth / 2)
			context.fill(lineLeft, row.top + 16, lineLeft + lineWidth, row.top + 17, ACCENT)
		}
	}

	private fun drawDisclosureSetting(context: GuiGraphics, row: Bounds, label: String, expanded: Boolean, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered, interactive = true)
		context.fill(row.left, row.top, row.left + 3, row.bottom, ACCENT)
		val marker = if (expanded) "v" else ">"
		context.drawTextWithShadow(textRenderer, marker, row.left + 10, row.top + 6, ACCENT)
		context.drawTextWithShadow(textRenderer, label, row.left + 24 + if (hovered) 2 else 0, row.top + 6, TEXT_WHITE)
	}

	private fun drawInfoSetting(context: GuiGraphics, row: Bounds, label: String, value: String, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 4, TEXT_WHITE)
		context.drawTextWithShadow(textRenderer, trimToWidth(value, TEXT_INPUT_WIDTH), row.left + 8, row.top + 20, TEXT_MUTED)
	}

	private fun drawCrosshairGridSetting(context: GuiGraphics, row: Bounds, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered, interactive = true)
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
					else -> SETTING_BACKGROUND
				}
				context.fill(cell.left, cell.top, cell.right, cell.bottom, fillColor)
				context.fill(cell.left, cell.top, cell.right, cell.top + 1, XclipsenUiTokens.BORDER_SUBTLE)
				context.fill(cell.left, cell.bottom - 1, cell.right, cell.bottom, XclipsenUiTokens.BORDER_SUBTLE)
				context.fill(cell.left, cell.top, cell.left + 1, cell.bottom, XclipsenUiTokens.BORDER_SUBTLE)
				context.fill(cell.right - 1, cell.top, cell.right, cell.bottom, XclipsenUiTokens.BORDER_SUBTLE)
			}
		}
	}

	private fun drawOptionSetting(context: GuiGraphics, row: Bounds, label: String, value: String, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered, interactive = true)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 6, TEXT_WHITE)
		context.drawTextWithShadow(textRenderer, trimToWidth(value, 110), row.right - 118, row.top + 6, TEXT_MUTED)
		if (hovered) {
			context.fill(row.right - 14, row.top + 7, row.right - 9, row.top + 12, ACCENT)
		}
	}

	private fun drawColorSetting(context: GuiGraphics, row: Bounds, label: String, field: ConfigField, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		val active = openColorField == field
		drawSettingBackground(context, row, hovered || active, interactive = true)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 6, TEXT_WHITE)
		drawColorPreview(context, row, colorFieldWidget(field).text)
		if (active) {
			context.fill(row.left, row.bottom - 1, row.right, row.bottom, ACCENT)
		}
	}

	private fun colorFieldWidget(field: ConfigField): EditBox {
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

	private fun drawColorPreview(context: GuiGraphics, row: Bounds, hex: String) {
		val color = ClientColor.parseRgb(hex) ?: return
		val swatchRight = row.right - 10
		val swatchLeft = swatchRight - 14
		val swatchTop = row.top + 4
		context.fill(swatchLeft - 1, swatchTop - 1, swatchRight + 1, swatchTop + 15, TEXT_WHITE)
		context.fill(swatchLeft, swatchTop, swatchRight, swatchTop + 14, ClientColor.argb(color, 255))
	}

	private fun drawColorPicker(context: GuiGraphics, menu: Bounds, mouseX: Int, mouseY: Int) {
		val field = openColorField ?: return
		val widget = colorFieldWidget(field)
		val currentColor = ClientColor.parseRgb(widget.text) ?: DEFAULT_GLOW_COLOR
		val hsb = ClientColor.rgbToHsb(currentColor)
		val hue = hsb.hue
		val saturation = hsb.saturation
		val brightness = hsb.brightness

		context.drawCenteredTextWithShadow(textRenderer, "Colors", (menu.left + menu.right) / 2, colorTitleY(menu), ACCENT)

		val sv = colorSvBounds(menu)
		drawSaturationBrightnessBox(context, sv, hue)
		drawPickerCursor(context, sv.left + (saturation * sv.width()).toInt(), sv.top + ((1f - brightness) * sv.height()).toInt(), 4)

		val hueBar = colorHueBounds(menu)
		drawHueBar(context, hueBar)
		val hueY = hueBar.top + (hue * hueBar.height()).toInt()
		context.fill(hueBar.left - 2, hueY - 2, hueBar.right + 2, hueY + 3, TEXT_WHITE)
		context.fill(hueBar.left - 1, hueY - 1, hueBar.right + 1, hueY + 2, PANEL_HEADER)

		context.drawTextWithShadow(textRenderer, "Hex: ${normalizedColor(widget.text) ?: "#36C5F0"}", menu.left + 18, colorHexY(menu), TEXT_WHITE)
	}

	private fun drawSaturationBrightnessBox(context: GuiGraphics, bounds: Bounds, hue: Float) {
		val baseColor = ClientColor.hsbToRgb(hue, 1f, 1f)
		val width = bounds.width().coerceAtLeast(1)
		val height = bounds.height().coerceAtLeast(1)

		for (xOffset in 0 until width step COLOR_PICKER_STEP) {
			val saturation = xOffset.toFloat() / (width - 1).coerceAtLeast(1)
			for (yOffset in 0 until height step COLOR_PICKER_STEP) {
				val brightness = 1f - (yOffset.toFloat() / (height - 1).coerceAtLeast(1))
				val color = ClientColor.hsbToRgb(hue, saturation, brightness)
				context.fill(
					bounds.left + xOffset,
					bounds.top + yOffset,
					(bounds.left + xOffset + COLOR_PICKER_STEP).coerceAtMost(bounds.right),
					(bounds.top + yOffset + COLOR_PICKER_STEP).coerceAtMost(bounds.bottom),
					ClientColor.argb(color, 255),
				)
			}
		}

		context.fill(bounds.left, bounds.top, bounds.right, bounds.top + 1, XclipsenUiTokens.BORDER_SUBTLE)
		context.fill(bounds.left, bounds.bottom - 1, bounds.right, bounds.bottom, XclipsenUiTokens.SURFACE_EDITOR_OVERLAY)
		context.fill(bounds.left, bounds.top, bounds.left + 1, bounds.bottom, XclipsenUiTokens.BORDER_SUBTLE)
		context.fill(bounds.right - 1, bounds.top, bounds.right, bounds.bottom, XclipsenUiTokens.SURFACE_EDITOR_OVERLAY)
		context.fill(bounds.right - 14, bounds.top + 4, bounds.right - 4, bounds.top + 14, ClientColor.argb(baseColor, 255))
	}

	private fun drawHueBar(context: GuiGraphics, bounds: Bounds) {
		val height = bounds.height().coerceAtLeast(1)
		for (yOffset in 0 until height step COLOR_PICKER_STEP) {
			val hue = yOffset.toFloat() / (height - 1).coerceAtLeast(1)
			val color = ClientColor.hsbToRgb(hue, 1f, 1f)
			context.fill(
				bounds.left,
				bounds.top + yOffset,
				bounds.right,
				(bounds.top + yOffset + COLOR_PICKER_STEP).coerceAtMost(bounds.bottom),
				ClientColor.argb(color, 255),
			)
		}
	}

	private fun drawPickerCursor(context: GuiGraphics, centerX: Int, centerY: Int, radius: Int) {
		context.fill(centerX - radius, centerY - radius, centerX + radius + 1, centerY - radius + 1, TEXT_WHITE)
		context.fill(centerX - radius, centerY + radius, centerX + radius + 1, centerY + radius + 1, TEXT_WHITE)
		context.fill(centerX - radius, centerY - radius, centerX - radius + 1, centerY + radius + 1, TEXT_WHITE)
		context.fill(centerX + radius, centerY - radius, centerX + radius + 1, centerY + radius + 1, TEXT_WHITE)
		context.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, PANEL_HEADER)
	}

	private fun drawSettingBackground(context: GuiGraphics, row: Bounds, hovered: Boolean, enabled: Boolean = true, interactive: Boolean = false) {
		context.fill(row.left, row.top, row.right, row.bottom, SETTING_BACKGROUND)
		if (!enabled) {
			context.fill(row.left, row.top, row.right, row.bottom, XclipsenUiTokens.SURFACE_EDITOR_OVERLAY)
		}
		if (interactive && enabled) {
			focusableSettingRows += row
			focusableSettingFields += null
			if (focusableSettingRows.lastIndex == focusedSettingIndex) {
				context.fill(row.left, row.top, row.left + 2, row.bottom, ACCENT)
			}
		}
		if (hovered) {
			context.fill(row.left, row.top + 3, row.left + 2, row.bottom - 3, ACCENT)
		}
	}

	private fun drawTooltip(context: GuiGraphics, mouseX: Int, mouseY: Int) {
		if (openedSection != null) {
			return
		}
		val section = sectionAt(mouseX, mouseY) ?: return
		context.drawTooltip(textRenderer, Component.literal(section.description), mouseX, mouseY)
	}

	private fun sectionAt(mouseX: Int, mouseY: Int): ConfigSection? {
		panelPositions().forEach { position ->
			val result = sectionAt(position.panel, position.x, position.y, mouseX, mouseY)
			if (result != null) {
				return result
			}
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
		val menuWidth = POPUP_WIDTH.coerceAtMost((width - (XclipsenUiTokens.SCREEN_MARGIN * 2)).coerceAtLeast(1))
		val targetHeight = settingsContentHeight()
		val menuHeight = targetHeight.coerceAtMost((height - (XclipsenUiTokens.SCREEN_MARGIN * 2)).coerceAtLeast(1))
		val left = (width / 2) - (menuWidth / 2)
		val top = (height / 2) - (menuHeight / 2)
		return Bounds(left, top, left + menuWidth, top + menuHeight)
	}

	private fun settingsContentHeight(): Int {
		return when (openedSection) {
			ConfigSection.SETUP -> SETUP_POPUP_HEIGHT
			ConfigSection.IRC_BRIDGE -> IRC_POPUP_HEIGHT
			ConfigSection.CHAT -> CHAT_POPUP_HEIGHT
			ConfigSection.HIDEONLEAF_HELPER -> HIDEONLEAF_POPUP_HEIGHT
			ConfigSection.PURPLE_TERRACOTTA -> PURPLE_TERRACOTTA_POPUP_HEIGHT
			ConfigSection.FLOOR_DROP_ESP -> 120
			ConfigSection.WORMHOLE_FINDER -> if (soundDropdownOpen) WORMHOLE_POPUP_WITH_DROPDOWN_HEIGHT else WORMHOLE_POPUP_HEIGHT
			ConfigSection.AUTO_SPRINT -> 160
			ConfigSection.TIME_CHANGER -> TIME_CHANGER_POPUP_HEIGHT
			ConfigSection.AUCTION_HOUSE -> AUCTION_HOUSE_POPUP_HEIGHT
			ConfigSection.SLAYER -> slayerPopupHeight()
			ConfigSection.PEST_ESP -> PEST_ESP_POPUP_HEIGHT
			ConfigSection.CORPSE_ESP -> CORPSE_ESP_POPUP_HEIGHT
			ConfigSection.MOB_MODEL -> mobModelPopupHeight()
			ConfigSection.CROSSHAIR -> CROSSHAIR_POPUP_HEIGHT
			ConfigSection.INVENTORY_PREVIEW -> INVENTORY_PREVIEW_POPUP_HEIGHT
			ConfigSection.SILENT_DISCONNECT -> POPUP_HEIGHT
			ConfigSection.CHIMERA_DROP -> if (soundDropdownOpen) CHIMERA_DROP_POPUP_WITH_DROPDOWN_HEIGHT else CHIMERA_DROP_POPUP_HEIGHT
			ConfigSection.M5 -> M5_POPUP_HEIGHT
			ConfigSection.DUNGEON_AUTOKICK -> if (dungeonAutoKickFloorDropdownOpen) DUNGEON_AUTOKICK_POPUP_WITH_DROPDOWN_HEIGHT else DUNGEON_AUTOKICK_POPUP_HEIGHT
			ConfigSection.PICKAXE_COOLDOWN -> pickaxeCooldownPopupHeight()
			ConfigSection.FIRE_FREEZE -> if (soundDropdownOpen) FIRE_FREEZE_POPUP_WITH_DROPDOWN_HEIGHT else FIRE_FREEZE_POPUP_HEIGHT
			ConfigSection.MINESHAFT_AUTOWARP -> MINESHAFT_AUTOWARP_POPUP_HEIGHT
			ConfigSection.DEPLOYBLE -> POPUP_HEIGHT
			ConfigSection.EXPERIMENTS -> 340
			ConfigSection.DOOR -> 135
			ConfigSection.RED_VIGNETTE -> 100
			ConfigSection.STATUS -> STATUS_POPUP_HEIGHT
			else -> POPUP_HEIGHT
		}
	}

	private fun settingsBodyBounds(menu: Bounds): Bounds {
		val top = (menu.top + SETTINGS_HEADER_HEIGHT).coerceAtMost(menu.bottom)
		return Bounds(menu.left, top, menu.right, (menu.bottom - SETTINGS_FOOTER_HEIGHT).coerceAtLeast(top))
	}

	private fun settingsContentBounds(): Bounds {
		val menu = settingsBounds()
		return Bounds(menu.left, menu.top - settingsScrollOffset, menu.right, menu.top - settingsScrollOffset + settingsContentHeight())
	}

	private fun maxSettingsScroll(): Int {
		return (settingsContentHeight() - settingsBounds().height() + SETTINGS_FOOTER_HEIGHT).coerceAtLeast(0)
	}

	private fun panelPositions(): List<PanelPosition> {
		val gap = XclipsenUiTokens.SPACING_LG
		val availableWidth = (width - (XclipsenUiTokens.SCREEN_MARGIN * 2)).coerceAtLeast(PANEL_WIDTH)
		val columns = ((availableWidth + gap) / (PANEL_WIDTH + gap)).coerceIn(1, sectionRows.size)
		return sectionRows.mapIndexed { index, panel ->
			val column = index % columns
			val row = index / columns
			val previousHeight = (0 until row).sumOf { previousRow ->
				(sectionRows.drop(previousRow * columns).take(columns).maxOfOrNull { it.sections.size * PANEL_ROW_HEIGHT + PANEL_HEADER_HEIGHT } ?: 0) + gap
			}
			PanelPosition(panel, XclipsenUiTokens.SCREEN_MARGIN + column * (PANEL_WIDTH + gap), XclipsenUiTokens.SCREEN_MARGIN + previousHeight)
		}
	}

	private fun visibleModuleRows(): List<ModuleRow> = panelPositions().flatMap { position ->
		filteredSections(position.panel.sections).mapIndexed { index, section ->
			ModuleRow(section, Bounds(position.x, position.y + PANEL_HEADER_HEIGHT + index * PANEL_ROW_HEIGHT, position.x + PANEL_WIDTH, position.y + PANEL_HEADER_HEIGHT + (index + 1) * PANEL_ROW_HEIGHT))
		}
	}

	private fun activateModule(section: ConfigSection): Boolean {
		selectedSection = section
		return if (section.toggleable) {
			section.toggle(workingCopy)
			saveModuleState()
			true
		} else {
			openSection(section)
			layoutWidgets()
			true
		}
	}

	private fun cycleIndex(current: Int, size: Int, backwards: Boolean): Int {
		return if (backwards) (current - 1 + size) % size else (current + 1) % size
	}

	private fun ensureFocusedSettingVisible() {
		val row = focusableSettingRows.getOrNull(focusedSettingIndex) ?: return
		val body = settingsBodyBounds(settingsBounds())
		settingsScrollOffset = when {
			row.top < body.top -> (settingsScrollOffset - (body.top - row.top)).coerceAtLeast(0)
			row.bottom > body.bottom -> (settingsScrollOffset + row.bottom - body.bottom).coerceAtMost(maxSettingsScroll())
			else -> settingsScrollOffset
		}
		layoutWidgets()
	}

	private fun adjustFocusedSetting(direction: Int): Boolean {
		val row = focusableSettingRows.getOrNull(focusedSettingIndex) ?: return false
		val menu = settingsContentBounds()
		val target = when {
			row == tracerLineBounds(menu) -> SliderDragTarget.LINE_MODE
			row == tracerLineWidthBounds(menu) -> SliderDragTarget.LINE_WIDTH
			row == mobModelScaleBounds(menu) -> SliderDragTarget.MOB_MODEL_SCALE
			workingCopy.hideonleafLostFightAlertEnabled && row == lostFightVolumeBounds(menu) -> SliderDragTarget.ALERT_VOLUME
			workingCopy.hideonleafLostFightAlertEnabled && row == lostFightPitchBounds(menu) -> SliderDragTarget.ALERT_PITCH
			workingCopy.wormholeDepartureAlertEnabled && row == wormholeVolumeBounds(menu) -> SliderDragTarget.WORMHOLE_VOLUME
			workingCopy.wormholeDepartureAlertEnabled && row == wormholePitchBounds(menu) -> SliderDragTarget.WORMHOLE_PITCH
			workingCopy.pickaxeAbilityCooldownAlertEnabled && row == pickaxeAlertVolumeBounds(menu) -> SliderDragTarget.PICKAXE_ALERT_VOLUME
			workingCopy.pickaxeAbilityCooldownAlertEnabled && row == pickaxeAlertPitchBounds(menu) -> SliderDragTarget.PICKAXE_ALERT_PITCH
			row == chimeraDropVolumeBounds(menu) -> SliderDragTarget.CHIMERA_DROP_VOLUME
			row == chimeraDropPitchBounds(menu) -> SliderDragTarget.CHIMERA_DROP_PITCH
			row == fireFreezeLineWidthBounds(menu) -> SliderDragTarget.FIRE_FREEZE_LINE_WIDTH
			workingCopy.fireFreezeRefreezeAlertEnabled && row == fireFreezeAlertVolumeBounds(menu) -> SliderDragTarget.FIRE_FREEZE_ALERT_VOLUME
			workingCopy.fireFreezeRefreezeAlertEnabled && row == fireFreezeAlertPitchBounds(menu) -> SliderDragTarget.FIRE_FREEZE_ALERT_PITCH
			workingCopy.slayerSpawnAnnouncerEnabled && row == slayerAnnouncerVolumeBounds(menu) -> SliderDragTarget.SLAYER_ANNOUNCER_VOLUME
			workingCopy.slayerSpawnAnnouncerEnabled && row == slayerAnnouncerPitchBounds(menu) -> SliderDragTarget.SLAYER_ANNOUNCER_PITCH
			else -> null
		}
		if (target != null) {
			adjustSlider(target, direction)
			return true
		}
		return when {
			row == timeChangerModeBounds(menu) -> {
				workingCopy.timeChangerMode = (workingCopy.timeChangerMode + direction + ClientTimeChanger.modeCount) % ClientTimeChanger.modeCount
				true
			}
			openedSection == ConfigSection.DOOR && row == settingRowBounds(menu, 2, SETTING_HEIGHT) -> {
				workingCopy.dungeonDoorMode = (workingCopy.dungeonDoorMode + direction + MortDoorBarrierFeature.modeCount) % MortDoorBarrierFeature.modeCount
				true
			}
			else -> false
		}
	}

	private fun adjustSlider(target: SliderDragTarget, direction: Int) {
		val (value, min, max, step) = when (target) {
			SliderDragTarget.LINE_MODE -> SliderKeyboardValue(workingCopy.shulkerTracerLineMode.toFloat(), 0.0f, 3.0f, 1.0f)
			SliderDragTarget.LINE_WIDTH -> SliderKeyboardValue(workingCopy.shulkerTracerLineWidth, 1.0f, 8.0f, 0.1f)
			SliderDragTarget.MOB_MODEL_SCALE -> SliderKeyboardValue(workingCopy.mobModelScale, 0.25f, 4.0f, 0.05f)
			SliderDragTarget.ALERT_VOLUME -> SliderKeyboardValue(workingCopy.hideonleafLostFightAlertSoundVolume, 0.0f, 2.0f, 0.05f)
			SliderDragTarget.ALERT_PITCH -> SliderKeyboardValue(workingCopy.hideonleafLostFightAlertSoundPitch, 0.1f, 2.0f, 0.05f)
			SliderDragTarget.WORMHOLE_VOLUME -> SliderKeyboardValue(workingCopy.wormholeDepartureAlertSoundVolume, 0.0f, 2.0f, 0.05f)
			SliderDragTarget.WORMHOLE_PITCH -> SliderKeyboardValue(workingCopy.wormholeDepartureAlertSoundPitch, 0.1f, 2.0f, 0.05f)
			SliderDragTarget.PICKAXE_ALERT_VOLUME -> SliderKeyboardValue(workingCopy.pickaxeAbilityCooldownAlertSoundVolume, 0.0f, 2.0f, 0.05f)
			SliderDragTarget.PICKAXE_ALERT_PITCH -> SliderKeyboardValue(workingCopy.pickaxeAbilityCooldownAlertSoundPitch, 0.1f, 2.0f, 0.05f)
			SliderDragTarget.CHIMERA_DROP_VOLUME -> SliderKeyboardValue(workingCopy.chimeraBookDropEffectsSoundVolume, 0.0f, 2.0f, 0.05f)
			SliderDragTarget.CHIMERA_DROP_PITCH -> SliderKeyboardValue(workingCopy.chimeraBookDropEffectsSoundPitch, 0.1f, 2.0f, 0.05f)
			SliderDragTarget.FIRE_FREEZE_LINE_WIDTH -> SliderKeyboardValue(workingCopy.fireFreezeCircleLineWidth, 1.0f, 8.0f, 0.1f)
			SliderDragTarget.FIRE_FREEZE_ALERT_VOLUME -> SliderKeyboardValue(workingCopy.fireFreezeRefreezeAlertSoundVolume, 0.0f, 2.0f, 0.05f)
			SliderDragTarget.FIRE_FREEZE_ALERT_PITCH -> SliderKeyboardValue(workingCopy.fireFreezeRefreezeAlertSoundPitch, 0.1f, 2.0f, 0.05f)
			SliderDragTarget.SLAYER_ANNOUNCER_VOLUME -> SliderKeyboardValue(workingCopy.slayerSpawnAnnouncerSoundVolume, 0.0f, 2.0f, 0.05f)
			SliderDragTarget.SLAYER_ANNOUNCER_PITCH -> SliderKeyboardValue(workingCopy.slayerSpawnAnnouncerSoundPitch, 0.1f, 2.0f, 0.05f)
		}
		val bounds = when (target) {
			SliderDragTarget.LINE_MODE -> tracerLineBounds(settingsContentBounds())
			SliderDragTarget.LINE_WIDTH -> tracerLineWidthBounds(settingsContentBounds())
			SliderDragTarget.MOB_MODEL_SCALE -> mobModelScaleBounds(settingsContentBounds())
			SliderDragTarget.ALERT_VOLUME -> lostFightVolumeBounds(settingsContentBounds())
			SliderDragTarget.ALERT_PITCH -> lostFightPitchBounds(settingsContentBounds())
			SliderDragTarget.WORMHOLE_VOLUME -> wormholeVolumeBounds(settingsContentBounds())
			SliderDragTarget.WORMHOLE_PITCH -> wormholePitchBounds(settingsContentBounds())
			SliderDragTarget.PICKAXE_ALERT_VOLUME -> pickaxeAlertVolumeBounds(settingsContentBounds())
			SliderDragTarget.PICKAXE_ALERT_PITCH -> pickaxeAlertPitchBounds(settingsContentBounds())
			SliderDragTarget.CHIMERA_DROP_VOLUME -> chimeraDropVolumeBounds(settingsContentBounds())
			SliderDragTarget.CHIMERA_DROP_PITCH -> chimeraDropPitchBounds(settingsContentBounds())
			SliderDragTarget.FIRE_FREEZE_LINE_WIDTH -> fireFreezeLineWidthBounds(settingsContentBounds())
			SliderDragTarget.FIRE_FREEZE_ALERT_VOLUME -> fireFreezeAlertVolumeBounds(settingsContentBounds())
			SliderDragTarget.FIRE_FREEZE_ALERT_PITCH -> fireFreezeAlertPitchBounds(settingsContentBounds())
			SliderDragTarget.SLAYER_ANNOUNCER_VOLUME -> slayerAnnouncerVolumeBounds(settingsContentBounds())
			SliderDragTarget.SLAYER_ANNOUNCER_PITCH -> slayerAnnouncerPitchBounds(settingsContentBounds())
		}
		val adjusted = (value + direction * step).coerceIn(min, max)
		val progress = (adjusted - min) / (max - min)
		updateSliderFromMouse(bounds.left + 8 + ((bounds.width() - 16) * progress).toInt(), target)
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

		val menu = settingsContentBounds()
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
				if (!workingCopy.hideonleafLostFightAlertEnabled) soundDropdownOpen = false
				return true
			}

			if (shareDataBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.hideonleafShareDataEnabled = !workingCopy.hideonleafShareDataEnabled
				return true
			}

			if (shardTrackerBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.shardTrackerEnabled = !workingCopy.shardTrackerEnabled
				return true
			}

			if (workingCopy.hideonleafLostFightAlertEnabled && lostFightSoundBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				openColorField = null
				mobModelDropdownOpen = false
				mobModelVariantDropdownOpen = false
				soundDropdownOpen = !soundDropdownOpen
				soundScrollOffset = 0
				layoutWidgets()
				return true
			}

			if (workingCopy.hideonleafLostFightAlertEnabled && soundDropdownOpen && soundListBounds(menu).contains(mouseX, mouseY)) {
				val index = soundScrollOffset + ((mouseY - soundListBounds(menu).top) / SOUND_ROW_HEIGHT)
				val filtered = SoundCatalog.filtered(lostFightSoundSearchField.text)
				if (index in filtered.indices) {
					readWorkingCopyFromFields(updateStatus = false)
					selectAndPreviewSound(filtered[index].id)
					soundDropdownOpen = false
					layoutWidgets()
				}
				return true
			}

			if (workingCopy.hideonleafLostFightAlertEnabled && lostFightVolumeBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.ALERT_VOLUME
				updateSliderFromMouse(mouseX, SliderDragTarget.ALERT_VOLUME)
				return true
			}

			if (workingCopy.hideonleafLostFightAlertEnabled && lostFightPitchBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.ALERT_PITCH
				updateSliderFromMouse(mouseX, SliderDragTarget.ALERT_PITCH)
				return true
			}

			if (workingCopy.hideonleafLostFightAlertEnabled && playLostFightSoundBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				awaitingHideonleafResetConfirmation = false
				mod.playHideonleafLostFightSound(workingCopy)
				return true
			}

			if (resetHideonleafTrackerBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				if (!awaitingHideonleafResetConfirmation) {
					awaitingHideonleafResetConfirmation = true
					statusMessage = Component.literal("Click again to reset Hideonleaf total data.")
				} else {
					awaitingHideonleafResetConfirmation = false
					HideonleafShardTracker.resetTotal()
					statusMessage = Component.literal("Hideonleaf total data reset.")
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
				if (!workingCopy.wormholeDepartureAlertEnabled) soundDropdownOpen = false
				return true
			}

			if (!workingCopy.wormholeDepartureAlertEnabled) return false
			if (wormholeSoundBounds(menu).contains(mouseX, mouseY)) {
				soundDropdownOpen = !soundDropdownOpen
				soundScrollOffset = 0
				layoutWidgets()
				return true
			}
			if (soundDropdownOpen && soundListBounds(menu).contains(mouseX, mouseY)) {
				val index = soundScrollOffset + ((mouseY - soundListBounds(menu).top) / SOUND_ROW_HEIGHT)
				SoundCatalog.filtered(wormholeSoundSearchField.text).getOrNull(index)?.let { selectAndPreviewSound(it.id) }
				soundDropdownOpen = false
				layoutWidgets()
				return true
			}
			if (wormholeVolumeBounds(menu).contains(mouseX, mouseY)) {
				draggingSlider = SliderDragTarget.WORMHOLE_VOLUME
				updateSliderFromMouse(mouseX, SliderDragTarget.WORMHOLE_VOLUME)
				return true
			}
			if (wormholePitchBounds(menu).contains(mouseX, mouseY)) {
				draggingSlider = SliderDragTarget.WORMHOLE_PITCH
				updateSliderFromMouse(mouseX, SliderDragTarget.WORMHOLE_PITCH)
				return true
			}
			if (wormholePreviewBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				previewSound(workingCopy.wormholeDepartureAlertSoundId, workingCopy.wormholeDepartureAlertSoundVolume, workingCopy.wormholeDepartureAlertSoundPitch)
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
				if (!workingCopy.slayerSpawnAnnouncerEnabled) soundDropdownOpen = false
				return true
			}

			if (slayerMiscExpanded && workingCopy.slayerSpawnAnnouncerEnabled && slayerAnnouncerSoundBounds(menu).contains(mouseX, mouseY)) {
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
					selectAndPreviewSound(filtered[index].id)
					soundDropdownOpen = false
					layoutWidgets()
				}
				return true
			}

			if (slayerMiscExpanded && workingCopy.slayerSpawnAnnouncerEnabled && slayerAnnouncerVolumeBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.SLAYER_ANNOUNCER_VOLUME
				updateSliderFromMouse(mouseX, SliderDragTarget.SLAYER_ANNOUNCER_VOLUME)
				return true
			}

			if (slayerMiscExpanded && workingCopy.slayerSpawnAnnouncerEnabled && slayerAnnouncerPitchBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.SLAYER_ANNOUNCER_PITCH
				updateSliderFromMouse(mouseX, SliderDragTarget.SLAYER_ANNOUNCER_PITCH)
				return true
			}

			if (slayerMiscExpanded && workingCopy.slayerSpawnAnnouncerEnabled && slayerAnnouncerPreviewBounds(menu).contains(mouseX, mouseY)) {
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
				statusMessage = Component.literal("This mob has no configurable variants.")
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
					statusMessage = Component.literal("Dungeon AutoKick cache cleared.")
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

			if (pickobulusHelperBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.pickobulusHelperModuleEnabled = !workingCopy.pickobulusHelperModuleEnabled
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
				if (!workingCopy.pickaxeAbilityCooldownAlertEnabled) soundDropdownOpen = false
				return true
			}

			if (workingCopy.pickaxeAbilityCooldownAlertEnabled && pickaxeAlertSoundBounds(menu).contains(mouseX, mouseY)) {
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
					selectAndPreviewSound(filtered[index].id)
					soundDropdownOpen = false
					layoutWidgets()
				}
				return true
			}

			if (workingCopy.pickaxeAbilityCooldownAlertEnabled && pickaxeAlertVolumeBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.PICKAXE_ALERT_VOLUME
				updateSliderFromMouse(mouseX, SliderDragTarget.PICKAXE_ALERT_VOLUME)
				return true
			}

			if (workingCopy.pickaxeAbilityCooldownAlertEnabled && pickaxeAlertPitchBounds(menu).contains(mouseX, mouseY)) {
				readWorkingCopyFromFields(updateStatus = false)
				draggingSlider = SliderDragTarget.PICKAXE_ALERT_PITCH
				updateSliderFromMouse(mouseX, SliderDragTarget.PICKAXE_ALERT_PITCH)
				return true
			}

			if (workingCopy.pickaxeAbilityCooldownAlertEnabled && pickaxeAlertPreviewBounds(menu).contains(mouseX, mouseY)) {
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
					selectAndPreviewSound(filtered[index].id)
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
					Component.literal("Triggered Chimera book effect test.")
				} else {
					Component.literal("Developer mode and the module must be enabled.")
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
				fireFreezeRefreezeAlertBounds(menu).contains(mouseX, mouseY) -> {
					workingCopy.fireFreezeRefreezeAlertEnabled = !workingCopy.fireFreezeRefreezeAlertEnabled
					if (!workingCopy.fireFreezeRefreezeAlertEnabled) soundDropdownOpen = false
				}
				workingCopy.fireFreezeRefreezeAlertEnabled && fireFreezeAlertSoundBounds(menu).contains(mouseX, mouseY) -> {
					openColorField = null
					soundDropdownOpen = !soundDropdownOpen
					soundScrollOffset = 0
					layoutWidgets()
				}
				soundDropdownOpen && soundListBounds(menu).contains(mouseX, mouseY) -> {
					val index = soundScrollOffset + ((mouseY - soundListBounds(menu).top) / SOUND_ROW_HEIGHT)
					val filtered = SoundCatalog.filtered(activeSoundSearchField().text)
					if (index in filtered.indices) {
						selectAndPreviewSound(filtered[index].id)
						soundDropdownOpen = false
						layoutWidgets()
					}
				}
				workingCopy.fireFreezeRefreezeAlertEnabled && fireFreezeAlertVolumeBounds(menu).contains(mouseX, mouseY) -> {
					draggingSlider = SliderDragTarget.FIRE_FREEZE_ALERT_VOLUME
					updateSliderFromMouse(mouseX, SliderDragTarget.FIRE_FREEZE_ALERT_VOLUME)
				}
				workingCopy.fireFreezeRefreezeAlertEnabled && fireFreezeAlertPitchBounds(menu).contains(mouseX, mouseY) -> {
					draggingSlider = SliderDragTarget.FIRE_FREEZE_ALERT_PITCH
					updateSliderFromMouse(mouseX, SliderDragTarget.FIRE_FREEZE_ALERT_PITCH)
				}
				workingCopy.fireFreezeRefreezeAlertEnabled && fireFreezeAlertPreviewBounds(menu).contains(mouseX, mouseY) -> FireFreezeFeature.playAlertPreview(workingCopy)
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

		if (section == ConfigSection.STATUS && workingCopy.checkForUpdatesEnabled && settingRowBounds(menu, 1, SETTING_HEIGHT).contains(mouseX, mouseY)) {
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

	private fun normalizedColor(value: String): String? = ClientColor.parseRgb(value)?.let(ClientColor::formatRgb)

	private fun updateColorFromPicker(mouseX: Int, mouseY: Int, target: ColorPickerDragTarget) {
		val menu = settingsContentBounds()
		val widget = colorFieldWidget(openColorField ?: return)
		val currentColor = ClientColor.parseRgb(widget.text) ?: DEFAULT_GLOW_COLOR
		val hsb = ClientColor.rgbToHsb(currentColor)

		var hue = hsb.hue
		var saturation = hsb.saturation
		var brightness = hsb.brightness

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

		val rgb = ClientColor.hsbToRgb(hue, saturation, brightness)
		widget.text = ClientColor.formatRgb(rgb)
		readWorkingCopyFromFields(updateStatus = false)
	}

	private fun updateSliderFromMouse(mouseX: Int, target: SliderDragTarget) {
		val menu = settingsContentBounds()
		val bounds = when (target) {
			SliderDragTarget.LINE_MODE -> tracerLineBounds(menu)
			SliderDragTarget.LINE_WIDTH -> tracerLineWidthBounds(menu)
			SliderDragTarget.MOB_MODEL_SCALE -> mobModelScaleBounds(menu)
			SliderDragTarget.ALERT_VOLUME -> lostFightVolumeBounds(menu)
			SliderDragTarget.ALERT_PITCH -> lostFightPitchBounds(menu)
			SliderDragTarget.WORMHOLE_VOLUME -> wormholeVolumeBounds(menu)
			SliderDragTarget.WORMHOLE_PITCH -> wormholePitchBounds(menu)
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
			SliderDragTarget.WORMHOLE_VOLUME -> 0.0f
			SliderDragTarget.WORMHOLE_PITCH -> 0.1f
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
			SliderDragTarget.WORMHOLE_VOLUME -> 2.0f
			SliderDragTarget.WORMHOLE_PITCH -> 2.0f
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
			SliderDragTarget.WORMHOLE_VOLUME -> roundToStep(rawValue, 0.05f)
			SliderDragTarget.WORMHOLE_PITCH -> roundToStep(rawValue, 0.05f)
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
			SliderDragTarget.WORMHOLE_VOLUME -> workingCopy.wormholeDepartureAlertSoundVolume = value
			SliderDragTarget.WORMHOLE_PITCH -> workingCopy.wormholeDepartureAlertSoundPitch = value
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

	private fun corpseEspTungstenBounds(menu: Bounds): Bounds {
		val top = corpseEspLapisBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun corpseEspUmberBounds(menu: Bounds): Bounds {
		val top = corpseEspTungstenBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun corpseEspVanguardBounds(menu: Bounds): Bounds {
		val top = corpseEspUmberBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
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
		val top = shardTrackerBounds(menu).bottom + SETTING_GAP
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

	private fun shardTrackerBounds(menu: Bounds): Bounds {
		val top = shareDataBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun wormholeSoundBounds(menu: Bounds): Bounds = settingRowBounds(menu, 1, SETTING_HEIGHT)

	private fun wormholeVolumeBounds(menu: Bounds): Bounds {
		val top = if (soundDropdownOpen) soundListBounds(menu).bottom + SETTING_GAP else wormholeSoundBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun wormholePitchBounds(menu: Bounds): Bounds = slayerRowAfter(wormholeVolumeBounds(menu), SETTING_HEIGHT)

	private fun wormholePreviewBounds(menu: Bounds): Bounds = slayerRowAfter(wormholePitchBounds(menu), SETTING_HEIGHT)

	private fun pickaxeShowReadyBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 40 + SETTING_HEIGHT)
	}

	private fun pickaxeAlertDisclosureBounds(menu: Bounds): Bounds {
		val top = pickobulusHelperBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + SETTING_HEIGHT)
	}

	private fun pickobulusHelperBounds(menu: Bounds): Bounds {
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
			ConfigSection.WORMHOLE_FINDER -> wormholeSoundBounds(menu)
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
		return Bounds(rowLeft, rowTop, menu.right - 10, rowTop + rowHeight)
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
		return Bounds(rowLeft, rowTop, menu.right - 10, rowTop + rowHeight)
	}

	private fun coopRelayToggleBounds(menu: Bounds): Bounds {
		val rowTop = menu.top + 40 + (4 * (TEXT_INPUT_SETTING_HEIGHT + SETTING_GAP))
		return Bounds(menu.left + 10, rowTop, menu.right - 10, rowTop + SETTING_HEIGHT)
	}

	private fun chatImplosionHiderBounds(menu: Bounds): Bounds = settingRowBounds(menu, 0, SETTING_HEIGHT)

	private fun chatImplosionExampleBounds(menu: Bounds): Bounds {
		val top = chatImplosionHiderBounds(menu).bottom + SETTING_GAP
		return Bounds(menu.left + 10, top, menu.right - 10, top + TEXT_INPUT_SETTING_HEIGHT)
	}

	private fun ircTestConnectionBounds(menu: Bounds): Bounds {
		val rowTop = coopRelayToggleBounds(menu).bottom + SETTING_GAP
		val rowLeft = menu.left + 10
		return Bounds(rowLeft, rowTop, menu.right - 10, rowTop + SETTING_HEIGHT)
	}

	private fun hudEditorBounds(menu: Bounds): Bounds {
		val rowTop = updaterInfoBounds(menu).bottom + SETTING_GAP
		val rowLeft = menu.left + 10
		return Bounds(rowLeft, rowTop, menu.right - 10, rowTop + SETTING_HEIGHT)
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

	private fun slayerMiscHeaderBounds(menu: Bounds): Bounds {
		val previous = if (slayerBlazeExpanded) slayerBlazeColoredMobsBounds(menu) else slayerBlazeHeaderBounds(menu)
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

	private fun trimToWidth(value: String, maxWidth: Int): String {
		if (textRenderer.width(value) <= maxWidth) {
			return value
		}

		var trimmed = value
		while (trimmed.length > 3 && textRenderer.width("$trimmed...") > maxWidth) {
			trimmed = trimmed.dropLast(1)
		}
		return "$trimmed..."
	}

	private fun activeSoundSearchField(): EditBox {
		return when (openedSection) {
			ConfigSection.WORMHOLE_FINDER -> wormholeSoundSearchField
			ConfigSection.PICKAXE_COOLDOWN -> pickaxeAlertSoundSearchField
			ConfigSection.CHIMERA_DROP -> chimeraDropSoundSearchField
			ConfigSection.FIRE_FREEZE -> fireFreezeAlertSoundSearchField
			ConfigSection.SLAYER -> slayerAnnouncerSoundSearchField
			else -> lostFightSoundSearchField
		}
	}

	private fun activeSelectedSoundId(): String {
		return when (openedSection) {
			ConfigSection.WORMHOLE_FINDER -> workingCopy.wormholeDepartureAlertSoundId
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

	private fun setVisible(widget: EditBox, visible: Boolean) {
		widget.visible = visible
		widget.setEditable(visible)
		widget.setCanLoseFocus(visible)
		if (!visible) {
			widget.setFocused(false)
		}
	}

	private fun selectAndPreviewSound(soundId: String) {
		when (openedSection) {
			ConfigSection.HIDEONLEAF_HELPER -> workingCopy.hideonleafLostFightAlertSoundId = soundId
			ConfigSection.WORMHOLE_FINDER -> workingCopy.wormholeDepartureAlertSoundId = soundId
			ConfigSection.PICKAXE_COOLDOWN -> workingCopy.pickaxeAbilityCooldownAlertSoundId = soundId
			ConfigSection.CHIMERA_DROP -> workingCopy.chimeraBookDropEffectsSoundId = soundId
			ConfigSection.FIRE_FREEZE -> workingCopy.fireFreezeRefreezeAlertSoundId = soundId
			ConfigSection.SLAYER -> workingCopy.slayerSpawnAnnouncerSoundId = soundId
			else -> return
		}
		val (volume, pitch) = when (openedSection) {
			ConfigSection.HIDEONLEAF_HELPER -> workingCopy.hideonleafLostFightAlertSoundVolume to workingCopy.hideonleafLostFightAlertSoundPitch
			ConfigSection.WORMHOLE_FINDER -> workingCopy.wormholeDepartureAlertSoundVolume to workingCopy.wormholeDepartureAlertSoundPitch
			ConfigSection.PICKAXE_COOLDOWN -> workingCopy.pickaxeAbilityCooldownAlertSoundVolume to workingCopy.pickaxeAbilityCooldownAlertSoundPitch
			ConfigSection.CHIMERA_DROP -> workingCopy.chimeraBookDropEffectsSoundVolume to workingCopy.chimeraBookDropEffectsSoundPitch
			ConfigSection.FIRE_FREEZE -> workingCopy.fireFreezeRefreezeAlertSoundVolume to workingCopy.fireFreezeRefreezeAlertSoundPitch
			ConfigSection.SLAYER -> workingCopy.slayerSpawnAnnouncerSoundVolume to workingCopy.slayerSpawnAnnouncerSoundPitch
			else -> return
		}
		previewSound(soundId, volume, pitch)
	}

	private fun previewSound(soundId: String, volume: Float, pitch: Float) {
		minecraft.soundManager.play(SoundCatalog.masterSound(soundId, pitch.coerceIn(0.1f, 2.0f), volume.coerceIn(0.0f, 2.0f)))
	}

	private data class ConfigPanel(val title: String, val sections: List<ConfigSection>)
	private data class PanelPosition(val panel: ConfigPanel, val x: Int, val y: Int)
	private data class ModuleRow(val section: ConfigSection, val bounds: Bounds)
	private data class SliderKeyboardValue(val value: Float, val min: Float, val max: Float, val step: Float)

	private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
		fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
		fun intersects(other: Bounds): Boolean = right > other.left && left < other.right && bottom > other.top && top < other.bottom
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
		WORMHOLE_VOLUME,
		WORMHOLE_PITCH,
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

	private enum class ConfigCategory { MODULES, MISC, DUNGEON, GALATEA, SYSTEM }

	private enum class ConfigSection(
		val label: String,
		val description: String,
		val category: ConfigCategory,
		val hasSettings: Boolean = true,
		private val enabledGetter: ((BridgeConfig) -> Boolean)? = null,
		private val enabledSetter: ((BridgeConfig, Boolean) -> Unit)? = null,
	) {
		SETUP("Setup", "Fixed mod API endpoint and global status.", ConfigCategory.SYSTEM),
		IRC_BRIDGE("IRC Bridge", "IRC server, auth, polling, and message format.", ConfigCategory.MODULES, enabledGetter = { it.ircBridgeEnabled }, enabledSetter = { c, v -> c.ircBridgeEnabled = v }),
		CHAT("Chat", "Client-side chat cleanup and message hiders.", ConfigCategory.MODULES, enabledGetter = { it.chatModuleEnabled }, enabledSetter = { c, v -> c.chatModuleEnabled = v }),
		HIDEONLEAF_HELPER("Hideonleaf Helper", "Shulker glow and Hideonleaf fight alerts.", ConfigCategory.GALATEA, enabledGetter = { it.hideonleafHelperEnabled }, enabledSetter = { c, v -> c.hideonleafHelperEnabled = v }),
		PURPLE_TERRACOTTA("Purple Terracotta", "Highlights purple terracotta blocks through walls.", ConfigCategory.GALATEA, enabledGetter = { it.purpleTerracottaHighlightModuleEnabled }, enabledSetter = { c, v -> c.purpleTerracottaHighlightModuleEnabled = v }),
		FLOOR_DROP_ESP("Floor Drop ESP", "Highlights Galatea floor drops made from three grouped string displays.", ConfigCategory.GALATEA, enabledGetter = { it.floorDropEspModuleEnabled }, enabledSetter = { c, v -> c.floorDropEspModuleEnabled = v }),
		WORMHOLE_FINDER("Wormhole Finder", "Shows a water-surface ring and tracer for the active wormhole.", ConfigCategory.MISC, enabledGetter = { it.wormholeFinderModuleEnabled }, enabledSetter = { c, v -> c.wormholeFinderModuleEnabled = v }),
		AUTO_SPRINT("Auto Sprint", "Automatically starts sprinting while moving forward.", ConfigCategory.MISC, enabledGetter = { it.autoSprintModuleEnabled }, enabledSetter = { c, v -> c.autoSprintModuleEnabled = v }),
		TIME_CHANGER("Time Changer", "Client-side world time presets.", ConfigCategory.MODULES, enabledGetter = { it.timeChangerEnabled }, enabledSetter = { c, v -> c.timeChangerEnabled = v }),
		AUCTION_HOUSE("Auction House", "Copies BIN underbids and tracks High Class Dice LBIN sell signals.", ConfigCategory.MODULES, enabledGetter = { it.auctionHouseModuleEnabled }, enabledSetter = { c, v -> c.auctionHouseModuleEnabled = v }),
		SLAYER("Slayer", "Slayer helpers including Blaze boss phase displays and Autopet spawn announcements.", ConfigCategory.MODULES, enabledGetter = { it.slayerModuleEnabled }, enabledSetter = { c, v -> c.slayerModuleEnabled = v }),
		PEST_ESP("Pest ESP", "Highlights named Garden pests through walls.", ConfigCategory.MISC, enabledGetter = { it.pestEspModuleEnabled }, enabledSetter = { c, v -> c.pestEspModuleEnabled = v }),
		CORPSE_ESP("Corpse ESP", "Highlights Glacite Mineshaft corpses by armor-stand helmet ID.", ConfigCategory.MISC, enabledGetter = { it.corpseEspModuleEnabled }, enabledSetter = { c, v -> c.corpseEspModuleEnabled = v }),
		MOB_MODEL("Mob Model", "Replaces the player model client-side with any living mob model and syncs it through the backend.", ConfigCategory.MISC, enabledGetter = { it.mobModelModuleEnabled }, enabledSetter = { c, v -> c.mobModelModuleEnabled = v }),
		CROSSHAIR("Crosshair", "Overrides the vanilla or texturepack crosshair with a custom editable grid.", ConfigCategory.MISC, enabledGetter = { it.customCrosshairModuleEnabled }, enabledSetter = { c, v -> c.customCrosshairModuleEnabled = v }),
		INVENTORY_PREVIEW("Inventory Preview", "Shows your inventory as a HUD element with optional armor slot rendering.", ConfigCategory.MISC, enabledGetter = { it.inventoryPreviewModuleEnabled }, enabledSetter = { c, v -> c.inventoryPreviewModuleEnabled = v }),
		ITEM_UPDATE_FIX("Item Update Fix", "Keeps bow use and drill mining stable across server inventory updates without sending packets.", ConfigCategory.MISC, hasSettings = false, enabledGetter = { it.itemUpdateFixModuleEnabled }, enabledSetter = { c, v -> c.itemUpdateFixModuleEnabled = v }),
		SILENT_DISCONNECT("Silent Disconnect", "Sets your Hypixel status offline on disconnect and restores it on rejoin.", ConfigCategory.MISC, hasSettings = false, enabledGetter = { it.silentDisconnectModuleEnabled }, enabledSetter = { c, v -> c.silentDisconnectModuleEnabled = v }),
		CHIMERA_DROP("Chimera Drop", "Shows the Totem-style screen effect when a Chimera book drops.", ConfigCategory.MISC, enabledGetter = { it.chimeraBookDropEffectsModuleEnabled }, enabledSetter = { c, v -> c.chimeraBookDropEffectsModuleEnabled = v }),
		M5("M5", "Livid finder, Ice Spray timer, and Rag Axe alert for Master Mode Floor 5.", ConfigCategory.DUNGEON, enabledGetter = { it.m5ModuleEnabled }, enabledSetter = { c, v -> c.m5ModuleEnabled = v }),
		DUNGEON_AUTOKICK("Dungeon AutoKick", "Odin-style Party Finder stats and requirement-based autokick using the Xclipsen backend.", ConfigCategory.DUNGEON, enabledGetter = { it.dungeonAutoKickModuleEnabled }, enabledSetter = { c, v -> c.dungeonAutoKickModuleEnabled = v }),
		PICKAXE_COOLDOWN("Pickaxe Cooldown", "Mining ability cooldown HUD, alerts, and Pickobulus surface-block helper.", ConfigCategory.MISC, enabledGetter = { it.pickaxeAbilityCooldownModuleEnabled }, enabledSetter = { c, v -> c.pickaxeAbilityCooldownModuleEnabled = v }),
		FIRE_FREEZE("Fire Freeze", "SkyHanni-style Fire Freeze timers, circle, mob boxes, and refreeze alert.", ConfigCategory.MISC, enabledGetter = { it.fireFreezeModuleEnabled }, enabledSetter = { c, v -> c.fireFreezeModuleEnabled = v }),
		MINESHAFT_AUTOWARP("Mineshaft AutoWarp", "Auto-requests lead and party-warps when configured corpse counts are found.", ConfigCategory.MISC, enabledGetter = { it.mineshaftAutoWarpModuleEnabled }, enabledSetter = { c, v -> c.mineshaftAutoWarpModuleEnabled = v }),
		DEPLOYBLE("Deployble", "Alerts when your deployable items are about to expire.", ConfigCategory.MISC, hasSettings = false, enabledGetter = { it.deploybleModuleEnabled }, enabledSetter = { c, v -> c.deploybleModuleEnabled = v }),
		EXPERIMENTS("Experimentation", "Shizo-style auto experiments plus SkyHanni keep-items-visible for Superpairs.", ConfigCategory.DUNGEON, enabledGetter = { it.experimentationTableModuleEnabled }, enabledSetter = { c, v -> c.experimentationTableModuleEnabled = v }),
		DOOR("Door", "Turns the disappearing blocks behind Mort into local barrier blocks using relative offsets.", ConfigCategory.DUNGEON, enabledGetter = { it.dungeonDoorModuleEnabled }, enabledSetter = { c, v -> c.dungeonDoorModuleEnabled = v }),
		RED_VIGNETTE("Red Vignette", "Matches Devonian's client-side click fix for the red vignette.", ConfigCategory.DUNGEON, enabledGetter = { it.dungeonRedVignetteModuleEnabled }, enabledSetter = { c, v -> c.dungeonRedVignetteModuleEnabled = v }),
		STATUS("Status", "Current config path and backend state.", ConfigCategory.SYSTEM),
		;

		val toggleable: Boolean get() = enabledGetter != null
		fun isEnabled(config: BridgeConfig): Boolean = enabledGetter?.invoke(config) ?: true
		fun toggle(config: BridgeConfig) = enabledSetter?.invoke(config, !isEnabled(config))
	}

	private enum class ConfigField(val section: ConfigSection) {
		IRC_SERVER_URL(ConfigSection.IRC_BRIDGE),
		AUTH_TOKEN(ConfigSection.IRC_BRIDGE),
		POLL_INTERVAL(ConfigSection.IRC_BRIDGE),
		IRC_FORMAT(ConfigSection.IRC_BRIDGE),
		AUTO_EXPERIMENTS_CLICK_DELAY(ConfigSection.EXPERIMENTS),
		AUTO_EXPERIMENTS_DELAY_VARIETY(ConfigSection.EXPERIMENTS),
		AUTO_EXPERIMENTS_SERUM_COUNT(ConfigSection.EXPERIMENTS),
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
		private const val ACCENT = XclipsenUiTokens.ACCENT
		private const val ACCENT_TRANS = XclipsenUiTokens.ACCENT_TRANSLUCENT
		private const val PANEL_HEADER = XclipsenUiTokens.SURFACE_PANEL_HEADER
		private const val PANEL_BODY = XclipsenUiTokens.SURFACE_PANEL_BODY
		private const val HOVER = XclipsenUiTokens.SURFACE_HOVER
		private const val SELECTED = XclipsenUiTokens.SURFACE_SELECTED
		private const val POPUP_BACKGROUND = XclipsenUiTokens.SURFACE_POPUP
		private const val SETTING_BACKGROUND = XclipsenUiTokens.SURFACE_SETTING
		private const val INPUT_BACKGROUND = XclipsenUiTokens.SURFACE_INPUT
		private const val TEXT_WHITE = XclipsenUiTokens.TEXT_WHITE
		private const val TEXT_PRIMARY = XclipsenUiTokens.TEXT_PRIMARY
		private const val TEXT_DISABLED = XclipsenUiTokens.TEXT_DISABLED
		private const val TEXT_MUTED = XclipsenUiTokens.TEXT_MUTED
		private const val TEXT_ERROR = XclipsenUiTokens.TEXT_ERROR
		private const val TEXT_SUCCESS = XclipsenUiTokens.TEXT_SUCCESS
		private const val LEFT_MOUSE_BUTTON = 0
		private const val RIGHT_MOUSE_BUTTON = 1
		private const val PANEL_WIDTH = 110
		private const val PANEL_HEADER_HEIGHT = XclipsenUiTokens.PANEL_HEADER_HEIGHT
		private const val PANEL_ROW_HEIGHT = XclipsenUiTokens.PANEL_ROW_HEIGHT
		private const val POPUP_WIDTH = 200
		private const val POPUP_HEIGHT = 250
		private const val SETTINGS_HEADER_HEIGHT = 32
		private const val SETTINGS_FOOTER_HEIGHT = 26
		private const val SETTINGS_SCROLL_STEP = 18
		private const val SETUP_POPUP_HEIGHT = 120
		private const val IRC_POPUP_HEIGHT = 330
		private const val CHAT_POPUP_HEIGHT = 145
		private const val HIDEONLEAF_POPUP_HEIGHT = 500
		private const val WORMHOLE_POPUP_HEIGHT = 190
		private const val WORMHOLE_POPUP_WITH_DROPDOWN_HEIGHT = 305
		private const val PURPLE_TERRACOTTA_POPUP_HEIGHT = 230
		private const val TIME_CHANGER_POPUP_HEIGHT = 100
		private const val AUCTION_HOUSE_POPUP_HEIGHT = 150
		private const val PEST_ESP_POPUP_HEIGHT = 230
		private const val CORPSE_ESP_POPUP_HEIGHT = 410
		private const val MOB_MODEL_POPUP_HEIGHT = 325
		private const val MOB_MODEL_POPUP_WITH_DROPDOWN_HEIGHT = 455
		private const val CROSSHAIR_POPUP_HEIGHT = 265
		private const val INVENTORY_PREVIEW_POPUP_HEIGHT = 165
		private const val CHIMERA_DROP_POPUP_HEIGHT = 380
		private const val CHIMERA_DROP_POPUP_WITH_DROPDOWN_HEIGHT = 480
		private const val M5_POPUP_HEIGHT = 190
		private const val DUNGEON_AUTOKICK_POPUP_HEIGHT = 610
		private const val DUNGEON_AUTOKICK_POPUP_WITH_DROPDOWN_HEIGHT = 690
		private const val STATUS_POPUP_HEIGHT = 255
		private const val PICKAXE_COOLDOWN_POPUP_COLLAPSED_HEIGHT = 170
		private const val PICKAXE_COOLDOWN_POPUP_EXPANDED_HEIGHT = 345
		private const val PICKAXE_COOLDOWN_POPUP_EXPANDED_WITH_DROPDOWN_HEIGHT = 445
		private const val FIRE_FREEZE_POPUP_HEIGHT = 410
		private const val FIRE_FREEZE_POPUP_WITH_DROPDOWN_HEIGHT = 510
		private const val MINESHAFT_AUTOWARP_POPUP_HEIGHT = 230
		private const val SETTING_WIDTH = 180
		private const val SETTING_HEIGHT = XclipsenUiTokens.CONTROL_HEIGHT
		private const val TEXT_INPUT_SETTING_HEIGHT = 38
		private const val TEXT_INPUT_WIDTH = 164
		private const val COLOR_INPUT_WIDTH = 134
		private const val SETTING_GAP = XclipsenUiTokens.SPACING_SM
		private const val EXPERIMENTS_SECTION_GAP = XclipsenUiTokens.SPACING_LG
		private const val SEARCH_WIDTH = 150
		private const val SOUND_VISIBLE_ROWS = 6
		private const val MOB_MODEL_VISIBLE_ROWS = 7
		private const val DUNGEON_AUTOKICK_FLOOR_VISIBLE_ROWS = 5
		private const val SOUND_ROW_HEIGHT = 15
		private val DUNGEON_AUTOKICK_FLOOR_OPTIONS = listOf("F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7")
		private const val SOUND_LIST_TEXT_WIDTH = 145
		private const val CROSSHAIR_GRID_CELL_SIZE = 18
		private const val CROSSHAIR_GRID_SETTING_HEIGHT = 150
		private const val DEFAULT_GLOW_COLOR = XclipsenUiTokens.ACCENT and 0xFFFFFF
		private const val COLOR_PICKER_STEP = 2
		private const val COLOR_PICKER_BLOCK_HEIGHT = 122
		private fun copyOf(source: BridgeConfig): BridgeConfig = source.copy()
	}
}
