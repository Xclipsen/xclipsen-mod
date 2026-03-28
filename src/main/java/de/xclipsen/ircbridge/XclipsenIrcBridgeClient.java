package de.xclipsen.ircbridge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class XclipsenIrcBridgeClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("xclipsen_irc_bridge");
	private static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of("xclipsen_irc_bridge", "general"));
	private static XclipsenIrcBridgeClient instance;

	private final BridgeConfigManager configManager = new BridgeConfigManager(LOGGER);
	private final ClientBackendBridgeService backendBridge = new ClientBackendBridgeService(LOGGER);
	private final KeyBinding copyAuctionHouseUnderbidKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
		"key.xclipsen_irc_bridge.copy_auction_house_underbid",
		InputUtil.Type.KEYSYM,
		GLFW.GLFW_KEY_U,
		KEY_CATEGORY
	));
	private final AuctionHouseUnderbidFeature auctionHouseUnderbidFeature = new AuctionHouseUnderbidFeature(LOGGER, copyAuctionHouseUnderbidKey);
	private final AutoExperimentsFeature autoExperimentsFeature = new AutoExperimentsFeature();
	private final KeyBinding openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
		"key.xclipsen_irc_bridge.open_config",
		InputUtil.Type.KEYSYM,
		GLFW.GLFW_KEY_RIGHT_SHIFT,
		KEY_CATEGORY
	));

	private BridgeConfig config = new BridgeConfig();
	private boolean ircLinkWarningShown = false;

	@Override
	public void onInitializeClient() {
		instance = this;
		config = configManager.load();
		backendBridge.start(config);

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			backendBridge.stop();
			auctionHouseUnderbidFeature.stop();
		});
		ClientTickEvents.END_CLIENT_TICK.register(this::handleEndTick);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommandManager.literal("irc")
				.then(ClientCommandManager.literal("config")
					.executes(this::openConfigScreen))
				.then(ClientCommandManager.literal("status")
					.executes(this::showStatus))
				.then(ClientCommandManager.literal("reload")
					.executes(this::reloadConfig))
				.then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
					.executes(this::sendIrcMessage))
		));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommandManager.literal("link")
				.executes(this::showLinkedStatus)
				.then(ClientCommandManager.argument("code", StringArgumentType.word())
					.executes(this::completeLink))
		));
	}

	public static XclipsenIrcBridgeClient getInstance() {
		return instance;
	}

	public BridgeConfig config() {
		return config;
	}

	public BackendStatusSnapshot backendStatus() {
		return backendBridge.status();
	}

	public BackendStatusSnapshot testBackendConnection() {
		return backendBridge.testConnection();
	}

	public java.nio.file.Path configPath() {
		return configManager.path();
	}

	public void saveAndApplyConfig(BridgeConfig config) throws java.io.IOException {
		configManager.save(config);
		this.config = config;
		backendBridge.start(config);
	}

	private int openConfigScreen(CommandContext<FabricClientCommandSource> context) {
		openConfigScreen(MinecraftClient.getInstance());
		return 1;
	}

	private void handleEndTick(MinecraftClient client) {
		while (openConfigKey.wasPressed()) {
			openConfigScreen(client);
		}

		auctionHouseUnderbidFeature.handleEndTick(client, config);
		autoExperimentsFeature.handleEndTick(client, config);
	}

	private void openConfigScreen(MinecraftClient client) {
		client.execute(() -> client.setScreen(new XclipsenConfigScreen(client.currentScreen, this)));
	}

	private int reloadConfig(CommandContext<FabricClientCommandSource> context) {
		config = configManager.load();
		backendBridge.start(config);
		context.getSource().sendFeedback(Text.literal("IRC bridge config reloaded: " + configManager.path()));
		return 1;
	}

	private int showStatus(CommandContext<FabricClientCommandSource> context) {
		BackendStatusSnapshot status = backendBridge.testConnection();
		context.getSource().sendFeedback(Text.literal(formatStatus(status)));
		return 1;
	}

	private int sendIrcMessage(CommandContext<FabricClientCommandSource> context) {
		String message = StringArgumentType.getString(context, "message").trim();

		if (message.isBlank()) {
			context.getSource().sendError(Text.literal("Message must not be empty."));
			return 0;
		}

		String playerName = MinecraftClient.getInstance().getSession().getUsername();
		BackendLinkStatusResponse linkStatus = backendBridge.getLinkStatus(playerName);
		if (!linkStatus.linked) {
			if (!ircLinkWarningShown) {
				context.getSource().sendError(Text.literal(linkStatus.error.isBlank()
					? "You are not linked yet. Use /link start on Discord and /link CODE in Minecraft."
					: linkStatus.error));
				ircLinkWarningShown = true;
			}
			return 0;
		}

		ircLinkWarningShown = false;
		cacheLinkedDisplayName(linkStatus);
		backendBridge.sendIrcMessage(playerName, message);
		return 1;
	}

	private int showLinkedStatus(CommandContext<FabricClientCommandSource> context) {
		String playerName = MinecraftClient.getInstance().getSession().getUsername();
		BackendLinkStatusResponse status = backendBridge.getLinkStatus(playerName);

		if (!status.linked) {
			context.getSource().sendFeedback(Text.literal(status.error.isBlank()
				? "Not linked. Use /link start on Discord, then /link CODE here."
				: status.error));
			return 1;
		}

		ircLinkWarningShown = false;
		cacheLinkedDisplayName(status);
		context.getSource().sendFeedback(Text.literal("Linked usernames: " + String.join(", ", status.minecraftUsernames)));
		return 1;
	}

	private int completeLink(CommandContext<FabricClientCommandSource> context) {
		String playerName = MinecraftClient.getInstance().getSession().getUsername();
		String code = StringArgumentType.getString(context, "code");
		BackendLinkStatusResponse status = backendBridge.completeLink(playerName, code);

		if (!status.error.isBlank() || !status.linked) {
			context.getSource().sendError(Text.literal(status.error.isBlank() ? "Link failed." : status.error));
			return 0;
		}

		ircLinkWarningShown = false;
		cacheLinkedDisplayName(status);
		context.getSource().sendFeedback(Text.literal("Linked successfully: " + String.join(", ", status.minecraftUsernames)));
		return 1;
	}

	private void cacheLinkedDisplayName(BackendLinkStatusResponse status) {
		String displayName = status != null ? status.discordDisplayName : "";
		if (displayName == null || displayName.isBlank() || displayName.equals(config.linkedDiscordDisplayName)) {
			return;
		}

		config.linkedDiscordDisplayName = displayName;
		try {
			configManager.save(config);
		} catch (java.io.IOException exception) {
			LOGGER.warn("Failed to persist linked Discord display name.", exception);
		}
	}

	public static String formatStatus(BackendStatusSnapshot status) {
		StringBuilder builder = new StringBuilder("IRC backend status: ").append(status.state());

		if (status.lastHttpStatus() >= 0) {
			builder.append(" | HTTP ").append(status.lastHttpStatus());
		}

		if (status.lastSuccessAt() > 0L) {
			builder.append(" | last success ").append(secondsAgo(status.lastSuccessAt())).append("s ago");
		}

		if (status.lastMessageAt() > 0L) {
			builder.append(" | last msg ").append(secondsAgo(status.lastMessageAt())).append("s ago");
		}

		if (!status.lastError().isBlank()) {
			builder.append(" | ").append(status.lastError());
		}

		return builder.toString();
	}

	private static long secondsAgo(long timestamp) {
		return Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
	}
}
