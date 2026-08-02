package de.xclipsen.ircbridge.minigame

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.xclipsen.ircbridge.BridgeConfig
import de.xclipsen.ircbridge.activeModBackendBaseUrl
import org.slf4j.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MinigameNetworkClient(
	private val logger: Logger,
	private val eventHandler: (Long, String, JsonObject) -> Unit,
) {
	private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
	private val executor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "xclipsen-minigame-network").apply { isDaemon = true }
	}
	private var scheduler: ScheduledExecutorService? = null
	@Volatile
	private var config = BridgeConfig()
	@Volatile
	private var modCredential = ""
	@Volatile
	private var sessionToken = ""
	@Volatile
	private var disconnectToken = ""
	@Volatile
	private var serverId = ""
	private val lastEventId = AtomicLong()
	private val connectionGeneration = AtomicLong()

	@Volatile
	var connected: Boolean = false
		private set

	@Synchronized
	fun configure(config: BridgeConfig, modCredential: String?) {
		if (activeModBackendBaseUrl(this.config) != activeModBackendBaseUrl(config) || this.modCredential != modCredential.orEmpty()) {
			lastEventId.set(0L)
		}
		this.config = config.copy()
		this.modCredential = modCredential.orEmpty()
	}

	@Synchronized
	fun register(minecraftServerId: String, modVersion: String) {
		val generation = connectionGeneration.incrementAndGet()
		scheduler?.shutdownNow()
		scheduler = null
		sessionToken = ""
		connected = false
		serverId = minecraftServerId
		val requestConfig = config.copy()
		val credential = modCredential
		executor.execute {
			val response = post(
				"/api/minigames/register",
				mapOf("minecraftServerId" to minecraftServerId, "modVersion" to modVersion),
				requestConfig,
				credential,
			)
			if (!isCurrentGeneration(generation)) {
				response.body.string("sessionToken")?.takeIf(String::isNotBlank)?.let { staleToken ->
					post("/api/minigames/disconnect", emptyMap(), requestConfig, staleToken)
				}
				return@execute
			}
			if (!response.ok) {
				handleEvent(generation, "network_error", errorData(response.error))
				return@execute
			}
			val token = response.body.string("sessionToken")?.takeIf(String::isNotBlank)
			if (token == null) {
				handleEvent(generation, "network_error", errorData("Mini-game backend returned an invalid registration response."))
				return@execute
			}
			synchronized(this) {
				if (!isCurrentGeneration(generation)) return@synchronized
				sessionToken = token
				disconnectToken = token
				connected = true
			}
			if (!isCurrentGeneration(generation)) {
				post("/api/minigames/disconnect", emptyMap(), requestConfig, token)
				return@execute
			}
			response.body?.get("activeMatch")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
				handleEvent(generation, "minigame_match_state_update", it)
			}
			response.body?.get("invites")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
				if (element.isJsonObject) {
					handleEvent(generation, "minigame_invite_received", element.asJsonObject)
				} else {
					logger.warn("Ignoring malformed minigame invite in registration response.")
				}
			}
			startPolling(generation, requestConfig)
		}
	}

	@Synchronized
	fun disconnect() {
		val token = sessionToken
		val requestConfig = config.copy()
		connectionGeneration.incrementAndGet()
		scheduler?.shutdownNow()
		scheduler = null
		sessionToken = ""
		connected = false
		if (token.isNotBlank()) {
			executor.execute { post("/api/minigames/disconnect", emptyMap(), requestConfig, token) }
		}
	}

	fun shutdown() {
		val token: String
		val requestConfig: BridgeConfig
		synchronized(this) {
			token = sessionToken.ifBlank { disconnectToken }
			requestConfig = config.copy()
			connectionGeneration.incrementAndGet()
			scheduler?.shutdownNow()
			scheduler = null
			sessionToken = ""
			connected = false
		}
		if (token.isNotBlank()) {
			val disconnectExecutor = newDisconnectExecutor()
			disconnectExecutor.execute {
				try {
					post("/api/minigames/disconnect", emptyMap(), requestConfig, token)
				} finally {
					executor.shutdownNow()
				}
			}
			disconnectExecutor.shutdown()
		} else {
			executor.shutdownNow()
		}
	}

	fun invite(targetUsername: String, minigameId: String, callback: (Long, NetworkResult) -> Unit) {
		executePost("/api/minigames/invites", mapOf("targetUsername" to targetUsername, "minigameId" to minigameId), callback)
	}

	fun acceptInvite(inviteId: String, callback: (Long, NetworkResult) -> Unit) {
		executePost("/api/minigames/invites/accept", mapOf("inviteId" to inviteId), callback)
	}

	fun denyInvite(inviteId: String, callback: (Long, NetworkResult) -> Unit) {
		executePost("/api/minigames/invites/deny", mapOf("inviteId" to inviteId), callback)
	}

	fun submitTicTacToeMove(
		matchId: String,
		fieldIndex: Int,
		requestId: String,
		expectedRevision: Long,
		callback: (Long, NetworkResult) -> Unit,
	) {
		executePost(
			"/api/minigames/tictactoe/move",
			mapOf(
				"matchId" to matchId,
				"fieldIndex" to fieldIndex,
				"requestId" to requestId,
				"expectedRevision" to expectedRevision,
			),
			callback,
		)
	}

	fun leaveMatch(matchId: String, callback: (Long, NetworkResult) -> Unit) {
		executePost("/api/minigames/matches/leave", mapOf("matchId" to matchId), callback)
	}

	fun requestRematch(matchId: String, callback: (Long, NetworkResult) -> Unit) {
		executePost("/api/minigames/matches/rematch", mapOf("matchId" to matchId), callback)
	}

	@Synchronized
	fun backendBaseUrl(): String = activeModBackendBaseUrl(config)

	@Synchronized
	private fun startPolling(generation: Long, requestConfig: BridgeConfig) {
		if (!isCurrentGeneration(generation)) return
		scheduler?.shutdownNow()
		scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "xclipsen-minigame-poller").apply { isDaemon = true }
		}
		scheduler?.scheduleAtFixedRate({ runScheduled("heartbeat") { heartbeat(generation, requestConfig) } }, 0L, 5L, TimeUnit.SECONDS)
		scheduler?.scheduleAtFixedRate({ runScheduled("event poll") { pollEvents(generation, requestConfig) } }, 0L, 1L, TimeUnit.SECONDS)
	}

	private fun heartbeat(generation: Long, requestConfig: BridgeConfig) {
		if (!isCurrentGeneration(generation)) return
		val token = sessionToken.takeIf(String::isNotBlank) ?: return
		val response = post(
			"/api/minigames/heartbeat",
			mapOf("minecraftServerId" to serverId),
			requestConfig,
			token,
		)
		if (!isCurrentGeneration(generation)) return
		if (!response.ok) {
			connected = false
			handleEvent(generation, "network_error", errorData(response.error))
		} else {
			connected = true
		}
	}

	private fun pollEvents(generation: Long, requestConfig: BridgeConfig) {
		if (!isCurrentGeneration(generation)) return
		val token = sessionToken.takeIf(String::isNotBlank) ?: return
		val query = "/api/minigames/events?after=${lastEventId.get()}"
		val response = get(query, requestConfig, token)
		if (!isCurrentGeneration(generation)) return
		if (!response.ok) return
		val events = response.body?.get("events")?.takeIf { it.isJsonArray }?.asJsonArray
		if (events == null) {
			logger.warn("Ignoring malformed minigame event response.")
			return
		}
		for (element in events) {
			if (!isCurrentGeneration(generation)) return
			if (!element.isJsonObject) {
				logger.warn("Ignoring malformed minigame event envelope.")
				continue
			}
			val event = element.asJsonObject
			val eventId = event.long("id")
			val cursor = lastEventId.get()
			if (eventId == null || eventId <= cursor) {
				logger.warn("Ignoring minigame event with an invalid or non-monotonic ID.")
				continue
			}
			lastEventId.set(eventId)
			val type = event.string("type")?.takeIf(String::isNotBlank)
			val data = event.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
			if (type == null || data == null) {
				logger.warn("Ignoring malformed minigame event {}.", eventId)
				continue
			}
			handleEvent(generation, type, data)
		}
	}

	private fun executePost(path: String, payload: Map<String, Any>, callback: (Long, NetworkResult) -> Unit) {
		val generation = connectionGeneration.get()
		val requestConfig = config.copy()
		executor.execute {
			if (!isCurrentGeneration(generation)) return@execute
			val token = sessionToken
			val response = post(path, payload, requestConfig, token)
			if (!isCurrentGeneration(generation)) return@execute
			try {
				callback(generation, NetworkResult(response.ok, response.error, response.body))
			} catch (exception: Exception) {
				logger.error("Minigame network callback failed.", exception)
			}
		}
	}

	fun isCurrentGeneration(generation: Long): Boolean = generation == connectionGeneration.get()

	private fun handleEvent(generation: Long, type: String, data: JsonObject) {
		if (!isCurrentGeneration(generation)) return
		try {
			eventHandler(generation, type, data)
		} catch (exception: Exception) {
			logger.error("Minigame event handler failed for {}.", type, exception)
		}
	}

	private fun runScheduled(name: String, action: () -> Unit) {
		try {
			action()
		} catch (exception: Exception) {
			logger.error("Minigame {} failed; polling will continue.", name, exception)
		}
	}

	private fun post(path: String, payload: Map<String, Any>, configOverride: BridgeConfig = config, bearerToken: String = ""): HttpResult {
		return request(
			HttpRequest.newBuilder(URI.create(activeModBackendBaseUrl(configOverride) + path))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.apply { if (bearerToken.isNotBlank()) header("Authorization", "Bearer $bearerToken") }
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
				.build(),
		)
	}

	private fun get(path: String, configOverride: BridgeConfig = config, bearerToken: String = ""): HttpResult {
		return request(
			HttpRequest.newBuilder(URI.create(activeModBackendBaseUrl(configOverride) + path))
				.timeout(Duration.ofSeconds(10))
				.apply { if (bearerToken.isNotBlank()) header("Authorization", "Bearer $bearerToken") }
				.GET()
				.build(),
		)
	}

	private fun request(request: HttpRequest): HttpResult {
		return try {
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			val root = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
			if (response.statusCode() in 200..299) {
				HttpResult(true, root)
			} else {
				HttpResult(false, root, root.string("error") ?: "Backend HTTP ${response.statusCode()}")
			}
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			HttpResult(false, error = "Backend request was interrupted.")
		} catch (exception: Exception) {
			logger.debug("Minigame backend request failed", exception)
			HttpResult(false, error = "Mini-game backend is unreachable.")
		}
	}

	private fun errorData(message: String): JsonObject = JsonObject().apply { addProperty("error", message) }
	private data class HttpResult(val ok: Boolean, val body: JsonObject? = null, val error: String = "")
	private fun JsonObject?.string(name: String): String? = runCatching {
		this?.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
	}.getOrNull()
	private fun JsonObject.long(name: String): Long? = runCatching {
		get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
			?.asJsonPrimitive?.asString?.toLongOrNull()
	}.getOrNull()

	companion object {
		private val GSON = Gson()

		private fun newDisconnectExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
			Thread(runnable, "xclipsen-minigame-disconnect").apply { isDaemon = true }
		}
	}
}
