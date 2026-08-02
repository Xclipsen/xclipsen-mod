package de.xclipsen.ircbridge

import com.google.gson.Gson
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import org.slf4j.Logger
import java.io.IOException
import java.io.InputStream
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
import java.util.concurrent.atomic.AtomicLong
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
	private val serviceGeneration = AtomicLong()
	private var config = BridgeConfig()
	@Volatile
	private var modCredential: String? = null
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
	private var lastPollWarningAt = 0L

	@Volatile
	private var announcedConnected = false

	@Volatile
	private var backlogInitialized = false

	private val pendingLocalEchoes: Deque<PendingLocalEcho> = ArrayDeque()
	private val pausedIncomingMessages: Deque<PausedIncomingMessage> = ArrayDeque()

	@Synchronized
	fun configure(config: BridgeConfig) {
		this.config = config.copy()
	}

	@Synchronized
	fun configureModCredential(credential: String?) {
		modCredential = credential
	}

	@Synchronized
	fun start(config: BridgeConfig) {
		stop()
		this.config = config.copy()
		lastSeenMessageId = 0L
		lastHttpStatus = -1
		lastSuccessAt = 0L
		lastPollAt = 0L
		lastMessageAt = 0L
		lastError = ""
		lastPollWarningAt = 0L
		announcedConnected = false
		backlogInitialized = false

		if (normalizeIrcServerBaseUrl(config.ircServerBaseUrl) == null || !isValidIrcAuthToken(config.backendAuthToken)) {
			state = "disabled"
			lastError = "IRC server URL must use HTTPS, except for loopback development, and the auth token must be valid."
			logger.warn("Client backend bridge disabled because its endpoint or auth token is invalid.")
			return
		}

		scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "xclipsen-client-backend-poller").apply { isDaemon = true }
		}

		val interval = max(500L, config.backendPollIntervalMs)
		val generation = serviceGeneration.incrementAndGet()
		val configSnapshot = this.config.copy()
		state = "starting"
		scheduler?.execute { bootstrapLastSeenMessageId(generation) }
		scheduler?.scheduleAtFixedRate({ pollMessages(generation, configSnapshot) }, interval, interval, TimeUnit.MILLISECONDS)
		logger.info("Client backend bridge started with IRC endpoint {}", config.ircServerBaseUrl)
	}

	@Synchronized
	fun stop() {
		serviceGeneration.incrementAndGet()
		scheduler?.shutdownNow()
		scheduler = null
		synchronized(pendingLocalEchoes) { pendingLocalEchoes.clear() }
		synchronized(pausedIncomingMessages) { pausedIncomingMessages.clear() }
		state = "stopped"
	}

	fun shutdown() {
		stop()
		outboundExecutor.shutdownNow()
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
		val generation = serviceGeneration.get()
		val configSnapshot = config.copy()
		outboundExecutor.execute { postOutgoing(outgoing, generation, configSnapshot) }
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
		val generation = serviceGeneration.get()
		val configSnapshot = config.copy()
		outboundExecutor.execute { postOutgoing(outgoing, generation, configSnapshot) }
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

	fun getLinkStatus(): BackendLinkStatusResponse {
		val requestBuilder = authenticatedModBackendRequestBuilder(modBackendUrl("/api/auth/link-status"))
		if (requestBuilder == null) {
			return BackendLinkStatusResponse().apply {
				error = "Not linked. Verify your account with /link on Discord, then use /xclipsen link <code>."
			}
		}

		return try {
			val request = requestBuilder.GET().build()
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
		} catch (_: RuntimeException) {
			BackendLinkStatusResponse().apply { error = "Link status response was invalid." }
		}
	}

	/**
	 * Fetches current Bazaar prices from the bot backend.
	 * Returns null when the backend is unreachable or not configured.
	 * Must NOT be called on the main thread — blocks until the HTTP response arrives.
	 */
	fun fetchSkyblockPrices(): BackendPricePayload? {
		if (activeModBackendBaseUrl(config).isBlank()) return null

		return try {
			val request = modBackendRequestBuilder(modBackendUrl("/api/skyblock/prices")).GET().build()
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

	fun fetchHighClassDiceTracker(): BackendHighClassDiceTrackerResponse? {
		if (activeModBackendBaseUrl(config).isBlank()) return null

		return try {
			val request = modBackendRequestBuilder(modBackendUrl("/api/skyblock/auction-trackers/highclass")).GET().build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()
			if (response.statusCode() !in 200..299) {
				logger.debug("High Class Dice tracker fetch returned HTTP {}", response.statusCode())
				null
			} else {
				GSON.fromJson(response.body(), BackendHighClassDiceTrackerResponse::class.java)
			}
		} catch (exception: IOException) {
			logger.debug("High Class Dice tracker fetch failed: {}", exception.message)
			null
		} catch (exception: RuntimeException) {
			logger.debug("High Class Dice tracker response could not be parsed", exception)
			null
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		}
	}

	fun fetchSlayerRngMeterDrops(): BackendSlayerRngMeterResponse? {
		if (activeModBackendBaseUrl(config).isBlank()) return null

		return try {
			val request = modBackendRequestBuilder(modBackendUrl("/api/skyblock/slayer-rng-meter")).GET().build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			if (response.statusCode() != 200) {
				logger.debug("Slayer RNG meter fetch returned HTTP {}", response.statusCode())
				null
			} else {
				GSON.fromJson(response.body(), BackendSlayerRngMeterResponse::class.java)
			}
		} catch (exception: IOException) {
			logger.debug("Slayer RNG meter fetch failed: {}", exception.message)
			null
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		}
	}

	fun fetchDungeonStats(playerName: String): BackendDungeonStatsResponse? {
		if (activeModBackendBaseUrl(config).isBlank()) {
			return null
		}

		val safePlayerName = sanitizeInline(playerName, MAX_NAME_LENGTH)
		if (safePlayerName.isBlank()) {
			return null
		}

		return try {
			val request = modBackendRequestBuilder(
				modBackendUrl("/api/skyblock/dungeons/player?username=" + URLEncoder.encode(safePlayerName, StandardCharsets.UTF_8)),
			).GET().build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()
			val payload = GSON.fromJson(response.body(), BackendDungeonStatsResponse::class.java) ?: BackendDungeonStatsResponse()
			if (response.statusCode() in 200..299) {
				payload
			} else {
				payload.apply {
					if (error.isBlank()) {
						error = "Dungeon stats returned HTTP ${response.statusCode()}"
					}
				}
			}
		} catch (exception: IOException) {
			logger.debug("Dungeon stats fetch failed: {}", exception.message)
			null
		} catch (exception: RuntimeException) {
			logger.warn("Dungeon stats response could not be parsed", exception)
			BackendDungeonStatsResponse().apply {
				ok = false
				error = "Dungeon stats response was invalid. Make sure the Xclipsen backend is updated."
			}
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		}
	}

	fun fetchDungeonStats(players: Collection<String>): BackendDungeonPlayersResponse? {
		if (activeModBackendBaseUrl(config).isBlank()) {
			return null
		}

		val safePlayers = players
			.map { sanitizeInline(it, MAX_NAME_LENGTH) }
			.filter { it.isNotBlank() }
			.distinctBy { it.lowercase() }
			.take(MAX_BATCH_DUNGEON_PLAYERS)
		if (safePlayers.isEmpty()) {
			return BackendDungeonPlayersResponse().apply { ok = true }
		}

		return try {
			val request = modBackendRequestBuilder(
				modBackendUrl("/api/skyblock/dungeons/players?usernames=" + URLEncoder.encode(safePlayers.joinToString(","), StandardCharsets.UTF_8)),
			).GET().build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()
			val payload = GSON.fromJson(response.body(), BackendDungeonPlayersResponse::class.java) ?: BackendDungeonPlayersResponse()
			if (response.statusCode() in 200..299) {
				payload
			} else {
				payload.apply {
					if (error.isBlank()) {
						error = "Dungeon stats returned HTTP ${response.statusCode()}"
					}
				}
			}
		} catch (exception: IOException) {
			logger.debug("Batch dungeon stats fetch failed: {}", exception.message)
			null
		} catch (exception: RuntimeException) {
			logger.warn("Batch dungeon stats response could not be parsed", exception)
			BackendDungeonPlayersResponse().apply {
				ok = false
				error = "Batch dungeon stats response was invalid. Make sure the Xclipsen backend is updated."
			}
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		}
	}

	fun uploadMobModelState(snapshot: BackendMobModelState, sessionGeneration: Long = ClientSessionLifecycle.snapshot()): Boolean {
		if (activeModBackendBaseUrl(config).isBlank()) {
			return false
		}

		val outgoing = BackendMobModelState().apply {
			enabled = snapshot.enabled
			entityType = sanitizeInline(snapshot.entityType, 64).lowercase()
			variant = sanitizeInline(snapshot.variant, 96).lowercase()
			baby = snapshot.baby
			showArmor = snapshot.showArmor
			showHeldItems = snapshot.showHeldItems
			scale = snapshot.scale.coerceIn(0.25f, 4.0f)
			updatedAt = snapshot.updatedAt.coerceAtLeast(0L)
		}

		return try {
			val requestBuilder = authenticatedModBackendRequestBuilder(modBackendUrl("/api/mob-model"), sessionGeneration) ?: return false
			val body = GSON.toJsonTree(outgoing).asJsonObject.apply { remove("minecraftUsername") }
			val request = requestBuilder
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
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

	fun fetchMobModelStates(): BackendMobModelStatesResponse? {
		if (activeModBackendBaseUrl(config).isBlank()) {
			return null
		}

		return try {
			val request = (authenticatedModBackendRequestBuilder(modBackendUrl("/api/mob-models")) ?: return null).GET().build()
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

	fun mutateHideonleaf(
		mutationRequest: BackendHideonleafMutationRequest,
		sessionGeneration: Long = ClientSessionLifecycle.snapshot(),
	): BackendHideonleafMutationResult? {
		if (activeModBackendBaseUrl(config).isBlank()) {
			return null
		}

		return try {
			val requestBuilder = authenticatedModBackendRequestBuilder(modBackendUrl("/api/hideonleaf"), sessionGeneration) ?: return null
			val request = requestBuilder
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(mutationRequest)))
				.build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()
			val payload = if (response.statusCode() == 200 || response.statusCode() == 409) {
				GSON.fromJson(response.body(), BackendHideonleafMutationResponse::class.java)
			} else {
				null
			}
			BackendHideonleafMutationResult(response.statusCode(), payload)
		} catch (exception: IOException) {
			logger.debug("Hideonleaf mutation failed", exception)
			null
		} catch (exception: RuntimeException) {
			logger.warn("Hideonleaf mutation response could not be parsed", exception)
			null
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		}
	}

	fun fetchHideonleafStats(sessionGeneration: Long = ClientSessionLifecycle.snapshot()): BackendHideonleafState? {
		if (activeModBackendBaseUrl(config).isBlank()) {
			return null
		}

		return try {
			val request = (authenticatedModBackendRequestBuilder(modBackendUrl("/api/hideonleaf/status"), sessionGeneration) ?: return null).GET().build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()
			if (response.statusCode() != 200) {
				null
			} else {
				GSON.fromJson(response.body(), BackendHideonleafState::class.java)
			}
		} catch (exception: IOException) {
			logger.debug("Hideonleaf stats fetch failed", exception)
			null
		} catch (exception: RuntimeException) {
			logger.warn("Hideonleaf stats response could not be parsed", exception)
			null
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		}
	}

	fun fetchAuctionHousePrices(itemIds: List<String>): BackendAuctionHousePriceResponse? {
		val requested = itemIds.distinct()
		if (requested.isEmpty() || requested.size > MAX_AUCTION_PRICE_ITEMS || requested.any { !AUCTION_ITEM_ID_PATTERN.matches(it) }) {
			return null
		}
		val query = requested.joinToString("&") { "itemId=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" }
		return try {
			val request = modBackendRequestBuilder(modBackendUrl("/api/skyblock/auction-house/prices?$query"))
				.header("Accept", "application/json")
				.GET()
				.build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
			lastHttpStatus = response.statusCode()
			if (response.statusCode() != 200) {
				response.body().close()
				return null
			}
			val declaredSize = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
			if (declaredSize > MAX_AUCTION_PRICE_RESPONSE_BYTES) {
				response.body().close()
				return null
			}
			val body = response.body().use { readBounded(it, MAX_AUCTION_PRICE_RESPONSE_BYTES) }
			val payload = GSON.fromJson(body.toString(Charsets.UTF_8), BackendAuctionHousePriceResponse::class.java) ?: return null
			payload.takeIf { isValidAuctionPriceResponse(it, requested) }
		} catch (exception: IOException) {
			logger.debug("Auction House price fetch failed", exception)
			null
		} catch (exception: RuntimeException) {
			logger.warn("Auction House price response could not be parsed")
			null
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		}
	}

	fun completeLink(code: String): BackendLinkCompleteResponse {
		val outgoing = BackendLinkCompleteRequest().apply {
			this.code = sanitizeInline(code, 32)
		}
		if (!MOD_LINK_CODE_PATTERN.matches(outgoing.code)) {
			return BackendLinkCompleteResponse().apply { error = "Link code format is invalid." }
		}

		return try {
			val request = modBackendRequestBuilder(modBackendUrl("/api/auth/complete"))
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(outgoing)))
				.build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = response.statusCode()

			val payload = GSON.fromJson(response.body(), BackendLinkCompleteResponse::class.java) ?: BackendLinkCompleteResponse()
			if (response.statusCode() >= 300 && payload.error.isBlank()) {
				payload.error = "Link returned HTTP ${response.statusCode()}"
			}
			payload
		} catch (exception: IOException) {
			BackendLinkCompleteResponse().apply {
				error = "${exception::class.java.simpleName}: ${safe(exception.message)}"
			}
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			BackendLinkCompleteResponse().apply {
				error = "${exception::class.java.simpleName}: ${safe(exception.message)}"
			}
		} catch (_: RuntimeException) {
			BackendLinkCompleteResponse().apply { error = "Link response was invalid." }
		}
	}

	@Synchronized
	fun discardBacklogOnNextPoll() {
		lastSeenMessageId = 0L
		backlogInitialized = false
	}

	private fun pollMessages(generation: Long, configSnapshot: BridgeConfig) {
		if (serviceGeneration.get() != generation) return
		val client = Minecraft.getInstance()
		val playerName = currentPlayerName(client)
		if (client == null || client.player == null || client.gui == null || playerName.isBlank()) {
			return
		}

		try {
			lastPollAt = System.currentTimeMillis()
			val query = ircServerUrl(
				"/api/messages?after=" +
					URLEncoder.encode(lastSeenMessageId.toString(), StandardCharsets.UTF_8) +
					"&playerName=" + URLEncoder.encode(playerName, StandardCharsets.UTF_8),
				configOverride = configSnapshot,
			)
			val request = ircRequestBuilder(query, configSnapshot).GET().build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			if (serviceGeneration.get() != generation) return
			lastHttpStatus = response.statusCode()

			if (response.statusCode() != 200) {
				state = "error"
				lastError = "Poll returned HTTP ${response.statusCode()}"
				val now = System.currentTimeMillis()
				if (now - lastPollWarningAt >= POLL_WARNING_INTERVAL_MS) {
					lastPollWarningAt = now
					logger.warn("Backend poll returned HTTP {}", response.statusCode())
				}
				return
			}

			state = "connected"
			lastSuccessAt = System.currentTimeMillis()
			lastError = ""
			announceConnected(client, generation)

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
						configSnapshot.ircCommandFormat,
						"%player%", safeTitle.ifBlank { safeUser },
						"%message%", safeContent,
					)
					else -> TextFormatter.apply(
						configSnapshot.ircCommandFormat,
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
						showBridgeMessage(client, styledMessage, isIrcMessage, generation)
					}
				}
			}
		} catch (exception: IOException) {
			if (serviceGeneration.get() != generation) return
			state = "error"
			lastError = "${exception::class.java.simpleName}: ${safe(exception.message)}"
			logger.debug("Backend poll failed", exception)
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
		} catch (exception: RuntimeException) {
			if (serviceGeneration.get() != generation) return
			state = "error"
			lastError = "IRC backend response or endpoint was invalid."
			logger.warn("Backend polling failed validation", exception)
		}
	}

	private fun bootstrapLastSeenMessageId(generation: Long) {
		if (serviceGeneration.get() != generation) return
		lastSeenMessageId = 0L
		backlogInitialized = false
	}

	private fun postOutgoing(outgoing: BackendOutgoingMessage, generation: Long, configSnapshot: BridgeConfig) {
		if (serviceGeneration.get() != generation) return
		try {
			val request = ircRequestBuilder(ircServerUrl("/api/messages", configSnapshot), configSnapshot)
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(outgoing)))
				.build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			if (serviceGeneration.get() != generation) return
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
			if (serviceGeneration.get() != generation) return
			state = "error"
			lastError = "${exception::class.java.simpleName}: ${safe(exception.message)}"
			logger.warn("Failed to send IRC message to backend", exception)
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
		} catch (exception: RuntimeException) {
			if (serviceGeneration.get() != generation) return
			state = "error"
			lastError = "IRC backend endpoint or request was invalid."
			logger.warn("Failed to validate IRC backend request", exception)
		}
	}

	fun status(): BackendStatusSnapshot =
		BackendStatusSnapshot(state, lastHttpStatus, lastSuccessAt, lastPollAt, lastMessageAt, lastError)

	fun testConnection(): BackendStatusSnapshot {
		return testConnection(config)
	}

	fun testConnection(configOverride: BridgeConfig): BackendStatusSnapshot {
		if (normalizeIrcServerBaseUrl(configOverride.ircServerBaseUrl) == null || !isValidIrcAuthToken(configOverride.backendAuthToken)) {
			state = "disabled"
			lastError = "IRC server URL must use HTTPS, except for loopback development, and the auth token must be valid."
			return status()
		}

		return try {
			lastPollAt = System.currentTimeMillis()
			val healthRequest = modBackendRequestBuilder(ircServerUrl("/health", configOverride)).GET().build()
			val healthResponse = httpClient.send(healthRequest, HttpResponse.BodyHandlers.ofString())
			lastHttpStatus = healthResponse.statusCode()

			if (healthResponse.statusCode() != 200) {
				state = "error"
				lastError = "Health returned HTTP ${healthResponse.statusCode()}"
				status()
			} else {
				val payload = GSON.fromJson(healthResponse.body(), HealthResponse::class.java)
				if (payload?.status?.equals("ok", ignoreCase = true) != true) {
					state = "error"
					lastError = "Health payload invalid."
				} else {
					val playerName = currentPlayerName(Minecraft.getInstance()).ifBlank { "test" }
					val query = ircServerUrl(
						"/api/messages?after=0&playerName=" + URLEncoder.encode(playerName, StandardCharsets.UTF_8),
						configOverride,
					)
					val messagesRequest = ircRequestBuilder(query, configOverride).GET().build()
					val messagesResponse = httpClient.send(messagesRequest, HttpResponse.BodyHandlers.ofString())
					lastHttpStatus = messagesResponse.statusCode()
					if (messagesResponse.statusCode() != 200) {
						state = "error"
						lastError = "Messages returned HTTP ${messagesResponse.statusCode()}"
					} else {
						state = "connected"
						lastSuccessAt = System.currentTimeMillis()
						lastError = ""
					}
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
		} catch (exception: RuntimeException) {
			state = "error"
			lastError = "IRC backend endpoint or response was invalid."
			status()
		}
	}

	private fun modBackendRequestBuilder(url: String): HttpRequest.Builder =
		HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(10))
			.header("Content-Type", "application/json")

	private fun authenticatedModBackendRequestBuilder(url: String, sessionGeneration: Long? = null): HttpRequest.Builder? = synchronized(this) {
		if (sessionGeneration != null && !ClientSessionLifecycle.isCurrent(sessionGeneration)) return@synchronized null
		modCredential?.takeIf { it.isNotBlank() }?.let { credential ->
			modBackendRequestBuilder(url).header("Authorization", "Bearer $credential")
		}
	}

	private fun ircRequestBuilder(url: String, configOverride: BridgeConfig = config): HttpRequest.Builder {
		require(normalizeIrcServerBaseUrl(configOverride.ircServerBaseUrl) != null) { "Unsafe IRC backend endpoint" }
		require(isValidIrcAuthToken(configOverride.backendAuthToken)) { "Invalid IRC backend token" }
		return modBackendRequestBuilder(url)
			.header("Authorization", "Bearer ${configOverride.backendAuthToken}")
	}

	private fun modBackendUrl(path: String, configOverride: BridgeConfig = config): String = activeModBackendBaseUrl(configOverride) + path

	private fun ircServerUrl(path: String, configOverride: BridgeConfig = config): String = configOverride.ircServerBaseUrl + path

	private fun readBounded(input: InputStream, maximumBytes: Int): ByteArray {
		val bytes = input.readNBytes(maximumBytes + 1)
		if (bytes.size > maximumBytes) throw IOException("Response exceeds size limit")
		return bytes
	}

	private fun isValidAuctionPriceResponse(payload: BackendAuctionHousePriceResponse, requested: List<String>): Boolean {
		if (payload.items.size != requested.size || payload.items.map { it.itemId } != requested) return false
		if (!isValidAuctionSource(payload.sources.lowestBin) || !isValidAuctionSource(payload.sources.bazaar)) return false
		val lowestAvailable = payload.sources.lowestBin.available
		val bazaarAvailable = payload.sources.bazaar.available
		if (!lowestAvailable && !bazaarAvailable || payload.partial != !(lowestAvailable && bazaarAvailable)) return false
		return payload.items.all { item ->
			AUCTION_ITEM_ID_PATTERN.matches(item.itemId) &&
				isValidAuctionPrice(item.lowestBin) && isValidAuctionPrice(item.bazaarSellReference) &&
				(item.lowestBin == null || lowestAvailable) && (item.bazaarSellReference == null || bazaarAvailable)
		}
	}

	private fun isValidAuctionSource(source: BackendAuctionHouseSourceStatus): Boolean {
		val fetchedAt = source.fetchedAt
		if (source.stale && !source.available) return false
		return if (source.available) {
			fetchedAt != null && fetchedAt > 0L && fetchedAt <= System.currentTimeMillis() + MAX_CLOCK_SKEW_MS
		} else {
			fetchedAt == null
		}
	}

	private fun isValidAuctionPrice(price: Double?): Boolean =
		price == null || (price.isFinite() && price > 0.0 && price <= MAX_AUCTION_UNIT_PRICE)

	private fun announceConnected(client: Minecraft?, generation: Long) {
		if (announcedConnected || client?.player == null || client.gui == null) {
			return
		}

		announcedConnected = true
		showClientMessage(client, Component.literal("[IRC] Connected to backend.").withStyle(ChatFormatting.GREEN), generation)
	}

	private fun echoLocally(message: String) {
		val client = Minecraft.getInstance()
		if (client?.gui == null) {
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

	private fun showClientMessage(client: Minecraft?, message: Component, generation: Long? = null) {
		client?.execute {
			if (generation != null && serviceGeneration.get() != generation) return@execute
			when {
				client.player != null -> client.player?.sendSystemMessage(message)
				client.gui != null -> client.gui.chat.addClientSystemMessage(message)
			}
		}
	}

	private fun showBridgeMessage(client: Minecraft, message: Component, isIrcMessage: Boolean, generation: Long) {
		client.execute {
			if (serviceGeneration.get() != generation) return@execute
			if (isIrcMessage) IrcChatTabManager.addIrcMessage(message)
			when {
				client.player != null -> client.player?.sendSystemMessage(message)
				client.gui != null -> client.gui.chat.addClientSystemMessage(message)
			}
		}
	}

	private fun flushPausedIncomingMessages() {
		val client = Minecraft.getInstance() ?: return
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

	private fun styleBridgeMessage(formatted: String): Component {
		if (formatted.startsWith("[") && formatted.contains("]")) {
			val prefixEnd = formatted.indexOf(']') + 1
			val prefix = formatted.substring(0, prefixEnd)
			val rest = formatted.substring(prefixEnd)

			val text: MutableComponent = Component.literal(prefix).withStyle(ChatFormatting.GREEN)
			if (rest.isNotEmpty()) {
				appendLinkedText(text, rest, Style.EMPTY.withColor(ChatFormatting.WHITE))
			}
			return text
		}

		return buildLinkedText(formatted, Style.EMPTY)
	}

	private fun buildLinkedText(content: String, defaultStyle: Style): MutableComponent {
		val root = Component.empty()
		appendLinkedText(root, content, defaultStyle)
		return root
	}

	private fun appendLinkedText(target: MutableComponent, content: String, defaultStyle: Style) {
		var cursor = 0

		for (match in URL_PATTERN.findAll(content)) {
			if (match.range.first > cursor) {
				target.append(Component.literal(content.substring(cursor, match.range.first)).setStyle(defaultStyle))
			}

			val rawUrl = match.value
			val normalizedUrl = trimTrailingUrlPunctuation(rawUrl)
			val trailing = rawUrl.substring(normalizedUrl.length)

			if (normalizedUrl.isNotBlank()) {
				target.append(
					Component.literal(normalizedUrl).setStyle(
						defaultStyle
							.withUnderlined(true)
							.withColor(ChatFormatting.AQUA)
							.withClickEvent(ClickEvent.OpenUrl(URI.create(normalizedUrl))),
					),
				)
			}

			if (trailing.isNotEmpty()) {
				target.append(Component.literal(trailing).setStyle(defaultStyle))
			}

			cursor = match.range.last + 1
		}

		if (cursor < content.length) {
			target.append(Component.literal(content.substring(cursor)).setStyle(defaultStyle))
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
		val content: Component,
		val isIrcMessage: Boolean,
	)

	companion object {
		private val GSON = Gson()
		private val URL_PATTERN = Regex("""https?://\S+""")
		private val MOD_LINK_CODE_PATTERN = Regex("[A-Za-z0-9_-]{22}")
		private const val TRAILING_URL_PUNCTUATION = ".,!?;:)]}"
		private const val MAX_OUTGOING_MESSAGE_LENGTH = 280
		private const val MAX_INCOMING_MESSAGE_LENGTH = 2048
		private const val MAX_NAME_LENGTH = 32
		private const val MAX_BATCH_DUNGEON_PLAYERS = 45
		private const val MAX_AUCTION_PRICE_ITEMS = 32
		private const val MAX_AUCTION_PRICE_RESPONSE_BYTES = 256 * 1024
		private const val MAX_AUCTION_UNIT_PRICE = 1_000_000_000_000_000.0
		private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
		private val AUCTION_ITEM_ID_PATTERN = Regex("^[A-Z0-9_:-]+(?:;[0-9]{1,2})?(?:\\+[A-Z0-9_:-]+)?$")
		private const val LOCAL_ECHO_TTL_MS = 10_000L
		private const val MAX_LOCAL_ECHOES = 32
		private const val MAX_PAUSED_INCOMING_MESSAGES = 100
		private const val POLL_WARNING_INTERVAL_MS = 30_000L

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

		private fun currentPlayerName(client: Minecraft?): String {
			if (client == null) {
				return ""
			}

			return sanitizeInline(client.user.name, MAX_NAME_LENGTH)
		}
	}
}
