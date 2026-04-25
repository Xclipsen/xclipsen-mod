package de.xclipsen.ircbridge

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.text.ClickEvent
import net.minecraft.text.MutableText
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture

object ModUpdateChecker {
	private const val MOD_ID = "xclipsen_mod"
	private const val RELEASES_URL = "https://github.com/Xclipsen/xclipsen-mod/releases/latest"
	private const val RELEASES_API_URL = "https://api.github.com/repos/Xclipsen/xclipsen-mod/releases/latest"

	private val gson = Gson()
	private val httpClient: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build()

	@Volatile
	private var checkStarted = false

	@Volatile
	private var announcementShown = false

	@Volatile
	private var state: UpdateState = UpdateState.IDLE

	@Volatile
	private var latestVersion: String? = null

	@Volatile
	private var latestReleaseUrl: String? = null

	@Volatile
	private var lastError: String = ""

	fun onStartup() {
		maybeStartCheck()
	}

	fun onConfigChanged() {
		if (!isEnabled()) {
			checkStarted = false
			announcementShown = false
			state = UpdateState.DISABLED
			return
		}
		maybeStartCheck()
	}

	fun onTick(client: MinecraftClient) {
		if (state != UpdateState.UPDATE_AVAILABLE || announcementShown) {
			return
		}
		if (client.player == null && client.inGameHud == null) {
			return
		}

		announcementShown = true
		showClientMessage(
			client,
			Text.literal("[Update] ").formatted(Formatting.GREEN)
				.append(Text.literal("Xclipsen Mod ${latestVersion ?: "?"} is available.").formatted(Formatting.WHITE)),
		)
		showClientMessage(
			client,
			clickableLink("Click here to open the latest GitHub release.", latestReleaseUrl ?: RELEASES_URL),
		)
	}

	fun statusLine(): String {
		return when (state) {
			UpdateState.DISABLED -> "Disabled"
			UpdateState.IDLE -> "Waiting for startup check"
			UpdateState.CHECKING -> "Checking GitHub releases..."
			UpdateState.UP_TO_DATE -> "Up to date (${currentVersion()})"
			UpdateState.UPDATE_AVAILABLE -> "Update available: ${latestVersion ?: "unknown"}"
			UpdateState.ERROR -> if (lastError.isBlank()) "Update check failed" else "Update check failed: $lastError"
		}
	}

	private fun maybeStartCheck() {
		if (!isEnabled()) {
			state = UpdateState.DISABLED
			return
		}
		if (checkStarted) {
			return
		}

		checkStarted = true
		state = UpdateState.CHECKING
		CompletableFuture.runAsync(::checkNow)
	}

	private fun checkNow() {
		val request = HttpRequest.newBuilder(URI.create(RELEASES_API_URL))
			.timeout(Duration.ofSeconds(15))
			.header("Accept", "application/vnd.github+json")
			.header("X-GitHub-Api-Version", "2022-11-28")
			.header("User-Agent", "xclipsen-mod-update-checker")
			.GET()
			.build()

		try {
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			if (response.statusCode() !in 200..299) {
				lastError = "HTTP ${response.statusCode()}"
				state = UpdateState.ERROR
				return
			}

			val release = gson.fromJson(response.body(), GithubReleaseResponse::class.java) ?: run {
				lastError = "Empty response"
				state = UpdateState.ERROR
				return
			}

			val current = normalizeVersion(currentVersion())
			val latest = normalizeVersion(release.tagName.ifBlank { release.name })
			latestVersion = latest
			latestReleaseUrl = release.htmlUrl.ifBlank { RELEASES_URL }

			state = if (compareVersions(current, latest) < 0) {
				UpdateState.UPDATE_AVAILABLE
			} else {
				UpdateState.UP_TO_DATE
			}
		} catch (exception: IOException) {
			lastError = exception::class.java.simpleName
			state = UpdateState.ERROR
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			lastError = exception::class.java.simpleName
			state = UpdateState.ERROR
		} catch (exception: RuntimeException) {
			lastError = exception::class.java.simpleName
			state = UpdateState.ERROR
		}
	}

	private fun currentVersion(): String {
		val metadataVersion = FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map { it.metadata.version.friendlyString }
			.orElse("0.0.0")
		return normalizeVersion(metadataVersion)
	}

	private fun compareVersions(current: String, latest: String): Int {
		val left = versionSegments(current)
		val right = versionSegments(latest)
		val length = maxOf(left.size, right.size)
		for (index in 0 until length) {
			val a = left.getOrElse(index) { 0 }
			val b = right.getOrElse(index) { 0 }
			if (a != b) {
				return a.compareTo(b)
			}
		}
		return 0
	}

	private fun versionSegments(version: String): List<Int> {
		return version.split('.')
			.map { part -> part.filter(Char::isDigit) }
			.map { digits -> digits.toIntOrNull() ?: 0 }
	}

	private fun normalizeVersion(version: String): String {
		return version.trim()
			.removePrefix("v")
			.removePrefix("V")
			.lowercase(Locale.ROOT)
	}

	private fun isEnabled(): Boolean {
		return XclipsenIrcBridgeClient.instance?.config()?.checkForUpdatesEnabled == true
	}

	private fun clickableLink(label: String, url: String): MutableText {
		return Text.literal(label).setStyle(
			Style.EMPTY
				.withColor(Formatting.AQUA)
				.withUnderline(true)
				.withClickEvent(ClickEvent.OpenUrl(URI.create(url))),
		)
	}

	private fun showClientMessage(client: MinecraftClient?, message: Text) {
		client?.execute {
			when {
				client.player != null -> client.player?.sendMessage(message, false)
				client.inGameHud != null -> client.inGameHud.chatHud.addMessage(message)
			}
		}
	}

	private enum class UpdateState {
		IDLE,
		DISABLED,
		CHECKING,
		UP_TO_DATE,
		UPDATE_AVAILABLE,
		ERROR,
	}

	private class GithubReleaseResponse {
		@SerializedName("tag_name")
		var tagName: String = ""

		@SerializedName("html_url")
		var htmlUrl: String = ""

		var name: String = ""
	}
}
