package de.xclipsen.ircbridge

import com.google.gson.Gson
import net.minecraft.client.MinecraftClient
import net.minecraft.text.MutableText
import net.minecraft.text.ClickEvent
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.slf4j.Logger
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.ArrayDeque
import java.util.Deque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class ClientBackendBridgeService(
	private val logger: Logger,
) {
	private val httpClient = HttpClient.newHttpClient()
	private val outboundExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "xclipsen-client-backend-send").apply { isDaemon = true }
	}

	private var scheduler: ScheduledExecutorService? = null
	private var config = BridgeConfig()
	private var lastSeenMessageId = 0L
	private var incomingMessagesEnabled = true
	private var previewHoverPaused = false

	@Volatile
	private var state = "stopped"

	@Volatile
	private var lastHttpStatus = -1

	@Volatile
	private var lastSuccessAt = 0L

	@Volatile
	private var lastPollAt = 0L

	@Volatile
	private var lastMessageAt = 0L

	@Volatile
	private var lastError = ""

	@Volatile
	private var announcedConnected = false

	@Volatile
	private var backlogInitialized = false

	private val pendingLocalEchoes: Deque<PendingLocalEcho> = ArrayDeque()
	private val pausedIncomingMessages: Deque<Text> = ArrayDeque()

	@Synchronized
	fun start(config: BridgeConfig) {
		stop()
		this.config = config
		lastSeenMessageId = 0L
		lastHttpStatus = -1
		lastSuccessAt = 0L
		lastPollAt = 0L
		lastMessageAt = 0L
		lastError = ""
		announcedConnected = false
		backlogInitialized = false

		if (config.backendBaseUrl.isBlank() || config.backendAuthToken.isBlank()) {
			state = "disabled"
			lastError = "Backend URL or auth token missing."
			logger.warn("Client backend bridge disabled because backend URL or auth token is missing.")
			return
		}

		scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "xclipsen-client-backend-poller").apply { isDaemon = true }
		}

		val interval = max(500L, config.backendPollIntervalMs)
		state = "starting"
		scheduler?.execute(::bootstrapLastSeenMessageId)
		scheduler?.scheduleAtFixedRate(::pollMessages, interval, interval, TimeUnit.MILLISECONDS)
		logger.info("Client backend bridge started with endpoint {}", config.backendBaseUrl)
	}

	@Synchronized
	fun stop() {
		scheduler?.shutdownNow()
		scheduler = null
		state = "stopped"
	}

	fun sendIrcMessage(playerName: String, message: String) {
		val safePlayerName = sanitizeInline(playerName, MAX_NAME_LENGTH)
		val safeMessage = sanitizeInline(message, MAX_OUTGOING_MESSAGE_LENGTH)
		if (safePlayerName.isBlank() || safeMessage.isBlank()) {
			return
		}

		echoLocally(safeMessage)

		val outgoing = BackendOutgoingMessage().apply {
			type = "irc"
			this.playerName = safePlayerName
			this.message = safeMessage
		}
		outboundExecutor.execute { postOutgoing(outgoing) }
	}

	fun setIncomingMessagesEnabled(enabled: Boolean) {
		incomingMessagesEnabled = enabled
	}

	fun setPreviewHoverPaused(paused: Boolean) {
		val shouldFlush: Boolean
		synchronized(pausedIncomingMessages) {
			shouldFlush = previewHoverPaused && !paused && pausedIncomingMessages.isNotEmpty()
			previewHoverPaused = paused
		}

		if (shouldFlush) {
			flushPausedIncomingMessages()
		}
	}

	fun getLinkStatus(playerName: String): BackendLinkStatusResponse {
		val safePlayerName = sanitizeInline(playerName, MAX_NAME_LENGTH)
		if (safePlayerName.isBlank()) {
			return BackendLinkStatusResponse().apply {
				error = "Minecraft username missing."
			}
		}

		return try {
			val request = requestBuilder(
				backendUrl("/api/link/status?playerName=" + URLEncoder.encode(safePlayerName, StandardCharsets.UTF_8)),
			).GET().build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()

			if (response.statusCode() != 200) {
				BackendLinkStatusResponse().apply {
					error = "Status returned HTTP ${response.statusCode()}"
				}
			} else {
				GSON.fromJson(response.body(), BackendLinkStatusResponse::class.java) ?: BackendLinkStatusResponse()
			}
		} catch (exception: IOException) {
			BackendLinkStatusResponse().apply {
				error = "${exception::class.java.simpleName}: ${safe(exception.message)}"
			}
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			BackendLinkStatusResponse().apply {
				error = "${exception::class.java.simpleName}: ${safe(exception.message)}"
			}
		}
	}

	fun completeLink(playerName: String, code: String): BackendLinkStatusResponse {
		val outgoing = BackendLinkCompleteRequest().apply {
			this.playerName = sanitizeInline(playerName, MAX_NAME_LENGTH)
			this.code = sanitizeInline(code, 32).uppercase()
		}

		return try {
			val request = requestBuilder(backendUrl("/api/link/complete"))
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(outgoing)))
				.build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()

			val payload = GSON.fromJson(response.body(), BackendLinkStatusResponse::class.java) ?: BackendLinkStatusResponse()
			if (response.statusCode() >= 300 && payload.error.isBlank()) {
				payload.error = "Link returned HTTP ${response.statusCode()}"
			}
			payload
		} catch (exception: IOException) {
			BackendLinkStatusResponse().apply {
				error = "${exception::class.java.simpleName}: ${safe(exception.message)}"
			}
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			BackendLinkStatusResponse().apply {
				error = "${exception::class.java.simpleName}: ${safe(exception.message)}"
			}
		}
	}

	private fun pollMessages() {
		val client = MinecraftClient.getInstance()
		val playerName = currentPlayerName(client)
		if (client == null || client.player == null || client.inGameHud == null || playerName.isBlank()) {
			return
		}

		try {
			lastPollAt = System.currentTimeMillis()
			val query = backendUrl(
				"/api/messages?after=" +
					URLEncoder.encode(lastSeenMessageId.toString(), StandardCharsets.UTF_8) +
					"&playerName=" + URLEncoder.encode(playerName, StandardCharsets.UTF_8),
			)
			val request = requestBuilder(query).GET().build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()

			if (response.statusCode() != 200) {
				state = "error"
				lastError = "Poll returned HTTP ${response.statusCode()}"
				logger.warn("Backend poll returned HTTP {}", response.statusCode())
				return
			}

			state = "connected"
			lastSuccessAt = System.currentTimeMillis()
			lastError = ""
			announceConnected(client)

			val payload = GSON.fromJson(response.body(), BackendMessagesResponse::class.java)
			if (payload?.messages == null) {
				return
			}

			if (!backlogInitialized) {
				for (message in payload.messages) {
					lastSeenMessageId = max(lastSeenMessageId, message.id)
				}
				backlogInitialized = true
				return
			}

			for (message in payload.messages) {
				lastSeenMessageId = max(lastSeenMessageId, message.id)

				val safeContent = sanitizeInline(message.content, MAX_INCOMING_MESSAGE_LENGTH)
				if (safeContent.isBlank()) {
					continue
				}

				lastMessageAt = System.currentTimeMillis()
				val safeUser = sanitizeInline(message.user, MAX_NAME_LENGTH)
				val safeTitle = sanitizeInline(message.title, 64)

				if (message.source == "irc" && shouldSuppressIncomingIrc(safeUser, safeContent)) {
					continue
				}

				val formatted = when (message.source) {
					"irc" -> TextFormatter.apply(
						config.ircCommandFormat,
						"%player%", safeUser,
						"%message%", safeContent,
					)
					"event" -> TextFormatter.apply(
						config.eventPingFormat,
						"%event%", safeTitle,
						"%message%", safeContent,
					)
					"status" -> safeContent
					else -> TextFormatter.apply(
						config.discordToMinecraftFormat,
						"%user%", safeUser,
						"%message%", safeContent,
					)
				}

				if (incomingMessagesEnabled) {
					val styledMessage = styleBridgeMessage(formatted)
					if (previewHoverPaused) {
						synchronized(pausedIncomingMessages) {
							pausedIncomingMessages.addLast(styledMessage)
							while (pausedIncomingMessages.size > MAX_PAUSED_INCOMING_MESSAGES) {
								pausedIncomingMessages.removeFirst()
							}
						}
					} else {
						showClientMessage(client, styledMessage)
					}
				}
			}
		} catch (exception: IOException) {
			state = "error"
			lastError = "${exception::class.java.simpleName}: ${safe(exception.message)}"
			logger.debug("Backend poll failed", exception)
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
		}
	}

	private fun bootstrapLastSeenMessageId() {
		lastSeenMessageId = 0L
		backlogInitialized = false
	}

	private fun postOutgoing(outgoing: BackendOutgoingMessage) {
		try {
			val request = requestBuilder(backendUrl("/api/messages"))
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(outgoing)))
				.build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()

			if (response.statusCode() >= 300) {
				state = "error"
				lastError = if (response.statusCode() == 403) "Link required. Use /link first." else "Send returned HTTP ${response.statusCode()}"
				logger.warn("Backend send returned HTTP {}", response.statusCode())
				return
			}

			state = "connected"
			lastSuccessAt = System.currentTimeMillis()
			lastError = ""
		} catch (exception: IOException) {
			state = "error"
			lastError = "${exception::class.java.simpleName}: ${safe(exception.message)}"
			logger.warn("Failed to send IRC message to backend", exception)
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
		}
	}

	fun status(): BackendStatusSnapshot =
		BackendStatusSnapshot(state, lastHttpStatus, lastSuccessAt, lastPollAt, lastMessageAt, lastError)

	fun testConnection(): BackendStatusSnapshot {
		return testConnection(config)
	}

	fun testConnection(configOverride: BridgeConfig): BackendStatusSnapshot {
		if (configOverride.backendBaseUrl.isBlank() || configOverride.backendAuthToken.isBlank()) {
			state = "disabled"
			lastError = "Backend URL or auth token missing."
			return status()
		}

		return try {
			lastPollAt = System.currentTimeMillis()
			val request = requestBuilder(backendUrl("/health", configOverride), configOverride).GET().build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()

			if (response.statusCode() != 200) {
				state = "error"
				lastError = "Health returned HTTP ${response.statusCode()}"
				status()
			} else {
				val payload = GSON.fromJson(response.body(), HealthResponse::class.java)
				if (payload?.status?.equals("ok", ignoreCase = true) != true) {
					state = "error"
					lastError = "Health payload invalid."
				} else {
					state = "connected"
					lastSuccessAt = System.currentTimeMillis()
					lastError = ""
				}
				status()
			}
		} catch (exception: IOException) {
			state = "error"
			lastError = "${exception::class.java.simpleName}: ${safe(exception.message)}"
			status()
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			state = "error"
			lastError = "${exception::class.java.simpleName}: ${safe(exception.message)}"
			status()
		}
	}

	private fun requestBuilder(url: String, configOverride: BridgeConfig = config): HttpRequest.Builder =
		HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(10))
			.header("Authorization", "Bearer ${configOverride.backendAuthToken}")
			.header("Content-Type", "application/json")

	private fun backendUrl(path: String, configOverride: BridgeConfig = config): String = configOverride.backendBaseUrl + path

	private fun announceConnected(client: MinecraftClient?) {
		if (announcedConnected || client?.player == null || client.inGameHud == null) {
			return
		}

		announcedConnected = true
		showClientMessage(client, Text.literal("[IRC] Connected to backend.").formatted(Formatting.GREEN))
	}

	private fun echoLocally(message: String) {
		val client = MinecraftClient.getInstance()
		if (client?.inGameHud == null) {
			return
		}

		var displayName = sanitizeInline(config.linkedDiscordDisplayName, 64)
		if (displayName.isBlank()) {
			displayName = currentPlayerName(client)
		}

		synchronized(pendingLocalEchoes) {
			prunePendingLocalEchoes()
			pendingLocalEchoes.addLast(PendingLocalEcho(displayName, message, System.currentTimeMillis() + LOCAL_ECHO_TTL_MS))
			while (pendingLocalEchoes.size > MAX_LOCAL_ECHOES) {
				pendingLocalEchoes.removeFirst()
			}
		}

		val formatted = TextFormatter.apply(
			config.ircCommandFormat,
			"%player%", displayName,
			"%message%", message,
		)
		showClientMessage(client, styleBridgeMessage(formatted))
	}

	private fun shouldSuppressIncomingIrc(user: String, content: String): Boolean {
		synchronized(pendingLocalEchoes) {
			prunePendingLocalEchoes()
			val iterator = pendingLocalEchoes.iterator()
			while (iterator.hasNext()) {
				val pending = iterator.next()
				if (pending.matches(user, content)) {
					iterator.remove()
					return true
				}
			}
		}

		return false
	}

	private fun prunePendingLocalEchoes() {
		val now = System.currentTimeMillis()
		while (pendingLocalEchoes.isNotEmpty() && pendingLocalEchoes.peekFirst().expiresAt < now) {
			pendingLocalEchoes.removeFirst()
		}
	}

	private fun showClientMessage(client: MinecraftClient?, message: Text) {
		client?.execute {
			when {
				client.player != null -> client.player?.sendMessage(message, false)
				client.inGameHud != null -> client.inGameHud.chatHud.addMessage(message)
			}
		}
	}

	private fun flushPausedIncomingMessages() {
		val client = MinecraftClient.getInstance() ?: return
		val drained = mutableListOf<Text>()
		synchronized(pausedIncomingMessages) {
			while (pausedIncomingMessages.isNotEmpty()) {
				drained.add(pausedIncomingMessages.removeFirst())
			}
		}

		for (message in drained) {
			showClientMessage(client, message)
		}
	}

	private fun styleBridgeMessage(formatted: String): Text {
		if (formatted.startsWith("[") && formatted.contains("]")) {
			val prefixEnd = formatted.indexOf(']') + 1
			val prefix = formatted.substring(0, prefixEnd)
			val rest = formatted.substring(prefixEnd)

			val text: MutableText = Text.literal(prefix).formatted(Formatting.GREEN)
			if (rest.isNotEmpty()) {
				appendLinkedText(text, rest, Style.EMPTY.withColor(Formatting.WHITE))
			}
			return text
		}

		return buildLinkedText(formatted, Style.EMPTY)
	}

	private fun buildLinkedText(content: String, defaultStyle: Style): MutableText {
		val root = Text.empty()
		appendLinkedText(root, content, defaultStyle)
		return root
	}

	private fun appendLinkedText(target: MutableText, content: String, defaultStyle: Style) {
		var cursor = 0

		for (match in URL_PATTERN.findAll(content)) {
			if (match.range.first > cursor) {
				target.append(Text.literal(content.substring(cursor, match.range.first)).setStyle(defaultStyle))
			}

			val rawUrl = match.value
			val normalizedUrl = trimTrailingUrlPunctuation(rawUrl)
			val trailing = rawUrl.substring(normalizedUrl.length)

			if (normalizedUrl.isNotBlank()) {
				target.append(
					Text.literal(normalizedUrl).setStyle(
						defaultStyle
							.withUnderline(true)
							.withColor(Formatting.AQUA)
							.withClickEvent(ClickEvent.OpenUrl(URI.create(normalizedUrl))),
					),
				)
			}

			if (trailing.isNotEmpty()) {
				target.append(Text.literal(trailing).setStyle(defaultStyle))
			}

			cursor = match.range.last + 1
		}

		if (cursor < content.length) {
			target.append(Text.literal(content.substring(cursor)).setStyle(defaultStyle))
		}
	}

	private fun trimTrailingUrlPunctuation(url: String): String {
		var end = url.length
		while (end > 0 && TRAILING_URL_PUNCTUATION.indexOf(url[end - 1]) >= 0) {
			end--
		}
		return url.substring(0, end)
	}

	private data class PendingLocalEcho(
		val user: String,
		val content: String,
		val expiresAt: Long,
	) {
		fun matches(otherUser: String, otherContent: String): Boolean =
			user == otherUser && content == otherContent
	}

	companion object {
		private val GSON = Gson()
		private val URL_PATTERN = Regex("""https?://\S+""")
		private const val TRAILING_URL_PUNCTUATION = ".,!?;:)]}"
		private const val MAX_OUTGOING_MESSAGE_LENGTH = 280
		private const val MAX_INCOMING_MESSAGE_LENGTH = 2048
		private const val MAX_NAME_LENGTH = 32
		private const val LOCAL_ECHO_TTL_MS = 10_000L
		private const val MAX_LOCAL_ECHOES = 32
		private const val MAX_PAUSED_INCOMING_MESSAGES = 100

		private fun safe(value: String?): String = value ?: ""

		private fun sanitizeInline(value: String?, maxLength: Int): String {
			if (value == null) {
				return ""
			}

			val builder = StringBuilder(min(value.length, maxLength))
			for (current in value) {
				if (builder.length >= maxLength) {
					break
				}

				if (current == '\r' || current == '\n' || current.isISOControl()) {
					if (builder.isEmpty() || builder.last() == ' ') {
						continue
					}
					builder.append(' ')
					continue
				}

				builder.append(current)
			}

			return builder.toString().trim()
		}

		private fun currentPlayerName(client: MinecraftClient?): String {
			if (client?.session == null) {
				return ""
			}

			return sanitizeInline(client.session.username, MAX_NAME_LENGTH)
		}
	}
}
