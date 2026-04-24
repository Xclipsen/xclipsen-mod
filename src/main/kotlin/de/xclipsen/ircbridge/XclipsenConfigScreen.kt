package de.xclipsen.ircbridge

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.input.KeyInput
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import java.awt.Color
import java.io.IOException
import java.util.Locale

class XclipsenConfigScreen(
	private val parent: Screen?,
	private val mod: XclipsenIrcBridgeClient,
) : Screen(Text.literal("Xclipsen Settings")) {
	private var workingCopy: BridgeConfig = copyOf(mod.config())
	private var selectedSection = ConfigSection.SETUP
	private var openedSection: ConfigSection? = null
	private var openColorField: ConfigField? = null
	private var draggingColorPicker: ColorPickerDragTarget? = null
	private var soundDropdownOpen = false
	private var soundScrollOffset = 0
	private var draggingSlider: SliderDragTarget? = null
	private var awaitingHideonleafResetConfirmation = false
	private var statusMessage: Text = Text.empty()
	private val colorPickerOpen: Boolean
		get() = openColorField != null

	private lateinit var searchField: TextFieldWidget
	private lateinit var backendBaseUrlField: TextFieldWidget
	private lateinit var backendAuthTokenField: TextFieldWidget
	private lateinit var backendPollIntervalField: TextFieldWidget
	private lateinit var discordFormatField: TextFieldWidget
	private lateinit var ircFormatField: TextFieldWidget
	private lateinit var eventPingFormatField: TextFieldWidget
	private lateinit var coopFormatField: TextFieldWidget
	private lateinit var shulkerGlowColorHexField: TextFieldWidget
	private lateinit var shulkerProjectileGlowColorHexField: TextFieldWidget
	private lateinit var shulkerTracerLineColorHexField: TextFieldWidget
	private lateinit var lostFightSoundSearchField: TextFieldWidget
	private lateinit var shulkerGlowButton: ButtonWidget
	private lateinit var testConnectionButton: ButtonWidget
	private lateinit var saveButton: ButtonWidget
	private lateinit var cancelButton: ButtonWidget

	private val fields = mutableMapOf<ConfigField, TextFieldWidget>()
	private val sectionRows = listOf(
		ConfigPanel("MODULES", listOf(ConfigSection.IRC_BRIDGE, ConfigSection.HIDEONLEAF_HELPER, ConfigSection.TIME_CHANGER)),
		ConfigPanel("SYSTEM", listOf(ConfigSection.SETUP, ConfigSection.STATUS)),
	)

	override fun init() {
		super.init()

		fields.clear()
		searchField = addField(0, 0, 150, "", "Search...")
		searchField.setTextShadow(false)

		backendBaseUrlField = registerField(ConfigField.BACKEND_URL, workingCopy.backendBaseUrl, "http://127.0.0.1:8765")
		backendAuthTokenField = registerField(ConfigField.AUTH_TOKEN, workingCopy.backendAuthToken, "shared secret")
		backendPollIntervalField = registerField(ConfigField.POLL_INTERVAL, workingCopy.backendPollIntervalMs.toString(), "minimum 500")
		discordFormatField = registerField(ConfigField.DISCORD_FORMAT, workingCopy.discordToMinecraftFormat, "%user% and %message%")
		ircFormatField = registerField(ConfigField.IRC_FORMAT, workingCopy.ircCommandFormat, "%player% and %message%")
		eventPingFormatField = registerField(ConfigField.EVENT_FORMAT, workingCopy.eventPingFormat, "%event% and %message%")
		coopFormatField = registerField(ConfigField.COOP_FORMAT, workingCopy.coopChatFormat, "%player% and %message%")
		shulkerGlowColorHexField = registerField(ConfigField.SHULKER_GLOW_COLOR, workingCopy.shulkerGlowColorHex, "#36C5F0")
		shulkerProjectileGlowColorHexField = registerField(ConfigField.SHULKER_PROJECTILE_GLOW_COLOR, workingCopy.shulkerProjectileGlowColorHex, "#FF4D4D")
		shulkerTracerLineColorHexField = registerField(ConfigField.SHULKER_TRACER_LINE_COLOR, workingCopy.shulkerTracerLineColorHex, "#36C5F0")
		lostFightSoundSearchField = addField(0, 0, 150, "", "Search sound...")

		testConnectionButton = addDrawableChild(
			ButtonWidget.builder(Text.literal("Test Connection")) { testConnection() }.dimensions(0, 0, 120, 20).build(),
		)
		shulkerGlowButton = addDrawableChild(
			ButtonWidget.builder(Text.empty()) {
				readWorkingCopyFromFields(updateStatus = false)
				workingCopy.shulkerGlowEnabled = !workingCopy.shulkerGlowEnabled
				updateShulkerGlowButton()
			}.dimensions(0, 0, 120, 20).build(),
		)
		saveButton = addDrawableChild(
			ButtonWidget.builder(Text.literal("Save")) { save() }.dimensions(0, 0, 90, 20).build(),
		)
		cancelButton = addDrawableChild(
			ButtonWidget.builder(Text.literal("Cancel")) { close() }.dimensions(0, 0, 90, 20).build(),
		)

		updateShulkerGlowButton()
		layoutWidgets()
	}

	override fun close() {
		readWorkingCopyFromFields(updateStatus = false)?.let {
			try {
				mod.saveAndApplyConfig(it)
			} catch (_: IOException) {
			}
		}
		client?.setScreen(parent)
	}

	override fun shouldPause(): Boolean = false

	override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
		renderBackground(context, mouseX, mouseY, delta)
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
				draggingColorPicker = null
				draggingSlider = null
			} else if (button == RIGHT_MOUSE_BUTTON) {
				openedSection = clickedSection
				openColorField = null
				soundDropdownOpen = false
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
		val dragTarget = draggingColorPicker
		val sliderTarget = draggingSlider
		if (openedSection == ConfigSection.HIDEONLEAF_HELPER && sliderTarget != null) {
			updateSliderFromMouse(click.x().toInt(), sliderTarget)
			return true
		}

		if (openedSection == ConfigSection.HIDEONLEAF_HELPER && dragTarget != null) {
			updateColorFromPicker(click.x().toInt(), click.y().toInt(), dragTarget)
			return true
		}

		return super.mouseDragged(click, offsetX, offsetY)
	}

	override fun mouseReleased(click: Click): Boolean {
		draggingColorPicker = null
		draggingSlider = null
		return super.mouseReleased(click)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
		if (openedSection == ConfigSection.HIDEONLEAF_HELPER && soundDropdownOpen) {
			val list = soundListBounds(settingsBounds())
			if (list.contains(mouseX.toInt(), mouseY.toInt())) {
				val filtered = SoundCatalog.filtered(lostFightSoundSearchField.text)
				val maxScroll = (filtered.size - SOUND_VISIBLE_ROWS).coerceAtLeast(0)
				soundScrollOffset = (soundScrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
				return true
			}
		}

		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
	}

	private fun toggleModule(section: ConfigSection) {
		when (section) {
			ConfigSection.IRC_BRIDGE -> workingCopy.ircBridgeEnabled = !workingCopy.ircBridgeEnabled
			ConfigSection.HIDEONLEAF_HELPER -> workingCopy.hideonleafHelperEnabled = !workingCopy.hideonleafHelperEnabled
			ConfigSection.TIME_CHANGER -> workingCopy.timeChangerEnabled = !workingCopy.timeChangerEnabled
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
		openColorField = null
		soundDropdownOpen = false
		soundScrollOffset = 0
		draggingColorPicker = null
		draggingSlider = null
		awaitingHideonleafResetConfirmation = false
		layoutWidgets()
	}

	private fun save() {
		if (readWorkingCopyFromFields(updateStatus = true) == null) {
			return
		}

		try {
			mod.saveAndApplyConfig(workingCopy)
			statusMessage = Text.literal("Saved.")
			close()
		} catch (_: IOException) {
			statusMessage = Text.literal("Failed to save config.")
		}
	}

	private fun testConnection() {
		testConnectionButton.active = false
		val candidate = readWorkingCopyFromFields(updateStatus = true) ?: run {
			testConnectionButton.active = true
			return
		}
		statusMessage = Text.literal(XclipsenIrcBridgeClient.formatStatus(mod.testBackendConnection(candidate)))
		testConnectionButton.active = true
	}

	private fun readWorkingCopyFromFields(updateStatus: Boolean): BridgeConfig? {
		val candidate = copyOf(workingCopy)
		candidate.backendBaseUrl = backendBaseUrlField.text.trim()
		candidate.backendAuthToken = backendAuthTokenField.text.trim()
		candidate.eventPingFormat = eventPingFormatField.text
		candidate.discordToMinecraftFormat = discordFormatField.text
		candidate.ircCommandFormat = ircFormatField.text
		candidate.coopChatFormat = coopFormatField.text
		candidate.ircBridgeEnabled = workingCopy.ircBridgeEnabled
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
		candidate.timeChangerMode = workingCopy.timeChangerMode.coerceIn(0, ClientTimeChanger.modeCount - 1)
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

		workingCopy = candidate
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
			val rowIndex = section?.let { textFieldRowIndex(it, field) } ?: -1
			if (rowIndex >= 0) {
				val row = settingRowBounds(menu, rowIndex, TEXT_INPUT_SETTING_HEIGHT)
				val inputWidth = if (field == ConfigField.SHULKER_GLOW_COLOR) COLOR_INPUT_WIDTH else TEXT_INPUT_WIDTH
				widget.setDimensionsAndPosition(inputWidth, 20, row.left + 8, row.top + 15)
				setVisible(widget, true)
			} else {
				setVisible(widget, false)
			}
		}

		setVisible(testConnectionButton, false)
		setVisible(shulkerGlowButton, false)
		setVisible(saveButton, false)
		setVisible(cancelButton, false)
		if (section == ConfigSection.HIDEONLEAF_HELPER && soundDropdownOpen) {
			val search = soundSearchBounds(menu)
			lostFightSoundSearchField.setDimensionsAndPosition(search.width(), 18, search.left, search.top)
			setVisible(lostFightSoundSearchField, true)
		} else {
			setVisible(lostFightSoundSearchField, false)
		}

		searchField.setDimensionsAndPosition(SEARCH_WIDTH, 22, (width / 2) - (SEARCH_WIDTH / 2), height - 40)
	}

	private fun updateShulkerGlowButton() {
		shulkerGlowButton.message = Text.literal(if (workingCopy.shulkerGlowEnabled) "Enabled" else "Disabled")
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
			ConfigSection.HIDEONLEAF_HELPER -> workingCopy.hideonleafHelperEnabled
			ConfigSection.TIME_CHANGER -> workingCopy.timeChangerEnabled
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
			ConfigSection.HIDEONLEAF_HELPER -> drawHideonleafHelperSettings(context, menu, mouseX, mouseY)
			ConfigSection.TIME_CHANGER -> drawTimeChangerSettings(context, menu, mouseX, mouseY)
			ConfigSection.STATUS -> drawStatusSettings(context, menu, mouseX, mouseY)
		}

		if (statusMessage.string.isNotEmpty()) {
			val color = if (statusMessage.string.startsWith("Failed") || statusMessage.string.startsWith("Poll")) TEXT_ERROR else TEXT_SUCCESS
			context.drawCenteredTextWithShadow(textRenderer, statusMessage, (menu.left + menu.right) / 2, menu.bottom - 18, color)
		}
	}

	private fun drawSetupSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawTextInputSetting(context, menu, 0, "Backend URL", backendBaseUrlField, mouseX, mouseY)
		drawTextInputSetting(context, menu, 1, "Backend Auth Token", backendAuthTokenField, mouseX, mouseY)
		drawTextInputSetting(context, menu, 2, "Poll Interval (ms)", backendPollIntervalField, mouseX, mouseY)
		drawButtonSetting(context, setupTestConnectionBounds(menu), "Test Connection", mouseX, mouseY)
	}

	private fun drawIrcBridgeSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawTextInputSetting(context, menu, 0, "Discord Format", discordFormatField, mouseX, mouseY)
		drawTextInputSetting(context, menu, 1, "IRC Format", ircFormatField, mouseX, mouseY)
		drawTextInputSetting(context, menu, 2, "Event Ping Format", eventPingFormatField, mouseX, mouseY)
		drawTextInputSetting(context, menu, 3, "Co-op Format", coopFormatField, mouseX, mouseY)
		drawToggleSetting(context, coopRelayToggleBounds(menu), "Co-op Relay", workingCopy.coopChatRelayEnabled, mouseX, mouseY)
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

	private fun drawStatusSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		val status = mod.backendStatus()
		drawInfoSetting(context, settingRowBounds(menu, 0, TEXT_INPUT_SETTING_HEIGHT), "Config", mod.configPath().toString(), mouseX, mouseY)
		drawInfoSetting(context, settingRowBounds(menu, 1, TEXT_INPUT_SETTING_HEIGHT), "Backend", XclipsenIrcBridgeClient.formatStatus(status), mouseX, mouseY)
		drawButtonSetting(context, hudEditorBounds(menu), "Open HUD Editor", mouseX, mouseY)
	}

	private fun drawTimeChangerSettings(context: DrawContext, menu: Bounds, mouseX: Int, mouseY: Int) {
		drawOptionSetting(context, timeChangerModeBounds(menu), "Time", ClientTimeChanger.displayName(workingCopy.timeChangerMode), mouseX, mouseY)
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
		val row = settingRowBounds(menu, rowIndex, TEXT_INPUT_SETTING_HEIGHT)
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
		val search = soundSearchBounds(menu)
		val list = soundListBounds(menu)
		context.fill(search.left - 4, search.top - 4, search.right + 4, list.bottom + 4, INPUT_BACKGROUND)
		context.fill(search.left, search.top, search.right, search.bottom, 0xC80F0F0F.toInt())
		context.fill(search.left, search.bottom - 1, search.right, search.bottom, if (lostFightSoundSearchField.isFocused) ACCENT else 0x1EFFFFFF)

		val filtered = SoundCatalog.filtered(lostFightSoundSearchField.text)
		val maxScroll = (filtered.size - SOUND_VISIBLE_ROWS).coerceAtLeast(0)
		soundScrollOffset = soundScrollOffset.coerceIn(0, maxScroll)

		context.enableScissor(list.left, list.top, list.right, list.bottom)
		filtered.drop(soundScrollOffset).take(SOUND_VISIBLE_ROWS).forEachIndexed { index, sound ->
			val row = Bounds(list.left, list.top + (index * SOUND_ROW_HEIGHT), list.right, list.top + ((index + 1) * SOUND_ROW_HEIGHT))
			val hovered = row.contains(mouseX, mouseY)
			val selected = sound.id == workingCopy.hideonleafLostFightAlertSoundId
			if (hovered) {
				context.fill(row.left, row.top, row.right, row.bottom, HOVER)
			}
			val textColor = if (selected) ACCENT else if (hovered) TEXT_WHITE else TEXT_MUTED
			context.drawTextWithShadow(textRenderer, trimToWidth(sound.name, SOUND_LIST_TEXT_WIDTH), row.left + 4, row.top + 3, textColor)
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

	private fun drawInfoSetting(context: DrawContext, row: Bounds, label: String, value: String, mouseX: Int, mouseY: Int) {
		val hovered = row.contains(mouseX, mouseY)
		drawSettingBackground(context, row, hovered)
		context.drawTextWithShadow(textRenderer, label, row.left + 8 + if (hovered) 2 else 0, row.top + 4, TEXT_WHITE)
		context.drawTextWithShadow(textRenderer, trimToWidth(value, TEXT_INPUT_WIDTH), row.left + 8, row.top + 20, TEXT_MUTED)
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
			ConfigSection.HIDEONLEAF_HELPER -> HIDEONLEAF_POPUP_HEIGHT
			ConfigSection.TIME_CHANGER -> TIME_CHANGER_POPUP_HEIGHT
			else -> POPUP_HEIGHT
		}
		val menuHeight = targetHeight.coerceAtMost((height - 80).coerceAtLeast(targetHeight))
		val left = (width / 2) - (menuWidth / 2)
		val top = (height / 2) - (menuHeight / 2)
		return Bounds(left, top, left + menuWidth, top + menuHeight)
	}

	private fun handleSettingsClick(section: ConfigSection, mouseX: Int, mouseY: Int, button: Int): Boolean {
		if (button != LEFT_MOUSE_BUTTON) {
			return true
		}

		val menu = settingsBounds()
		if (section == ConfigSection.HIDEONLEAF_HELPER && settingRowBounds(menu, 0, SETTING_HEIGHT).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.shulkerGlowEnabled = !workingCopy.shulkerGlowEnabled
			updateShulkerGlowButton()
			return true
		}

		if (section == ConfigSection.SETUP && setupTestConnectionBounds(menu).contains(mouseX, mouseY)) {
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

		if (section == ConfigSection.IRC_BRIDGE && coopRelayToggleBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.coopChatRelayEnabled = !workingCopy.coopChatRelayEnabled
			return true
		}

		if (section == ConfigSection.TIME_CHANGER && timeChangerModeBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			workingCopy.timeChangerMode = (workingCopy.timeChangerMode + 1) % ClientTimeChanger.modeCount
			return true
		}

		if (section == ConfigSection.STATUS && hudEditorBounds(menu).contains(mouseX, mouseY)) {
			readWorkingCopyFromFields(updateStatus = false)
			mod.openHudEditorScreen(this)
			return true
		}

		return false
	}

	private fun textFieldsFor(section: ConfigSection): List<ConfigField> {
		return when (section) {
			ConfigSection.SETUP -> listOf(
				ConfigField.BACKEND_URL,
				ConfigField.AUTH_TOKEN,
				ConfigField.POLL_INTERVAL,
			)
			ConfigSection.IRC_BRIDGE -> listOf(
				ConfigField.DISCORD_FORMAT,
				ConfigField.IRC_FORMAT,
				ConfigField.EVENT_FORMAT,
				ConfigField.COOP_FORMAT,
			)
			else -> emptyList()
		}
	}

	private fun textFieldRowIndex(section: ConfigSection, field: ConfigField): Int {
		return textFieldsFor(section).indexOf(field)
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
			SliderDragTarget.ALERT_VOLUME -> lostFightVolumeBounds(menu)
			SliderDragTarget.ALERT_PITCH -> lostFightPitchBounds(menu)
		}
		val min = when (target) {
			SliderDragTarget.LINE_MODE -> 0.0f
			SliderDragTarget.LINE_WIDTH -> 1.0f
			SliderDragTarget.ALERT_VOLUME -> 0.0f
			SliderDragTarget.ALERT_PITCH -> 0.1f
		}
		val max = when (target) {
			SliderDragTarget.LINE_MODE -> 3.0f
			SliderDragTarget.LINE_WIDTH -> 8.0f
			SliderDragTarget.ALERT_VOLUME -> 2.0f
			SliderDragTarget.ALERT_PITCH -> 2.0f
		}
		val barLeft = bounds.left + 8
		val barRight = bounds.right - 8
		val progress = ((mouseX - barLeft).toFloat() / (barRight - barLeft).coerceAtLeast(1)).coerceIn(0.0f, 1.0f)
		val rawValue = min + ((max - min) * progress)
		val value = when (target) {
			SliderDragTarget.LINE_MODE -> roundToStep(rawValue, 1.0f)
			SliderDragTarget.LINE_WIDTH -> roundToStep(rawValue, 0.1f)
			SliderDragTarget.ALERT_VOLUME -> roundToStep(rawValue, 0.05f)
			SliderDragTarget.ALERT_PITCH -> roundToStep(rawValue, 0.05f)
		}.coerceIn(min, max)

		when (target) {
			SliderDragTarget.LINE_MODE -> {
				workingCopy.shulkerTracerLineMode = value.toInt()
				workingCopy.shulkerTracerLineEnabled = value.toInt() > 0
			}
			SliderDragTarget.LINE_WIDTH -> workingCopy.shulkerTracerLineWidth = value
			SliderDragTarget.ALERT_VOLUME -> workingCopy.hideonleafLostFightAlertSoundVolume = value
			SliderDragTarget.ALERT_PITCH -> workingCopy.hideonleafLostFightAlertSoundPitch = value
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
		val top = lostFightSoundBounds(menu).bottom + 4
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

	private fun settingRowBounds(menu: Bounds, rowIndex: Int, rowHeight: Int): Bounds {
		val rowTop = menu.top + 40 + (rowIndex * (rowHeight + SETTING_GAP))
		val rowLeft = menu.left + 10
		return Bounds(rowLeft, rowTop, rowLeft + SETTING_WIDTH, rowTop + rowHeight)
	}

	private fun coopRelayToggleBounds(menu: Bounds): Bounds {
		val rowTop = menu.top + 40 + (4 * (TEXT_INPUT_SETTING_HEIGHT + SETTING_GAP))
		return Bounds(menu.left + 10, rowTop, menu.left + 10 + SETTING_WIDTH, rowTop + SETTING_HEIGHT)
	}

	private fun setupTestConnectionBounds(menu: Bounds): Bounds {
		val rowTop = menu.top + 40 + (3 * (TEXT_INPUT_SETTING_HEIGHT + SETTING_GAP))
		val rowLeft = menu.left + 10
		return Bounds(rowLeft, rowTop, rowLeft + SETTING_WIDTH, rowTop + SETTING_HEIGHT)
	}

	private fun hudEditorBounds(menu: Bounds): Bounds {
		val rowTop = menu.top + 40 + (2 * (TEXT_INPUT_SETTING_HEIGHT + SETTING_GAP))
		val rowLeft = menu.left + 10
		return Bounds(rowLeft, rowTop, rowLeft + SETTING_WIDTH, rowTop + SETTING_HEIGHT)
	}

	private fun timeChangerModeBounds(menu: Bounds): Bounds {
		return Bounds(menu.left + 10, menu.top + 40, menu.right - 10, menu.top + 40 + SETTING_HEIGHT)
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

	private fun setVisible(widget: ButtonWidget, visible: Boolean) {
		widget.visible = visible
		widget.active = visible
		if (!visible) {
			widget.setFocused(false)
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
		ALERT_VOLUME,
		ALERT_PITCH,
	}

	private enum class ConfigSection(
		val label: String,
		val description: String,
		val toggleable: Boolean = false,
	) {
		SETUP("Setup", "Global backend and API settings used by all modules."),
		IRC_BRIDGE("IRC Bridge", "IRC message formats and bridge-specific toggles.", toggleable = true),
		HIDEONLEAF_HELPER("Hideonleaf Helper", "Shulker glow and Hideonleaf fight alerts.", toggleable = true),
		TIME_CHANGER("Time Changer", "Client-side world time presets.", toggleable = true),
		STATUS("Status", "Current config path and backend state."),
	}

	private enum class ConfigField(val section: ConfigSection) {
		BACKEND_URL(ConfigSection.SETUP),
		AUTH_TOKEN(ConfigSection.SETUP),
		POLL_INTERVAL(ConfigSection.SETUP),
		DISCORD_FORMAT(ConfigSection.IRC_BRIDGE),
		IRC_FORMAT(ConfigSection.IRC_BRIDGE),
		EVENT_FORMAT(ConfigSection.IRC_BRIDGE),
		COOP_FORMAT(ConfigSection.IRC_BRIDGE),
		SHULKER_GLOW_COLOR(ConfigSection.HIDEONLEAF_HELPER),
		SHULKER_PROJECTILE_GLOW_COLOR(ConfigSection.HIDEONLEAF_HELPER),
		SHULKER_TRACER_LINE_COLOR(ConfigSection.HIDEONLEAF_HELPER),
	}

	companion object {
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
		private const val SETUP_POPUP_HEIGHT = 220
		private const val IRC_POPUP_HEIGHT = 250
		private const val HIDEONLEAF_POPUP_HEIGHT = 500
		private const val TIME_CHANGER_POPUP_HEIGHT = 100
		private const val SETTING_WIDTH = 180
		private const val SETTING_HEIGHT = 20
		private const val TEXT_INPUT_SETTING_HEIGHT = 38
		private const val TEXT_INPUT_WIDTH = 164
		private const val COLOR_INPUT_WIDTH = 134
		private const val SETTING_GAP = 5
		private const val SEARCH_WIDTH = 150
		private const val SOUND_VISIBLE_ROWS = 6
		private const val SOUND_ROW_HEIGHT = 15
		private const val SOUND_LIST_TEXT_WIDTH = 145
		private const val DEFAULT_GLOW_COLOR = 0x36C5F0
		private const val COLOR_PICKER_STEP = 2
		private const val COLOR_PICKER_BLOCK_HEIGHT = 122
		private val HEX_COLOR_PATTERN = Regex("[0-9a-fA-F]{6}")

		private fun copyOf(source: BridgeConfig): BridgeConfig = source.copy()
	}
}
