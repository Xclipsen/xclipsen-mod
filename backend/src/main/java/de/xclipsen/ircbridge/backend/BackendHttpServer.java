package de.xclipsen.ircbridge.backend;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class BackendHttpServer {
	private static final Gson GSON = new Gson();

	private final Logger logger;
	private final BackendConfig config;
	private final MessageBuffer buffer;
	private final DiscordGateway discordGateway;

	private HttpServer server;

	public BackendHttpServer(Logger logger, BackendConfig config, MessageBuffer buffer, DiscordGateway discordGateway) {
		this.logger = logger;
		this.config = config;
		this.buffer = buffer;
		this.discordGateway = discordGateway;
	}

	public void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(config.bindHost, config.listenPort), 0);
		server.createContext("/health", this::handleHealth);
		server.createContext("/api/messages", this::handleMessages);
		server.setExecutor(null);
		server.start();
		logger.info("Backend HTTP server listening on {}:{}", config.bindHost, config.listenPort);
	}

	public void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
	}

	private void handleHealth(HttpExchange exchange) throws IOException {
		writeJson(exchange, 200, "{\"status\":\"ok\"}");
	}

	private void handleMessages(HttpExchange exchange) throws IOException {
		if (!isAuthorized(exchange)) {
			writeJson(exchange, 401, "{\"error\":\"unauthorized\"}");
			return;
		}

		if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			long after = parseAfter(exchange.getRequestURI().getQuery());
			writeJson(exchange, 200, GSON.toJson(new MessagesResponse(buffer.after(after))));
			return;
		}

		if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
				OutgoingMinecraftMessage payload = GSON.fromJson(reader, OutgoingMinecraftMessage.class);

				if (payload == null || payload.message == null || payload.message.isBlank()) {
					writeJson(exchange, 400, "{\"error\":\"invalid payload\"}");
					return;
				}

				if ("irc".equalsIgnoreCase(payload.type)) {
					buffer.add("irc", payload.playerName, payload.message);
				} else if ("status".equalsIgnoreCase(payload.type)) {
					buffer.add("status", "system", payload.message);
				}

				discordGateway.sendMinecraftMessage(payload);
				writeJson(exchange, 202, "{\"status\":\"accepted\"}");
				return;
			}
		}

		writeJson(exchange, 405, "{\"error\":\"method not allowed\"}");
	}

	private boolean isAuthorized(HttpExchange exchange) {
		String auth = exchange.getRequestHeaders().getFirst("Authorization");
		return auth != null && auth.equals("Bearer " + config.authToken);
	}

	private static long parseAfter(String query) {
		if (query == null || query.isBlank()) {
			return 0L;
		}

		for (String part : query.split("&")) {
			int separator = part.indexOf('=');

			if (separator <= 0) {
				continue;
			}

			if ("after".equals(part.substring(0, separator))) {
				try {
					return Long.parseLong(part.substring(separator + 1));
				} catch (NumberFormatException ignored) {
					return 0L;
				}
			}
		}

		return 0L;
	}

	private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);

		try (OutputStream output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}
}
