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
	private val pausedIncomingMessages: Deque<PausedIncomingMessage> = ArrayDeque()

	@Synchronized
	fun configure(config: BridgeConfig) {
		this.config = config
	}

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

	fun relayCoopChat(localPlayerName: String, coopPlayerName: String, message: String) {
		val safeForwarder = sanitizeInline(localPlayerName, MAX_NAME_LENGTH)
		val safeAuthor = sanitizeInline(coopPlayerName, MAX_NAME_LENGTH)
		val safeMessage = sanitizeInline(message, MAX_OUTGOING_MESSAGE_LENGTH)
		if (safeForwarder.isBlank() || safeAuthor.isBlank() || safeMessage.isBlank()) {
			return
		}

		val outgoing = BackendOutgoingMessage().apply {
			type = "coop"
			playerName = safeForwarder
			this.forwardedPlayerName = safeAuthor
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

	/**
	 * Fetches current Bazaar prices from the bot backend.
	 * Returns null when the backend is unreachable or not configured.
	 * Must NOT be called on the main thread — blocks until the HTTP response arrives.
	 */
	fun fetchSkyblockPrices(): BackendPricePayload? {
		if (config.backendBaseUrl.isBlank() || config.backendAuthToken.isBlank()) return null

		return try {
			val request = requestBuilder(backendUrl("/api/skyblock/prices")).GET().build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			if (response.statusCode() != 200) {
				logger.debug("Price fetch returned HTTP {}", response.statusCode())
				null
			} else {
				GSON.fromJson(response.body(), BackendPricePayload::class.java)
			}
		} catch (exception: IOException) {
			logger.debug("Price fetch failed: {}", exception.message)
			null
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		}
	}

	fun uploadMobModelState(playerName: String, snapshot: BackendMobModelState): Boolean {
		if (config.backendBaseUrl.isBlank() || config.backendAuthToken.isBlank()) {
			return false
		}

		val safePlayerName = sanitizeInline(playerName, MAX_NAME_LENGTH)
		if (safePlayerName.isBlank()) {
			return false
		}

		val outgoing = BackendMobModelState().apply {
			minecraftUsername = safePlayerName
			enabled = snapshot.enabled
			entityType = sanitizeInline(snapshot.entityType, 64).lowercase()
			variant = sanitizeInline(snapshot.variant, 96).lowercase()
			baby = snapshot.baby
			scale = snapshot.scale.coerceIn(0.25f, 4.0f)
			updatedAt = snapshot.updatedAt.coerceAtLeast(0L)
		}

		return try {
			val request = requestBuilder(backendUrl("/api/mob-model"))
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(outgoing)))
				.build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()
			response.statusCode() in 200..299
		} catch (exception: IOException) {
			logger.debug("Mob model upload failed", exception)
			false
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			false
		}
	}

	fun fetchMobModelStates(playerName: String): BackendMobModelStatesResponse? {
		if (config.backendBaseUrl.isBlank() || config.backendAuthToken.isBlank()) {
			return null
		}

		val safePlayerName = sanitizeInline(playerName, MAX_NAME_LENGTH)
		if (safePlayerName.isBlank()) {
			return null
		}

		return try {
			val request = requestBuilder(
				backendUrl("/api/mob-models?playerName=" + URLEncoder.encode(safePlayerName, StandardCharsets.UTF_8)),
			).GET().build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()
			if (response.statusCode() != 200) {
				null
			} else {
				GSON.fromJson(response.body(), BackendMobModelStatesResponse::class.java)
			}
		} catch (exception: IOException) {
			logger.debug("Mob model fetch failed", exception)
			null
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		}
	}

	fun uploadHideonleafStats(playerName: String, snapshot: BackendHideonleafStatsUpload): Boolean {
		if (config.backendBaseUrl.isBlank() || config.backendAuthToken.isBlank()) {
			return false
		}

		val safePlayerName = sanitizeInline(playerName, MAX_NAME_LENGTH)
		if (safePlayerName.isBlank()) {
			return false
		}

		val outgoing = BackendHideonleafStatsUpload().apply {
			this.playerName = safePlayerName
			kills = snapshot.kills.coerceAtLeast(0L)
			totalShards = snapshot.totalShards.coerceAtLeast(0L)
			totalProfit = snapshot.totalProfit.coerceAtLeast(0.0)
			profitPerHour = snapshot.profitPerHour.coerceAtLeast(0.0)
			totalDurationMs = snapshot.totalDurationMs.coerceAtLeast(0L)
			updatedAt = snapshot.updatedAt.coerceAtLeast(0L)
			items = snapshot.items.mapValues { (_, item) ->
				BackendHideonleafTrackedItem().also { mapped ->
					mapped.amount = item.amount.coerceAtLeast(0L)
					mapped.timesDropped = item.timesDropped.coerceAtLeast(0L)
					mapped.pricePerUnit = item.pricePerUnit.coerceAtLeast(0.0)
				}
			}.toMutableMap()
		}

		return try {
			val request = requestBuilder(backendUrl("/api/hideonleaf"))
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(outgoing)))
				.build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()
			response.statusCode() in 200..299
		} catch (exception: IOException) {
			logger.debug("Hideonleaf stats upload failed", exception)
			false
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			false
		}
	}

	fun fetchHideonleafStats(playerName: String): BackendHideonleafStatsUpload? {
		if (config.backendBaseUrl.isBlank() || config.backendAuthToken.isBlank()) {
			return null
		}

		val safePlayerName = sanitizeInline(playerName, MAX_NAME_LENGTH)
		if (safePlayerName.isBlank()) {
			return null
		}

		return try {
			val request = requestBuilder(
				backendUrl("/api/hideonleaf/status?playerName=" + URLEncoder.encode(safePlayerName, StandardCharsets.UTF_8)),
			).GET().build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()
			if (response.statusCode() != 200) {
				null
			} else {
				GSON.fromJson(response.body(), BackendHideonleafStatsUpload::class.java)
			}
		} catch (exception: IOException) {
			logger.debug("Hideonleaf stats fetch failed", exception)
			null
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			null
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

	@Synchronized
	fun discardBacklogOnNextPoll() {
		lastSeenMessageId = 0L
		backlogInitialized = false
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
					"status" -> safeContent
					"event" -> TextFormatter.apply(
						config.ircCommandFormat,
						"%player%", safeTitle.ifBlank { safeUser },
						"%message%", safeContent,
					)
					else -> TextFormatter.apply(
						config.ircCommandFormat,
						"%player%", safeUser,
						"%message%", safeContent,
					)
				}

				if (incomingMessagesEnabled) {
					val styledMessage = styleBridgeMessage(formatted)
					val isIrcMessage = message.source == "irc" || message.source == "discord" || message.source == "coop"
					if (previewHoverPaused) {
						synchronized(pausedIncomingMessages) {
							pausedIncomingMessages.addLast(PausedIncomingMessage(styledMessage, isIrcMessage))
							while (pausedIncomingMessages.size > MAX_PAUSED_INCOMING_MESSAGES) {
								pausedIncomingMessages.removeFirst()
							}
						}
					} else {
						if (isIrcMessage) {
							IrcChatTabManager.addIrcMessage(styledMessage)
						}
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
		val styledMessage = styleBridgeMessage(formatted)
		IrcChatTabManager.addIrcMessage(styledMessage)
		showClientMessage(client, styledMessage)
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
		val drained = mutableListOf<PausedIncomingMessage>()
		synchronized(pausedIncomingMessages) {
			while (pausedIncomingMessages.isNotEmpty()) {
				drained.add(pausedIncomingMessages.removeFirst())
			}
		}

		for (message in drained) {
			if (message.isIrcMessage) {
				IrcChatTabManager.addIrcMessage(message.content)
			}
			showClientMessage(client, message.content)
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

	private data class PausedIncomingMessage(
		val content: Text,
		val isIrcMessage: Boolean,
	)

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
