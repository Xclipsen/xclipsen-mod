package de.xclipsen.ircbridge.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

public final class BackendMain {
	private static final Logger LOGGER = LoggerFactory.getLogger("xclipsen-discord-backend");

	private BackendMain() {
	}

	public static void main(String[] args) throws Exception {
		Path configPath = Paths.get(System.getProperty("user.home"), ".config", "xclipsen-irc-bridge-backend", "config.json");
		BackendConfigManager configManager = new BackendConfigManager(LOGGER, configPath);
		BackendConfig config = configManager.load();
		MessageBuffer buffer = new MessageBuffer(config.maxBufferedMessages);
		DiscordGateway discordGateway = new DiscordGateway(LOGGER, config, buffer);
		BackendHttpServer httpServer = new BackendHttpServer(LOGGER, config, buffer, discordGateway);

		discordGateway.start();
		httpServer.start();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			httpServer.stop();
			discordGateway.stop();
		}));

		LOGGER.info("Backend ready. Config path: {}", configPath);
		new CountDownLatch(1).await();
	}
}
