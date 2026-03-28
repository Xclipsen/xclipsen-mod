package de.xclipsen.ircbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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
				BridgeConfig defaults = normalized(new BridgeConfig());
				save(defaults);
				logger.info("Created default config at {}", path);
				return defaults;
			}

			try (Reader reader = Files.newBufferedReader(path)) {
				BridgeConfig config = GSON.fromJson(reader, BridgeConfig.class);
				return normalized(config);
			}
		} catch (IOException exception) {
			logger.error("Failed to load config {}", path, exception);
			return normalized(new BridgeConfig());
		}
	}

	public void save(BridgeConfig config) throws IOException {
		Files.createDirectories(path.getParent());

		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(normalized(config), writer);
		}
	}

	public Path path() {
		return path;
	}

	private static BridgeConfig normalized(BridgeConfig config) {
		BridgeConfig value = config != null ? config : new BridgeConfig();
		value.backendBaseUrl = normalizeBackendBaseUrl(value.backendBaseUrl);
		value.backendAuthToken = safeString(value.backendAuthToken, "change-me");
		value.linkedDiscordDisplayName = normalizedTemplate(value.linkedDiscordDisplayName, "");
		value.discordToMinecraftFormat = normalizedTemplate(value.discordToMinecraftFormat, "[Discord] <%user%> %message%");
		value.ircCommandFormat = normalizedTemplate(value.ircCommandFormat, "[IRC] <%player%> %message%");
		value.eventPingFormat = normalizedTemplate(value.eventPingFormat, "[Event] %event%: %message%");
		value.backendPollIntervalMs = Math.max(500L, Math.min(60_000L, value.backendPollIntervalMs));
		value.autoExperimentsClickDelayMs = Math.max(0L, value.autoExperimentsClickDelayMs);
		value.autoExperimentsDelayVarianceMs = Math.max(0L, value.autoExperimentsDelayVarianceMs);
		value.autoExperimentsSerumCount = Math.max(0, Math.min(3, value.autoExperimentsSerumCount));
		return value;
	}

	private static String safeString(String value, String fallback) {
		return value != null ? value : fallback;
	}

	private static String normalizedTemplate(String value, String fallback) {
		String candidate = safeString(value, fallback)
			.replace('\r', ' ')
			.replace('\n', ' ')
			.trim();
		if (candidate.isBlank()) {
			return fallback;
		}

		return candidate.length() > 256 ? candidate.substring(0, 256) : candidate;
	}

	private static String normalizeBackendBaseUrl(String value) {
		String candidate = safeString(value, "http://127.0.0.1:8765").trim();
		if (candidate.isBlank()) {
			return "http://127.0.0.1:8765";
		}

		try {
			URI uri = URI.create(candidate);
			String scheme = safeString(uri.getScheme(), "").toLowerCase(Locale.ROOT);
			if (!"http".equals(scheme) && !"https".equals(scheme)) {
				return "http://127.0.0.1:8765";
			}

			String host = safeString(uri.getHost(), "");
			if (host.isBlank()) {
				return "http://127.0.0.1:8765";
			}

			URI normalized = new URI(
				scheme,
				uri.getUserInfo(),
				host,
				uri.getPort(),
				null,
				null,
				null
			);
			String output = normalized.toString();
			return output.endsWith("/") ? output.substring(0, output.length() - 1) : output;
		} catch (IllegalArgumentException | URISyntaxException exception) {
			return "http://127.0.0.1:8765";
		}
	}
}
