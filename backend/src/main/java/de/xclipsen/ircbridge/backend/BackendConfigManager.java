package de.xclipsen.ircbridge.backend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BackendConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Logger logger;
	private final Path path;

	public BackendConfigManager(Logger logger, Path path) {
		this.logger = logger;
		this.path = path;
	}

	public BackendConfig load() {
		try {
			Files.createDirectories(path.getParent());

			if (Files.notExists(path)) {
				BackendConfig defaults = new BackendConfig();
				save(defaults);
				logger.info("Created default backend config at {}", path);
				return defaults;
			}

			try (Reader reader = Files.newBufferedReader(path)) {
				BackendConfig config = GSON.fromJson(reader, BackendConfig.class);
				return config != null ? config : new BackendConfig();
			}
		} catch (IOException exception) {
			logger.error("Failed to load backend config {}", path, exception);
			return new BackendConfig();
		}
	}

	public void save(BackendConfig config) throws IOException {
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(config, writer);
		}
	}
}
