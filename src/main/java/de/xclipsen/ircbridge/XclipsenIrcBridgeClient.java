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
	private static XclipsenIrcBridgeClient instance;

	private final BridgeConfigManager configManager = new BridgeConfigManager(LOGGER);
	private final ClientBackendBridgeService backendBridge = new ClientBackendBridgeService(LOGGER);
	private final KeyBinding openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
		"key.xclipsen_irc_bridge.open_config",
		InputUtil.Type.KEYSYM,
		GLFW.GLFW_KEY_RIGHT_SHIFT,
		KeyBinding.Category.create(Identifier.of("xclipsen_irc_bridge", "general"))
	));

	private BridgeConfig config = new BridgeConfig();

	@Override
	public void onInitializeClient() {
		instance = this;
		config = configManager.load();
		backendBridge.start(config);

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> backendBridge.stop());
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
		backendBridge.sendIrcMessage(playerName, message);
		return 1;
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
