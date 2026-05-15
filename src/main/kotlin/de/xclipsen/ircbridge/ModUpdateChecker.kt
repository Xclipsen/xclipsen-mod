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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
	private var checkInProgress = false

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
		requestCheckNow(force = false)
	}

	fun onConfigChanged() {
		if (!isEnabled()) {
			checkStarted = false
			checkInProgress = false
			announcementShown = false
			state = UpdateState.DISABLED
			return
		}
		requestCheckNow(force = true)
	}

	fun requestCheckNow(force: Boolean = true): Boolean {
		if (!isEnabled()) {
			state = UpdateState.DISABLED
			return false
		}

		synchronized(this) {
			if (checkInProgress) {
				return false
			}
			if (!force && checkStarted) {
				return false
			}

			checkStarted = true
			checkInProgress = true
			announcementShown = false
			lastError = ""
			state = UpdateState.CHECKING
		}

		CompletableFuture.runAsync {
			try {
				checkNow()
			} finally {
				checkInProgress = false
			}
		}
		return true
	}

	fun onTick(client: MinecraftClient) {
		if (announcementShown) {
			return
		}
		if (client.player == null && client.inGameHud == null) {
			return
		}

		when (state) {
			UpdateState.UPDATE_AVAILABLE -> {
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
			UpdateState.INSTALLED -> {
				announcementShown = true
				showClientMessage(
					client,
					Text.literal("[Update] ").formatted(Formatting.GREEN)
						.append(
							Text.literal("Xclipsen Mod ${latestVersion ?: "?"} wurde heruntergeladen. Bitte Minecraft neu starten.")
								.formatted(Formatting.WHITE),
						),
				)
			}
			UpdateState.DOWNLOAD_ERROR -> {
				announcementShown = true
				showClientMessage(
					client,
					Text.literal("[Update] ").formatted(Formatting.RED)
						.append(Text.literal("Auto-Download fehlgeschlagen: $lastError").formatted(Formatting.WHITE)),
				)
				showClientMessage(
					client,
					clickableLink("Hier manuell herunterladen.", latestReleaseUrl ?: RELEASES_URL),
				)
			}
			else -> return
		}
	}

	fun statusLine(): String {
		return when (state) {
			UpdateState.DISABLED -> "Disabled"
			UpdateState.IDLE -> "Waiting for startup check"
			UpdateState.CHECKING -> "Checking GitHub releases..."
			UpdateState.UP_TO_DATE -> "Up to date (${currentVersion()})"
			UpdateState.UPDATE_AVAILABLE -> "Update available: ${latestVersion ?: "unknown"}"
			UpdateState.DOWNLOADING -> "Downloading ${latestVersion ?: "update"}..."
			UpdateState.INSTALLED -> "Installed ${latestVersion ?: "update"} — restart required"
			UpdateState.ERROR -> if (lastError.isBlank()) "Update check failed" else "Update check failed: $lastError"
			UpdateState.DOWNLOAD_ERROR -> if (lastError.isBlank()) "Download failed" else "Download failed: $lastError"
		}
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

			if (compareVersions(current, latest) < 0) {
				if (isAutoUpdateEnabled()) {
					val jarAsset = release.assets.firstOrNull { asset ->
						asset.name.endsWith(".jar") &&
							!asset.name.endsWith("-sources.jar") &&
							!asset.name.endsWith("-dev.jar")
					}
					if (jarAsset != null) {
						downloadAndInstall(jarAsset)
					} else {
						// Kein JAR-Asset vorhanden – nur Benachrichtigung anzeigen
						state = UpdateState.UPDATE_AVAILABLE
					}
				} else {
					state = UpdateState.UPDATE_AVAILABLE
				}
			} else {
				state = UpdateState.UP_TO_DATE
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

	private fun downloadAndInstall(asset: GithubAsset) {
		state = UpdateState.DOWNLOADING

		val modsDir = FabricLoader.getInstance().gameDir.resolve("mods")
		val newJarPath = modsDir.resolve(asset.name)
		val tempPath = modsDir.resolve("${asset.name}.tmp")

		try {
			val downloadRequest = HttpRequest.newBuilder(URI.create(asset.browserDownloadUrl))
				.timeout(Duration.ofSeconds(120))
				.header("User-Agent", "xclipsen-mod-auto-updater")
				.GET()
				.build()

			httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofFile(tempPath))

			// Alte xclipsen-mod JARs löschen (außer der neuen)
			Files.list(modsDir).use { stream ->
				stream
					.filter { path ->
						val name = path.fileName.toString()
						name.matches(Regex("xclipsen-mod-[0-9]+\\.[0-9]+\\.[0-9]+\\.jar"))
					}
					.filter { it != newJarPath }
					.forEach { Files.deleteIfExists(it) }
			}

			// Temp-Datei zur finalen JAR umbenennen
			Files.move(tempPath, newJarPath, StandardCopyOption.REPLACE_EXISTING)

			state = UpdateState.INSTALLED
		} catch (exception: IOException) {
			Files.deleteIfExists(tempPath)
			lastError = exception::class.java.simpleName + (exception.message?.let { ": $it" } ?: "")
			state = UpdateState.DOWNLOAD_ERROR
		} catch (exception: InterruptedException) {
			Files.deleteIfExists(tempPath)
			Thread.currentThread().interrupt()
			lastError = exception::class.java.simpleName
			state = UpdateState.DOWNLOAD_ERROR
		} catch (exception: RuntimeException) {
			Files.deleteIfExists(tempPath)
			lastError = exception::class.java.simpleName + (exception.message?.let { ": $it" } ?: "")
			state = UpdateState.DOWNLOAD_ERROR
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

	private fun isAutoUpdateEnabled(): Boolean {
		return XclipsenIrcBridgeClient.instance?.config()?.autoUpdateEnabled == true
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
		DOWNLOADING,
		INSTALLED,
		ERROR,
		DOWNLOAD_ERROR,
	}

	private class GithubReleaseResponse {
		@SerializedName("tag_name")
		var tagName: String = ""

		@SerializedName("html_url")
		var htmlUrl: String = ""

		var name: String = ""

		var assets: List<GithubAsset> = emptyList()
	}

	private class GithubAsset {
		var name: String = ""

		@SerializedName("browser_download_url")
		var browserDownloadUrl: String = ""
	}
}
