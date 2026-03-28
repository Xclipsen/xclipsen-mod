package de.xclipsen.ircbridge

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import java.io.IOException

class XclipsenConfigScreen(
	private val parent: Screen?,
	private val mod: XclipsenIrcBridgeClient,
) : Screen(Text.literal("Xclipsen IRC Bridge")) {
	private var workingCopy: BridgeConfig = copyOf(mod.config())
	private lateinit var backendBaseUrlField: TextFieldWidget
	private lateinit var backendAuthTokenField: TextFieldWidget
	private lateinit var backendPollIntervalField: TextFieldWidget
	private lateinit var discordFormatField: TextFieldWidget
	private lateinit var ircFormatField: TextFieldWidget
	private lateinit var eventPingFormatField: TextFieldWidget
	private lateinit var saveButton: ButtonWidget
	private lateinit var cancelButton: ButtonWidget
	private lateinit var testConnectionButton: ButtonWidget
	private var statusMessage: Text = Text.empty()

	override fun init() {
		super.init()

		val centerX = width / 2
		val fieldWidth = 320
		val left = centerX - (fieldWidth / 2)
		var y = 40

		backendBaseUrlField = addField(left, y, fieldWidth, workingCopy.backendBaseUrl, "Expected: full HTTP/HTTPS URL, e.g. http://127.0.0.1:8765")
		y += 28
		backendAuthTokenField = addField(left, y, fieldWidth, workingCopy.backendAuthToken, "Expected: backend auth token/shared secret")
		y += 28
		backendPollIntervalField = addField(left, y, fieldWidth, workingCopy.backendPollIntervalMs.toString(), "Expected: polling interval in milliseconds, minimum 500")
		y += 28
		eventPingFormatField = addField(left, y, fieldWidth, workingCopy.eventPingFormat, "Expected: text format using %event% and %message%")
		y += 28
		discordFormatField = addField(left, y, fieldWidth, workingCopy.discordToMinecraftFormat, "Expected: text format using %user% and %message%")
		y += 28
		ircFormatField = addField(left, y, fieldWidth, workingCopy.ircCommandFormat, "Expected: text format using %player% and %message%")
		y += 28

		testConnectionButton = addDrawableChild(
			ButtonWidget.builder(Text.literal("Test Connection")) { testConnection() }
				.dimensions(left, y, fieldWidth, 20)
				.build(),
		)

		saveButton = addDrawableChild(
			ButtonWidget.builder(Text.literal("Save")) { save() }
				.dimensions(left, height - 52, 156, 20)
				.build(),
		)
		cancelButton = addDrawableChild(
			ButtonWidget.builder(Text.literal("Cancel")) { close() }
				.dimensions(left + 164, height - 52, 156, 20)
				.build(),
		)

		updateVisibleWidgets()
	}

	override fun close() {
		client?.setScreen(parent)
	}

	override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
		renderBackground(context, mouseX, mouseY, delta)
		super.render(context, mouseX, mouseY, delta)

		val centerX = width / 2
		val fieldWidth = 320
		val left = centerX - (fieldWidth / 2)
		var y = 40

		context.drawCenteredTextWithShadow(textRenderer, title, centerX, 12, 0xFFFFFF)
		drawLabel(context, "Backend URL", left, y - 10)
		y += 28
		drawLabel(context, "Backend Auth Token", left, y - 10)
		y += 28
		drawLabel(context, "Poll Interval (ms)", left, y - 10)
		y += 28
		drawLabel(context, "Event Ping Format", left, y - 10)
		y += 28
		drawLabel(context, "Discord Format", left, y - 10)
		y += 28
		drawLabel(context, "IRC Format", left, y - 10)

		if (statusMessage.string.isNotEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, statusMessage, centerX, height - 20, 0xFF8080)
		}
	}

	private fun save() {
		workingCopy.backendBaseUrl = backendBaseUrlField.text.trim()
		workingCopy.backendAuthToken = backendAuthTokenField.text.trim()
		workingCopy.eventPingFormat = eventPingFormatField.text
		workingCopy.discordToMinecraftFormat = discordFormatField.text
		workingCopy.ircCommandFormat = ircFormatField.text

		try {
			workingCopy.backendPollIntervalMs = backendPollIntervalField.text.trim().toLong()
		} catch (_: NumberFormatException) {
			statusMessage = Text.literal("Poll interval must be a number.")
			return
		}

		if (workingCopy.backendPollIntervalMs < 500L) {
			statusMessage = Text.literal("Poll interval must be at least 500 ms.")
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
		val candidate = readWorkingCopyFromFields() ?: run {
			testConnectionButton.active = true
			return
		}
		statusMessage = Text.literal(XclipsenIrcBridgeClient.formatStatus(mod.testBackendConnection(candidate)))
		testConnectionButton.active = true
	}

	private fun readWorkingCopyFromFields(): BridgeConfig? {
		val candidate = copyOf(workingCopy)
		candidate.backendBaseUrl = backendBaseUrlField.text.trim()
		candidate.backendAuthToken = backendAuthTokenField.text.trim()
		candidate.eventPingFormat = eventPingFormatField.text
		candidate.discordToMinecraftFormat = discordFormatField.text
		candidate.ircCommandFormat = ircFormatField.text

		try {
			candidate.backendPollIntervalMs = backendPollIntervalField.text.trim().toLong()
		} catch (_: NumberFormatException) {
			statusMessage = Text.literal("Poll interval must be a number.")
			return null
		}

		if (candidate.backendPollIntervalMs < 500L) {
			statusMessage = Text.literal("Poll interval must be at least 500 ms.")
			return null
		}

		workingCopy = candidate
		return candidate
	}

	private fun addField(x: Int, y: Int, width: Int, value: String, placeholder: String): TextFieldWidget {
		val field = TextFieldWidget(textRenderer, x, y, width, 20, Text.empty())
		field.setMaxLength(512)
		field.text = value
		field.setPlaceholder(Text.literal(placeholder))
		addDrawableChild(field)
		return field
	}

	private fun updateVisibleWidgets() {
		layoutCurrentPage()
		setVisible(backendBaseUrlField, true)
		setVisible(backendAuthTokenField, true)
		setVisible(backendPollIntervalField, true)
		setVisible(eventPingFormatField, true)
		setVisible(discordFormatField, true)
		setVisible(ircFormatField, true)
		setVisible(testConnectionButton, true)
	}

	private fun layoutCurrentPage() {
		val centerX = width / 2
		val fieldWidth = 320
		val left = centerX - (fieldWidth / 2)
		var y = 40

		position(backendBaseUrlField, left, y)
		y += 28
		position(backendAuthTokenField, left, y)
		y += 28
		position(backendPollIntervalField, left, y)
		y += 28
		position(eventPingFormatField, left, y)
		y += 28
		position(discordFormatField, left, y)
		y += 28
		position(ircFormatField, left, y)
		y += 28
		position(testConnectionButton, left, y)
	}

	private fun drawLabel(context: DrawContext, label: String, x: Int, y: Int) {
		context.drawTextWithShadow(textRenderer, Text.literal(label), x, y, 0xA0A0A0)
	}

	companion object {
		private fun setVisible(widget: ButtonWidget, visible: Boolean) {
			widget.visible = visible
			widget.active = visible
		}

		private fun setVisible(widget: TextFieldWidget, visible: Boolean) {
			widget.visible = visible
			widget.setEditable(visible)
			widget.setFocusUnlocked(visible)
		}

		private fun position(widget: ButtonWidget, x: Int, y: Int) {
			widget.x = x
			widget.y = y
		}

		private fun position(widget: TextFieldWidget, x: Int, y: Int) {
			widget.x = x
			widget.y = y
		}

		private fun copyOf(source: BridgeConfig): BridgeConfig = source.copy()
	}
}
