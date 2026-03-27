package de.xclipsen.ircbridge;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.IOException;

public final class XclipsenConfigScreen extends Screen {
	private final Screen parent;
	private final XclipsenIrcBridgeClient mod;

	private BridgeConfig workingCopy;
	private ButtonWidget bridgeModeButton;
	private TextFieldWidget backendBaseUrlField;
	private TextFieldWidget backendAuthTokenField;
	private TextFieldWidget backendPollIntervalField;
	private TextFieldWidget discordFormatField;
	private TextFieldWidget ircFormatField;
	private ButtonWidget testConnectionButton;
	private Text statusMessage = Text.empty();

	public XclipsenConfigScreen(Screen parent, XclipsenIrcBridgeClient mod) {
		super(Text.literal("Xclipsen IRC Bridge"));
		this.parent = parent;
		this.mod = mod;
		this.workingCopy = copyOf(mod.config());
	}

	@Override
	protected void init() {
		super.init();

		int centerX = this.width / 2;
		int fieldWidth = 320;
		int left = centerX - (fieldWidth / 2);
		int y = 40;

		bridgeModeButton = ButtonWidget.builder(modeLabel(), button -> {
			workingCopy.bridgeMode = "backend".equalsIgnoreCase(workingCopy.bridgeMode) ? "direct" : "backend";
			button.setMessage(modeLabel());
		}).dimensions(left, y, fieldWidth, 20).build();
		addDrawableChild(bridgeModeButton);
		y += 28;

		backendBaseUrlField = addField(left, y, fieldWidth, workingCopy.backendBaseUrl);
		y += 28;
		backendAuthTokenField = addField(left, y, fieldWidth, workingCopy.backendAuthToken);
		y += 28;
		backendPollIntervalField = addField(left, y, fieldWidth, Long.toString(workingCopy.backendPollIntervalMs));
		y += 28;
		discordFormatField = addField(left, y, fieldWidth, workingCopy.discordToMinecraftFormat);
		y += 28;
		ircFormatField = addField(left, y, fieldWidth, workingCopy.ircCommandFormat);
		y += 36;

		addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> save())
			.dimensions(left, y, 156, 20)
			.build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close())
			.dimensions(left + 164, y, 156, 20)
			.build());
		y += 28;

		testConnectionButton = addDrawableChild(ButtonWidget.builder(Text.literal("Test Connection"), button -> testConnection())
			.dimensions(left, y, fieldWidth, 20)
			.build());
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(parent);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.renderBackground(context, mouseX, mouseY, delta);
		super.render(context, mouseX, mouseY, delta);

		int centerX = this.width / 2;
		int fieldWidth = 320;
		int left = centerX - (fieldWidth / 2);
		int y = 28;

		context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 12, 0xFFFFFF);

		y += 40;
		drawLabel(context, "Mode", left, y - 10);
		y += 28;
		drawLabel(context, "Backend URL", left, y - 10);
		y += 28;
		drawLabel(context, "Backend Auth Token", left, y - 10);
		y += 28;
		drawLabel(context, "Poll Interval (ms)", left, y - 10);
		y += 28;
		drawLabel(context, "Discord Format", left, y - 10);
		y += 28;
		drawLabel(context, "IRC Format", left, y - 10);
		y += 44;

		BackendStatusSnapshot backendStatus = mod.backendStatus();
		drawLabel(context, "State: " + backendStatus.state(), left, y);
		y += 12;
		drawLabel(context, "HTTP: " + formatHttp(backendStatus.lastHttpStatus()), left, y);
		y += 12;
		drawLabel(context, "Last success: " + formatTimestamp(backendStatus.lastSuccessAt()), left, y);
		y += 12;
		drawLabel(context, "Last message: " + formatTimestamp(backendStatus.lastMessageAt()), left, y);
		y += 12;
		if (!backendStatus.lastError().isBlank()) {
			drawLabel(context, trim("Last error: " + backendStatus.lastError(), 60), left, y);
		}

		if (!statusMessage.getString().isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, statusMessage, centerX, this.height - 20, 0xFF8080);
		}
	}

	private void save() {
		workingCopy.backendBaseUrl = backendBaseUrlField.getText().trim();
		workingCopy.backendAuthToken = backendAuthTokenField.getText().trim();
		workingCopy.discordToMinecraftFormat = discordFormatField.getText();
		workingCopy.ircCommandFormat = ircFormatField.getText();

		try {
			workingCopy.backendPollIntervalMs = Long.parseLong(backendPollIntervalField.getText().trim());
		} catch (NumberFormatException exception) {
			statusMessage = Text.literal("Poll interval must be a number.");
			return;
		}

		try {
			mod.saveAndApplyConfig(workingCopy);
			statusMessage = Text.literal("Saved.");
			close();
		} catch (IOException exception) {
			statusMessage = Text.literal("Failed to save config.");
		}
	}

	private void testConnection() {
		testConnectionButton.active = false;
		BackendStatusSnapshot checked = mod.testBackendConnection();
		statusMessage = Text.literal(XclipsenIrcBridgeClient.formatStatus(checked));
		testConnectionButton.active = true;
	}

	private TextFieldWidget addField(int x, int y, int width, String value) {
		TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, width, 20, Text.empty());
		field.setMaxLength(512);
		field.setText(value);
		addDrawableChild(field);
		return field;
	}

	private Text modeLabel() {
		return Text.literal("Mode: " + workingCopy.bridgeMode);
	}

	private void drawLabel(DrawContext context, String label, int x, int y) {
		context.drawTextWithShadow(this.textRenderer, Text.literal(label), x, y, 0xA0A0A0);
	}

	private static String formatHttp(int status) {
		return status >= 0 ? Integer.toString(status) : "-";
	}

	private static String formatTimestamp(long timestamp) {
		if (timestamp <= 0L) {
			return "-";
		}

		long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
		return seconds + "s ago";
	}

	private static String trim(String value, int maxLength) {
		return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
	}

	private static BridgeConfig copyOf(BridgeConfig source) {
		BridgeConfig copy = new BridgeConfig();
		copy.bridgeMode = source.bridgeMode;
		copy.discordToken = source.discordToken;
		copy.guildId = source.guildId;
		copy.channelId = source.channelId;
		copy.backendBaseUrl = source.backendBaseUrl;
		copy.backendAuthToken = source.backendAuthToken;
		copy.backendPollIntervalMs = source.backendPollIntervalMs;
		copy.mirrorMinecraftChat = source.mirrorMinecraftChat;
		copy.mirrorJoinLeave = source.mirrorJoinLeave;
		copy.minecraftToDiscordFormat = source.minecraftToDiscordFormat;
		copy.discordToMinecraftFormat = source.discordToMinecraftFormat;
		copy.ircCommandFormat = source.ircCommandFormat;
		return copy;
	}
}
