package de.xclipsen.ircbridge.minigame

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.xclipsen.ircbridge.BridgeConfig
import de.xclipsen.ircbridge.activeModBackendBaseUrl
import org.slf4j.Logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MinigameNetworkClient(
	private val logger: Logger,
	private val eventHandler: (String, JsonObject) -> Unit,
) {
	private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
	private val executor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "xclipsen-minigame-network").apply { isDaemon = true }
	}
	private var scheduler: ScheduledExecutorService? = null
	@Volatile
	private var config = BridgeConfig()
	@Volatile
	private var sessionToken = ""
	@Volatile
	private var serverId = ""
	@Volatile
	private var lastEventId = 0L
	@Volatile
	private var connectionGeneration = 0L

	@Volatile
	var connected: Boolean = false
		private set

	@Synchronized
	fun configure(config: BridgeConfig) {
		if (activeModBackendBaseUrl(this.config) != activeModBackendBaseUrl(config)) {
			lastEventId = 0L
		}
		this.config = config.copy()
	}

	fun register(uuid: String, username: String, minecraftServerId: String, modVersion: String) {
		serverId = minecraftServerId
		val generation = connectionGeneration
		val requestConfig = config.copy()
		executor.execute {
			val response = post(
				"/api/minigames/register",
				mapOf("uuid" to uuid, "username" to username, "minecraftServerId" to minecraftServerId, "modVersion" to modVersion),
				requestConfig,
			)
			if (generation != connectionGeneration) {
				response.body?.get("sessionToken")?.asString?.takeIf(String::isNotBlank)?.let { staleToken ->
					post("/api/minigames/disconnect", mapOf("sessionToken" to staleToken), requestConfig)
				}
				return@execute
			}
			if (!response.ok) {
				eventHandler("network_error", errorData(response.error))
				return@execute
			}
			sessionToken = response.body?.get("sessionToken")?.asString.orEmpty()
			connected = sessionToken.isNotBlank()
			response.body?.get("activeMatch")?.takeIf { it.isJsonObject }?.asJsonObject?.let { eventHandler("minigame_match_state_update", it) }
			response.body?.getAsJsonArray("invites")?.forEach { eventHandler("minigame_invite_received", it.asJsonObject) }
			startPolling(generation, requestConfig)
		}
	}

	@Synchronized
	fun disconnect() {
		val token = sessionToken
		val requestConfig = config.copy()
		connectionGeneration++
		scheduler?.shutdownNow()
		scheduler = null
		sessionToken = ""
		connected = false
		if (token.isNotBlank()) {
			executor.execute { post("/api/minigames/disconnect", mapOf("sessionToken" to token), requestConfig) }
		}
	}

	fun shutdown() {
		disconnect()
		executor.shutdownNow()
	}

	fun invite(targetUsername: String, minigameId: String, callback: (NetworkResult) -> Unit) {
		executePost("/api/minigames/invites", mapOf("sessionToken" to sessionToken, "targetUsername" to targetUsername, "minigameId" to minigameId), callback)
	}

	fun acceptInvite(inviteId: String, senderUsername: String = "", callback: (NetworkResult) -> Unit) {
		executePost("/api/minigames/invites/accept", mapOf("sessionToken" to sessionToken, "inviteId" to inviteId, "senderUsername" to senderUsername), callback)
	}

	fun denyInvite(inviteId: String, senderUsername: String = "", callback: (NetworkResult) -> Unit) {
		executePost("/api/minigames/invites/deny", mapOf("sessionToken" to sessionToken, "inviteId" to inviteId, "senderUsername" to senderUsername), callback)
	}

	fun submitTicTacToeMove(
		matchId: String,
		fieldIndex: Int,
		requestId: String,
		expectedRevision: Long,
		callback: (NetworkResult) -> Unit,
	) {
		executePost(
			"/api/minigames/tictactoe/move",
			mapOf(
				"sessionToken" to sessionToken,
				"matchId" to matchId,
				"fieldIndex" to fieldIndex,
				"requestId" to requestId,
				"expectedRevision" to expectedRevision,
			),
			callback,
		)
	}

	fun leaveMatch(matchId: String, callback: (NetworkResult) -> Unit) {
		executePost("/api/minigames/matches/leave", mapOf("sessionToken" to sessionToken, "matchId" to matchId), callback)
	}

	fun requestRematch(matchId: String, callback: (NetworkResult) -> Unit) {
		executePost("/api/minigames/matches/rematch", mapOf("sessionToken" to sessionToken, "matchId" to matchId), callback)
	}

	@Synchronized
	fun backendBaseUrl(): String = activeModBackendBaseUrl(config)

	@Synchronized
	private fun startPolling(generation: Long, requestConfig: BridgeConfig) {
		if (generation != connectionGeneration) return
		scheduler?.shutdownNow()
		scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "xclipsen-minigame-poller").apply { isDaemon = true }
		}
		scheduler?.scheduleAtFixedRate({ heartbeat(generation, requestConfig) }, 0L, 5L, TimeUnit.SECONDS)
		scheduler?.scheduleAtFixedRate({ pollEvents(generation, requestConfig) }, 0L, 1L, TimeUnit.SECONDS)
	}

	private fun heartbeat(generation: Long, requestConfig: BridgeConfig) {
		if (generation != connectionGeneration) return
		if (sessionToken.isBlank()) return
		val response = post(
			"/api/minigames/heartbeat",
			mapOf("sessionToken" to sessionToken, "minecraftServerId" to serverId),
			requestConfig,
		)
		if (generation != connectionGeneration) return
		if (!response.ok) {
			connected = false
			eventHandler("network_error", errorData(response.error))
		} else {
			connected = true
		}
	}

	private fun pollEvents(generation: Long, requestConfig: BridgeConfig) {
		if (generation != connectionGeneration) return
		if (sessionToken.isBlank()) return
		val query = "/api/minigames/events?sessionToken=${encode(sessionToken)}&after=$lastEventId"
		val response = get(query, requestConfig)
		if (generation != connectionGeneration) return
		if (!response.ok) return
		response.body?.getAsJsonArray("events")?.forEach { element ->
			val event = element.asJsonObject
			lastEventId = maxOf(lastEventId, event.get("id")?.asLong ?: 0L)
			val type = event.get("type")?.asString.orEmpty()
			val data = event.getAsJsonObject("data") ?: JsonObject()
			if (type.isNotBlank()) eventHandler(type, data)
		}
	}

	private fun executePost(path: String, payload: Map<String, Any>, callback: (NetworkResult) -> Unit) {
		val generation = connectionGeneration
		val requestConfig = config.copy()
		executor.execute {
			if (generation != connectionGeneration) return@execute
			val response = post(path, payload, requestConfig)
			if (generation != connectionGeneration) return@execute
			callback(NetworkResult(response.ok, response.error, response.body))
		}
	}

	private fun post(path: String, payload: Map<String, Any>, configOverride: BridgeConfig = config): HttpResult {
		return request(
			HttpRequest.newBuilder(URI.create(activeModBackendBaseUrl(configOverride) + path))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
				.build(),
		)
	}

	private fun get(path: String, configOverride: BridgeConfig = config): HttpResult {
		return request(
			HttpRequest.newBuilder(URI.create(activeModBackendBaseUrl(configOverride) + path))
				.timeout(Duration.ofSeconds(10))
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
				HttpResult(false, root, root?.get("error")?.asString ?: "Backend HTTP ${response.statusCode()}")
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
	private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

	private data class HttpResult(val ok: Boolean, val body: JsonObject? = null, val error: String = "")

	companion object {
		private val GSON = Gson()
	}
}
