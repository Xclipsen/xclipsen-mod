package de.xclipsen.ircbridge;

import com.google.gson.Gson;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ClientBackendBridgeService {
	private static final Gson GSON = new Gson();
	private static final int MAX_MESSAGE_LENGTH = 280;
	private static final int MAX_NAME_LENGTH = 32;
	private static final long LOCAL_ECHO_TTL_MS = 10_000L;
	private static final int MAX_LOCAL_ECHOES = 32;

	private final Logger logger;
	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final ExecutorService outboundExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "xclipsen-client-backend-send");
		thread.setDaemon(true);
		return thread;
	});

	private ScheduledExecutorService scheduler;
	private BridgeConfig config = new BridgeConfig();
	private long lastSeenMessageId;
	private volatile String state = "stopped";
	private volatile int lastHttpStatus = -1;
	private volatile long lastSuccessAt;
	private volatile long lastPollAt;
	private volatile long lastMessageAt;
	private volatile String lastError = "";
	private volatile boolean announcedConnected;
	private final Deque<PendingLocalEcho> pendingLocalEchoes = new ArrayDeque<>();

	public ClientBackendBridgeService(Logger logger) {
		this.logger = logger;
	}

	public synchronized void start(BridgeConfig config) {
		stop();
		this.config = config;
		this.lastSeenMessageId = 0L;
		this.lastHttpStatus = -1;
		this.lastSuccessAt = 0L;
		this.lastPollAt = 0L;
		this.lastMessageAt = 0L;
		this.lastError = "";
		this.announcedConnected = false;

		if (config.backendBaseUrl.isBlank() || config.backendAuthToken.isBlank()) {
			state = "disabled";
			lastError = "Backend URL or auth token missing.";
			logger.warn("Client backend bridge disabled because backend URL or auth token is missing.");
			return;
		}

		scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "xclipsen-client-backend-poller");
			thread.setDaemon(true);
			return thread;
		});

		long interval = Math.max(500L, config.backendPollIntervalMs);
		state = "starting";
		scheduler.execute(this::bootstrapLastSeenMessageId);
		scheduler.scheduleAtFixedRate(this::pollMessages, interval, interval, TimeUnit.MILLISECONDS);
		logger.info("Client backend bridge started with endpoint {}", config.backendBaseUrl);
	}

	public synchronized void stop() {
		if (scheduler != null) {
			scheduler.shutdownNow();
			scheduler = null;
		}

		state = "stopped";
	}

	public void sendIrcMessage(String playerName, String message) {
		String safePlayerName = sanitizeInline(playerName, MAX_NAME_LENGTH);
		String safeMessage = sanitizeInline(message, MAX_MESSAGE_LENGTH);
		if (safePlayerName.isBlank() || safeMessage.isBlank()) {
			return;
		}

		echoLocally(safeMessage);

		BackendOutgoingMessage outgoing = new BackendOutgoingMessage();
		outgoing.type = "irc";
		outgoing.playerName = safePlayerName;
		outgoing.message = safeMessage;
		outboundExecutor.execute(() -> postOutgoing(outgoing));
	}

	public BackendLinkStatusResponse getLinkStatus(String playerName) {
		String safePlayerName = sanitizeInline(playerName, MAX_NAME_LENGTH);
		if (safePlayerName.isBlank()) {
			BackendLinkStatusResponse response = new BackendLinkStatusResponse();
			response.error = "Minecraft username missing.";
			return response;
		}

		try {
			HttpRequest request = requestBuilder(
				backendUrl("/api/link/status?playerName=" + URLEncoder.encode(safePlayerName, StandardCharsets.UTF_8))
			).GET().build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			lastHttpStatus = response.statusCode();

			if (response.statusCode() != 200) {
				BackendLinkStatusResponse failure = new BackendLinkStatusResponse();
				failure.error = "Status returned HTTP " + response.statusCode();
				return failure;
			}

			BackendLinkStatusResponse payload = GSON.fromJson(response.body(), BackendLinkStatusResponse.class);
			return payload != null ? payload : new BackendLinkStatusResponse();
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}

			BackendLinkStatusResponse failure = new BackendLinkStatusResponse();
			failure.error = exception.getClass().getSimpleName() + ": " + safe(exception.getMessage());
			return failure;
		}
	}

	public BackendLinkStatusResponse completeLink(String playerName, String code) {
		String safePlayerName = sanitizeInline(playerName, MAX_NAME_LENGTH);
		String safeCode = sanitizeInline(code, 32).toUpperCase();
		BackendLinkCompleteRequest outgoing = new BackendLinkCompleteRequest();
		outgoing.playerName = safePlayerName;
		outgoing.code = safeCode;

		try {
			HttpRequest request = requestBuilder(backendUrl("/api/link/complete"))
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(outgoing)))
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			lastHttpStatus = response.statusCode();

			BackendLinkStatusResponse payload = GSON.fromJson(response.body(), BackendLinkStatusResponse.class);
			if (payload == null) {
				payload = new BackendLinkStatusResponse();
			}

			if (response.statusCode() >= 300 && payload.error.isBlank()) {
				payload.error = "Link returned HTTP " + response.statusCode();
			}

			return payload;
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}

			BackendLinkStatusResponse failure = new BackendLinkStatusResponse();
			failure.error = exception.getClass().getSimpleName() + ": " + safe(exception.getMessage());
			return failure;
		}
	}

	private void pollMessages() {
		MinecraftClient client = MinecraftClient.getInstance();
		String playerName = currentPlayerName(client);
		if (client == null || client.player == null || client.inGameHud == null || playerName.isBlank()) {
			return;
		}

		try {
			lastPollAt = System.currentTimeMillis();
			String query = backendUrl("/api/messages?after="
				+ URLEncoder.encode(Long.toString(lastSeenMessageId), StandardCharsets.UTF_8)
				+ "&playerName=" + URLEncoder.encode(playerName, StandardCharsets.UTF_8));
			HttpRequest request = requestBuilder(query).GET().build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			lastHttpStatus = response.statusCode();

			if (response.statusCode() != 200) {
				state = "error";
				lastError = "Poll returned HTTP " + response.statusCode();
				logger.warn("Backend poll returned HTTP {}", response.statusCode());
				return;
			}

			state = "connected";
			lastSuccessAt = System.currentTimeMillis();
			lastError = "";
			announceConnected(client);

			BackendMessagesResponse payload = GSON.fromJson(response.body(), BackendMessagesResponse.class);
			if (payload == null || payload.messages == null) {
				return;
			}

			for (BackendMessage message : payload.messages) {
				lastSeenMessageId = Math.max(lastSeenMessageId, message.id);

				String safeContent = sanitizeInline(message.content, MAX_MESSAGE_LENGTH);
				if (safeContent.isBlank()) {
					continue;
				}

				lastMessageAt = System.currentTimeMillis();

				String safeUser = sanitizeInline(message.user, MAX_NAME_LENGTH);
				String safeTitle = sanitizeInline(message.title, 64);

				if ("irc".equals(message.source) && shouldSuppressIncomingIrc(safeUser, safeContent)) {
					continue;
				}

				String formatted = switch (message.source) {
					case "irc" -> TextFormatter.apply(
						config.ircCommandFormat,
						"%player%", safeUser,
						"%message%", safeContent
					);
					case "event" -> TextFormatter.apply(
						config.eventPingFormat,
						"%event%", safeTitle,
						"%message%", safeContent
					);
					case "status" -> safeContent;
					default -> TextFormatter.apply(
						config.discordToMinecraftFormat,
						"%user%", safeUser,
						"%message%", safeContent
					);
				};

				showClientMessage(client, styleBridgeMessage(formatted));
			}
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
				return;
			}

			state = "error";
			lastError = exception.getClass().getSimpleName() + ": " + safe(exception.getMessage());
			logger.debug("Backend poll failed", exception);
		}
	}

	private void bootstrapLastSeenMessageId() {
		lastSeenMessageId = 0L;
	}

	private void postOutgoing(BackendOutgoingMessage outgoing) {
		try {
			HttpRequest request = requestBuilder(backendUrl("/api/messages"))
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(outgoing)))
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			lastHttpStatus = response.statusCode();

			if (response.statusCode() >= 300) {
				state = "error";
				lastError = response.statusCode() == 403 ? "Link required. Use /link first." : "Send returned HTTP " + response.statusCode();
				logger.warn("Backend send returned HTTP {}", response.statusCode());
				return;
			}

			state = "connected";
			lastSuccessAt = System.currentTimeMillis();
			lastError = "";
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
				return;
			}

			state = "error";
			lastError = exception.getClass().getSimpleName() + ": " + safe(exception.getMessage());
			logger.warn("Failed to send IRC message to backend", exception);
		}
	}

	public BackendStatusSnapshot status() {
		return new BackendStatusSnapshot(state, lastHttpStatus, lastSuccessAt, lastPollAt, lastMessageAt, lastError);
	}

	public BackendStatusSnapshot testConnection() {
		if (config.backendBaseUrl.isBlank() || config.backendAuthToken.isBlank()) {
			state = "disabled";
			lastError = "Backend URL or auth token missing.";
			return status();
		}

		try {
			lastPollAt = System.currentTimeMillis();
			HttpRequest request = requestBuilder(backendUrl("/health")).GET().build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			lastHttpStatus = response.statusCode();

			if (response.statusCode() != 200) {
				state = "error";
				lastError = "Health returned HTTP " + response.statusCode();
				return status();
			}

			HealthResponse payload = GSON.fromJson(response.body(), HealthResponse.class);
			if (payload == null || !"ok".equalsIgnoreCase(payload.status)) {
				state = "error";
				lastError = "Health payload invalid.";
				return status();
			}

			state = "connected";
			lastSuccessAt = System.currentTimeMillis();
			lastError = "";
			return status();
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}

			state = "error";
			lastError = exception.getClass().getSimpleName() + ": " + safe(exception.getMessage());
			return status();
		}
	}

	private HttpRequest.Builder requestBuilder(String url) {
		return HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(10))
			.header("Authorization", "Bearer " + config.backendAuthToken)
			.header("Content-Type", "application/json");
	}

	private static String safe(String value) {
		return value != null ? value : "";
	}

	private String backendUrl(String path) {
		return config.backendBaseUrl + path;
	}

	private void announceConnected(MinecraftClient client) {
		if (announcedConnected || client == null || client.player == null || client.inGameHud == null) {
			return;
		}

		announcedConnected = true;
		showClientMessage(client, Text.literal("[IRC] Connected to backend.").formatted(Formatting.GREEN));
	}

	private void echoLocally(String message) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.inGameHud == null) {
			return;
		}

		String displayName = sanitizeInline(config.linkedDiscordDisplayName, 64);
		if (displayName.isBlank()) {
			displayName = currentPlayerName(client);
		}

		synchronized (pendingLocalEchoes) {
			prunePendingLocalEchoes();
			pendingLocalEchoes.addLast(new PendingLocalEcho(displayName, message, System.currentTimeMillis() + LOCAL_ECHO_TTL_MS));
			while (pendingLocalEchoes.size() > MAX_LOCAL_ECHOES) {
				pendingLocalEchoes.removeFirst();
			}
		}

		String formatted = TextFormatter.apply(
			config.ircCommandFormat,
			"%player%", displayName,
			"%message%", message
		);
		showClientMessage(client, styleBridgeMessage(formatted));
	}

	private boolean shouldSuppressIncomingIrc(String user, String content) {
		synchronized (pendingLocalEchoes) {
			prunePendingLocalEchoes();
			for (PendingLocalEcho pending : pendingLocalEchoes) {
				if (pending.matches(user, content)) {
					pendingLocalEchoes.remove(pending);
					return true;
				}
			}
		}

		return false;
	}

	private void prunePendingLocalEchoes() {
		long now = System.currentTimeMillis();
		while (!pendingLocalEchoes.isEmpty() && pendingLocalEchoes.peekFirst().expiresAt() < now) {
			pendingLocalEchoes.removeFirst();
		}
	}

	private void showClientMessage(MinecraftClient client, Text message) {
		client.execute(() -> {
			if (client.player != null) {
				client.player.sendMessage(message, false);
			} else if (client.inGameHud != null) {
				client.inGameHud.getChatHud().addMessage(message);
			}
		});
	}

	private Text styleBridgeMessage(String formatted) {
		if (formatted.startsWith("[") && formatted.contains("]")) {
			int prefixEnd = formatted.indexOf(']') + 1;
			String prefix = formatted.substring(0, prefixEnd);
			String rest = formatted.substring(prefixEnd);

			MutableText text = Text.literal(prefix).formatted(Formatting.GREEN);
			if (!rest.isEmpty()) {
				text.append(Text.literal(rest).formatted(Formatting.WHITE));
			}

			return text;
		}

		return Text.literal(formatted);
	}

	private static String sanitizeInline(String value, int maxLength) {
		if (value == null) {
			return "";
		}

		StringBuilder builder = new StringBuilder(Math.min(value.length(), maxLength));
		for (int index = 0; index < value.length() && builder.length() < maxLength; index++) {
			char current = value.charAt(index);
			if (current == '\r' || current == '\n' || Character.isISOControl(current)) {
				if (builder.length() == 0 || builder.charAt(builder.length() - 1) == ' ') {
					continue;
				}

				builder.append(' ');
				continue;
			}

			builder.append(current);
		}

		return builder.toString().trim();
	}

	private static String currentPlayerName(MinecraftClient client) {
		if (client == null || client.getSession() == null) {
			return "";
		}

		return sanitizeInline(client.getSession().getUsername(), MAX_NAME_LENGTH);
	}

	private record PendingLocalEcho(String user, String content, long expiresAt) {
		private boolean matches(String otherUser, String otherContent) {
			return user.equals(otherUser) && content.equals(otherContent);
		}
	}
}
