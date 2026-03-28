package de.xclipsen.ircbridge;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.IOException;

public final class XclipsenConfigScreen extends Screen {
	private enum Page {
		BRIDGE("Bridge"),
		AUCTION_HOUSE("Auction House"),
		EXPERIMENTS("Experiments"),
		STATUS("Status");

		private final String label;

		Page(String label) {
			this.label = label;
		}
	}

	private final Screen parent;
	private final XclipsenIrcBridgeClient mod;

	private BridgeConfig workingCopy;
	private Page currentPage = Page.BRIDGE;
	private ButtonWidget bridgeTabButton;
	private ButtonWidget auctionHouseTabButton;
	private ButtonWidget experimentsTabButton;
	private ButtonWidget statusTabButton;
	private TextFieldWidget backendBaseUrlField;
	private TextFieldWidget backendAuthTokenField;
	private TextFieldWidget backendPollIntervalField;
	private TextFieldWidget discordFormatField;
	private TextFieldWidget ircFormatField;
	private TextFieldWidget eventPingFormatField;
	private ButtonWidget auctionHouseUnderbidButton;
	private ButtonWidget createBinAutoCopyButton;
	private ButtonWidget autoExperimentsEnabledButton;
	private TextFieldWidget autoExperimentsClickDelayField;
	private TextFieldWidget autoExperimentsDelayVarianceField;
	private ButtonWidget autoExperimentsAutoCloseButton;
	private ButtonWidget autoExperimentsSerumCountButton;
	private ButtonWidget autoExperimentsGetMaxXpButton;
	private ButtonWidget saveButton;
	private ButtonWidget cancelButton;
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

		bridgeTabButton = addDrawableChild(ButtonWidget.builder(tabLabel(Page.BRIDGE), button -> switchPage(Page.BRIDGE))
			.dimensions(left, y, 76, 20)
			.build());
		auctionHouseTabButton = addDrawableChild(ButtonWidget.builder(tabLabel(Page.AUCTION_HOUSE), button -> switchPage(Page.AUCTION_HOUSE))
			.dimensions(left + 82, y, 76, 20)
			.build());
		experimentsTabButton = addDrawableChild(ButtonWidget.builder(tabLabel(Page.EXPERIMENTS), button -> switchPage(Page.EXPERIMENTS))
			.dimensions(left + 164, y, 76, 20)
			.build());
		statusTabButton = addDrawableChild(ButtonWidget.builder(tabLabel(Page.STATUS), button -> switchPage(Page.STATUS))
			.dimensions(left + 246, y, 76, 20)
			.build());
		y += 32;

		backendBaseUrlField = addField(left, y, fieldWidth, workingCopy.backendBaseUrl, "Backend URL, z. B. http://127.0.0.1:8765");
		y += 28;
		backendAuthTokenField = addField(left, y, fieldWidth, workingCopy.backendAuthToken, "Shared Secret fuer dein Backend");
		y += 28;
		backendPollIntervalField = addField(left, y, fieldWidth, Long.toString(workingCopy.backendPollIntervalMs), "Polling-Intervall in Millisekunden");
		y += 28;
		eventPingFormatField = addField(left, y, fieldWidth, workingCopy.eventPingFormat, "Format fuer Event-Pings, z. B. [Event] %event%: %message%");
		y += 28;
		discordFormatField = addField(left, y, fieldWidth, workingCopy.discordToMinecraftFormat, "Format fuer Discord -> Minecraft, z. B. [Discord] <%user%> %message%");
		y += 28;
		ircFormatField = addField(left, y, fieldWidth, workingCopy.ircCommandFormat, "Format fuer /irc-Nachrichten, z. B. [IRC] <%player%> %message%");
		y += 28;

		auctionHouseUnderbidButton = addDrawableChild(ButtonWidget.builder(auctionHouseUnderbidLabel(), button ->
			toggleAuctionHouseUnderbid(button)
		).dimensions(left, y, fieldWidth, 20).build());
		y += 28;
		createBinAutoCopyButton = addDrawableChild(ButtonWidget.builder(createBinAutoCopyLabel(), button ->
			toggleCreateBinAutoCopy(button)
		).dimensions(left, y, fieldWidth, 20).build());
		y += 28;

		autoExperimentsEnabledButton = addDrawableChild(ButtonWidget.builder(autoExperimentsEnabledLabel(), button ->
			toggleAutoExperimentsEnabled(button)
		).dimensions(left, y, fieldWidth, 20).build());
		y += 28;
		autoExperimentsClickDelayField = addField(left, y, fieldWidth, Long.toString(workingCopy.autoExperimentsClickDelayMs), "Grundverzoegerung pro Klick in ms");
		y += 28;
		autoExperimentsDelayVarianceField = addField(left, y, fieldWidth, Long.toString(workingCopy.autoExperimentsDelayVarianceMs), "Zusaetzliche Zufallsverzoegerung in ms");
		y += 28;
		autoExperimentsAutoCloseButton = addDrawableChild(ButtonWidget.builder(autoExperimentsAutoCloseLabel(), button ->
			toggleAutoExperimentsAutoClose(button)
		).dimensions(left, y, fieldWidth, 20).build());
		y += 28;
		autoExperimentsSerumCountButton = addDrawableChild(ButtonWidget.builder(autoExperimentsSerumCountLabel(), button ->
			cycleSerumCount(button)
		).dimensions(left, y, fieldWidth, 20).build());
		y += 28;
		autoExperimentsGetMaxXpButton = addDrawableChild(ButtonWidget.builder(autoExperimentsGetMaxXpLabel(), button ->
			toggleAutoExperimentsGetMaxXp(button)
		).dimensions(left, y, fieldWidth, 20).build());

		saveButton = addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> save())
			.dimensions(left, this.height - 52, 156, 20)
			.build());
		cancelButton = addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close())
			.dimensions(left + 164, this.height - 52, 156, 20)
			.build());
		testConnectionButton = addDrawableChild(ButtonWidget.builder(Text.literal("Test Connection"), button -> testConnection())
			.dimensions(left, this.height - 80, fieldWidth, 20)
			.build());

		updateVisibleWidgets();
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
		int y = 72;

		context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 12, 0xFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(currentPage.label), centerX, 44, 0xA0A0A0);

		if (currentPage == Page.BRIDGE) {
			drawLabel(context, "Backend URL", left, y - 10);
			y += 28;
			drawLabel(context, "Backend Auth Token", left, y - 10);
			y += 28;
			drawLabel(context, "Poll Interval (ms)", left, y - 10);
			y += 28;
			drawLabel(context, "Event Ping Format", left, y - 10);
			y += 28;
			drawLabel(context, "Discord Format", left, y - 10);
			y += 28;
			drawLabel(context, "IRC Format", left, y - 10);
		} else if (currentPage == Page.AUCTION_HOUSE) {
			drawLabel(context, "AH Underbid Copy (U)", left, y - 10);
			y += 28;
			drawLabel(context, "Create BIN Auto Copy", left, y - 10);
		} else if (currentPage == Page.EXPERIMENTS) {
			drawLabel(context, "Enabled", left, y - 10);
			y += 28;
			drawLabel(context, "Click Delay (ms)", left, y - 10);
			y += 28;
			drawLabel(context, "Delay Variance (ms)", left, y - 10);
			y += 28;
			drawLabel(context, "Auto Close", left, y - 10);
			y += 28;
			drawLabel(context, "Serum Count", left, y - 10);
			y += 28;
			drawLabel(context, "Get Max XP", left, y - 10);
		} else if (currentPage == Page.STATUS) {
			BackendStatusSnapshot backendStatus = mod.backendStatus();
			drawLabel(context, "State: " + backendStatus.state(), left, y);
			y += 16;
			drawLabel(context, "HTTP: " + formatHttp(backendStatus.lastHttpStatus()), left, y);
			y += 16;
			drawLabel(context, "Last success: " + formatTimestamp(backendStatus.lastSuccessAt()), left, y);
			y += 16;
			drawLabel(context, "Last message: " + formatTimestamp(backendStatus.lastMessageAt()), left, y);
			y += 16;
			drawLabel(context, "Config: " + trim(mod.configPath().toString(), 50), left, y);
			y += 16;
			drawLabel(context, "Last error: " + trim(backendStatus.lastError().isBlank() ? "-" : backendStatus.lastError(), 50), left, y);
		}

		if (!statusMessage.getString().isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, statusMessage, centerX, this.height - 20, 0xFF8080);
		}
	}

	private void save() {
		workingCopy.backendBaseUrl = backendBaseUrlField.getText().trim();
		workingCopy.backendAuthToken = backendAuthTokenField.getText().trim();
		workingCopy.eventPingFormat = eventPingFormatField.getText();
		workingCopy.discordToMinecraftFormat = discordFormatField.getText();
		workingCopy.ircCommandFormat = ircFormatField.getText();

		try {
			workingCopy.backendPollIntervalMs = Long.parseLong(backendPollIntervalField.getText().trim());
			workingCopy.autoExperimentsClickDelayMs = Long.parseLong(autoExperimentsClickDelayField.getText().trim());
			workingCopy.autoExperimentsDelayVarianceMs = Long.parseLong(autoExperimentsDelayVarianceField.getText().trim());
		} catch (NumberFormatException exception) {
			statusMessage = Text.literal("Delay and poll fields must be numbers.");
			return;
		}

		if (workingCopy.backendPollIntervalMs < 500L) {
			statusMessage = Text.literal("Poll interval must be at least 500 ms.");
			return;
		}

		if (workingCopy.autoExperimentsClickDelayMs < 0L || workingCopy.autoExperimentsDelayVarianceMs < 0L) {
			statusMessage = Text.literal("Experiment delays must not be negative.");
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

	private TextFieldWidget addField(int x, int y, int width, String value, String placeholder) {
		TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, width, 20, Text.empty());
		field.setMaxLength(512);
		field.setText(value);
		field.setPlaceholder(Text.literal(placeholder));
		addDrawableChild(field);
		return field;
	}

	private Text tabLabel(Page page) {
		return Text.literal((currentPage == page ? "> " : "") + page.label);
	}

	private void switchPage(Page page) {
		currentPage = page;
		updateTabLabels();
		updateVisibleWidgets();
	}

	private Text auctionHouseUnderbidLabel() {
		return Text.literal("Auction House Underbid: " + enabledState(workingCopy.auctionHouseUnderbidEnabled));
	}

	private void toggleAuctionHouseUnderbid(ButtonWidget button) {
		workingCopy.auctionHouseUnderbidEnabled = !workingCopy.auctionHouseUnderbidEnabled;
		button.setMessage(auctionHouseUnderbidLabel());
	}

	private Text createBinAutoCopyLabel() {
		return Text.literal("Create BIN Auto Copy: " + enabledState(workingCopy.auctionHouseCreateBinAutoCopyEnabled));
	}

	private void toggleCreateBinAutoCopy(ButtonWidget button) {
		workingCopy.auctionHouseCreateBinAutoCopyEnabled = !workingCopy.auctionHouseCreateBinAutoCopyEnabled;
		button.setMessage(createBinAutoCopyLabel());
	}

	private Text autoExperimentsEnabledLabel() {
		return Text.literal("Auto Experiments: " + enabledState(workingCopy.autoExperimentsEnabled));
	}

	private void toggleAutoExperimentsEnabled(ButtonWidget button) {
		workingCopy.autoExperimentsEnabled = !workingCopy.autoExperimentsEnabled;
		button.setMessage(autoExperimentsEnabledLabel());
	}

	private Text autoExperimentsAutoCloseLabel() {
		return Text.literal("Auto Close: " + enabledState(workingCopy.autoExperimentsAutoClose));
	}

	private void toggleAutoExperimentsAutoClose(ButtonWidget button) {
		workingCopy.autoExperimentsAutoClose = !workingCopy.autoExperimentsAutoClose;
		button.setMessage(autoExperimentsAutoCloseLabel());
	}

	private Text autoExperimentsSerumCountLabel() {
		return Text.literal("Serum Count: " + workingCopy.autoExperimentsSerumCount);
	}

	private void cycleSerumCount(ButtonWidget button) {
		workingCopy.autoExperimentsSerumCount = (workingCopy.autoExperimentsSerumCount + 1) % 4;
		button.setMessage(autoExperimentsSerumCountLabel());
	}

	private Text autoExperimentsGetMaxXpLabel() {
		return Text.literal("Get Max XP: " + enabledState(workingCopy.autoExperimentsGetMaxXp));
	}

	private void toggleAutoExperimentsGetMaxXp(ButtonWidget button) {
		workingCopy.autoExperimentsGetMaxXp = !workingCopy.autoExperimentsGetMaxXp;
		button.setMessage(autoExperimentsGetMaxXpLabel());
	}

	private void updateTabLabels() {
		bridgeTabButton.setMessage(tabLabel(Page.BRIDGE));
		auctionHouseTabButton.setMessage(tabLabel(Page.AUCTION_HOUSE));
		experimentsTabButton.setMessage(tabLabel(Page.EXPERIMENTS));
		statusTabButton.setMessage(tabLabel(Page.STATUS));
	}

	private void updateVisibleWidgets() {
		layoutCurrentPage();

		boolean bridge = currentPage == Page.BRIDGE;
		boolean auctionHouse = currentPage == Page.AUCTION_HOUSE;
		boolean experiments = currentPage == Page.EXPERIMENTS;
		boolean status = currentPage == Page.STATUS;

		setVisible(backendBaseUrlField, bridge);
		setVisible(backendAuthTokenField, bridge);
		setVisible(backendPollIntervalField, bridge);
		setVisible(eventPingFormatField, bridge);
		setVisible(discordFormatField, bridge);
		setVisible(ircFormatField, bridge);
		setVisible(auctionHouseUnderbidButton, auctionHouse);
		setVisible(createBinAutoCopyButton, auctionHouse);
		setVisible(autoExperimentsEnabledButton, experiments);
		setVisible(autoExperimentsClickDelayField, experiments);
		setVisible(autoExperimentsDelayVarianceField, experiments);
		setVisible(autoExperimentsAutoCloseButton, experiments);
		setVisible(autoExperimentsSerumCountButton, experiments);
		setVisible(autoExperimentsGetMaxXpButton, experiments);
		setVisible(testConnectionButton, status);
	}

	private void layoutCurrentPage() {
		int centerX = this.width / 2;
		int fieldWidth = 320;
		int left = centerX - (fieldWidth / 2);
		int y = 72;

		if (currentPage == Page.BRIDGE) {
			position(backendBaseUrlField, left, y);
			y += 28;
			position(backendAuthTokenField, left, y);
			y += 28;
			position(backendPollIntervalField, left, y);
			y += 28;
			position(eventPingFormatField, left, y);
			y += 28;
			position(discordFormatField, left, y);
			y += 28;
			position(ircFormatField, left, y);
			return;
		}

		if (currentPage == Page.AUCTION_HOUSE) {
			position(auctionHouseUnderbidButton, left, y);
			y += 28;
			position(createBinAutoCopyButton, left, y);
			return;
		}

		if (currentPage == Page.EXPERIMENTS) {
			position(autoExperimentsEnabledButton, left, y);
			y += 28;
			position(autoExperimentsClickDelayField, left, y);
			y += 28;
			position(autoExperimentsDelayVarianceField, left, y);
			y += 28;
			position(autoExperimentsAutoCloseButton, left, y);
			y += 28;
			position(autoExperimentsSerumCountButton, left, y);
			y += 28;
			position(autoExperimentsGetMaxXpButton, left, y);
		}
	}

	private static void setVisible(ButtonWidget widget, boolean visible) {
		widget.visible = visible;
		widget.active = visible;
	}

	private static void setVisible(TextFieldWidget widget, boolean visible) {
		widget.visible = visible;
		widget.setEditable(visible);
		widget.setFocusUnlocked(visible);
	}

	private static void position(ButtonWidget widget, int x, int y) {
		widget.setX(x);
		widget.setY(y);
	}

	private static void position(TextFieldWidget widget, int x, int y) {
		widget.setX(x);
		widget.setY(y);
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
		copy.backendBaseUrl = source.backendBaseUrl;
		copy.backendAuthToken = source.backendAuthToken;
		copy.backendPollIntervalMs = source.backendPollIntervalMs;
		copy.linkedDiscordDisplayName = source.linkedDiscordDisplayName;
		copy.auctionHouseUnderbidEnabled = source.auctionHouseUnderbidEnabled;
		copy.auctionHouseCreateBinAutoCopyEnabled = source.auctionHouseCreateBinAutoCopyEnabled;
		copy.autoExperimentsEnabled = source.autoExperimentsEnabled;
		copy.autoExperimentsClickDelayMs = source.autoExperimentsClickDelayMs;
		copy.autoExperimentsDelayVarianceMs = source.autoExperimentsDelayVarianceMs;
		copy.autoExperimentsAutoClose = source.autoExperimentsAutoClose;
		copy.autoExperimentsSerumCount = source.autoExperimentsSerumCount;
		copy.autoExperimentsGetMaxXp = source.autoExperimentsGetMaxXp;
		copy.discordToMinecraftFormat = source.discordToMinecraftFormat;
		copy.ircCommandFormat = source.ircCommandFormat;
		copy.eventPingFormat = source.eventPingFormat;
		return copy;
	}

	private static String enabledState(boolean enabled) {
		return enabled ? "enabled" : "disabled";
	}
}
