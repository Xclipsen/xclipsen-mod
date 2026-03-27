package de.xclipsen.ircbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BridgeConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Logger logger;
	private final Path path;

	public BridgeConfigManager(Logger logger) {
		this.logger = logger;
		this.path = FabricLoader.getInstance().getConfigDir().resolve("xclipsen-irc-bridge.json");
	}

	public BridgeConfig load() {
		try {
			Files.createDirectories(path.getParent());

			if (Files.notExists(path)) {
				BridgeConfig defaults = new BridgeConfig();
				save(defaults);
				logger.info("Created default config at {}", path);
				return defaults;
			}

			try (Reader reader = Files.newBufferedReader(path)) {
				BridgeConfig config = GSON.fromJson(reader, BridgeConfig.class);
				return config != null ? config : new BridgeConfig();
			}
		} catch (IOException exception) {
			logger.error("Failed to load config {}", path, exception);
			return new BridgeConfig();
		}
	}

	public void save(BridgeConfig config) throws IOException {
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(config, writer);
		}
	}

	public Path path() {
		return path;
	}
}
