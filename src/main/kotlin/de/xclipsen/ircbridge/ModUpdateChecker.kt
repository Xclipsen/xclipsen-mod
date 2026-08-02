package de.xclipsen.ircbridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.JarFile

object ModUpdateChecker {
	private const val MOD_ID = "xclipsen_mod"
	private const val RELEASES_URL = "https://github.com/Xclipsen/xclipsen-mod/releases/latest"
	private const val RELEASES_BASE_URL = "https://github.com/Xclipsen/xclipsen-mod/releases"
	private const val RELEASES_API_URL = "https://api.github.com/repos/Xclipsen/xclipsen-mod/releases/latest"
	private const val ARTIFACT_PREFIX = "xclipsen-mod-"
	private const val MAX_METADATA_BYTES = 1_048_576L
	private const val MIN_JAR_BYTES = 65_536L
	private const val MAX_JAR_BYTES = 33_554_432L
	private const val MAX_JAR_ENTRIES = 4_096
	private const val MAX_INFLATED_JAR_BYTES = 134_217_728L
	private val VERSION_PATTERN = Regex("^(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})$")
	private val DIGEST_PATTERN = Regex("^sha256:([0-9a-fA-F]{64})$")

	private val gson = Gson()
	private val httpClient: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build()
	private val executor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "xclipsen-update-checker").apply { isDaemon = true }
	}
	private val requestGeneration = AtomicLong()

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
			requestGeneration.incrementAndGet()
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

		val generation = requestGeneration.incrementAndGet()
		executor.execute {
			try {
				checkNow(generation)
			} finally {
				checkInProgress = false
			}
		}
		return true
	}

	fun shutdown() {
		requestGeneration.incrementAndGet()
		executor.shutdownNow()
		checkInProgress = false
	}

	fun onTick(client: Minecraft) {
		if (announcementShown) {
			return
		}
		when (state) {
			UpdateState.UPDATE_AVAILABLE -> {
				announcementShown = true
				showClientMessage(
					client,
					Component.literal("[Update] ").withStyle(ChatFormatting.GREEN)
						.append(Component.literal("Xclipsen Mod ${latestVersion ?: "?"} is available.").withStyle(ChatFormatting.WHITE)),
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
					Component.literal("[Update] ").withStyle(ChatFormatting.GREEN)
						.append(
							Component.literal("Xclipsen Mod ${latestVersion ?: "?"} wurde heruntergeladen. Bitte Minecraft neu starten.")
								.withStyle(ChatFormatting.WHITE),
						),
				)
			}
			UpdateState.DOWNLOAD_ERROR -> {
				announcementShown = true
				showClientMessage(
					client,
					Component.literal("[Update] ").withStyle(ChatFormatting.RED)
						.append(Component.literal("Auto-Download fehlgeschlagen: $lastError").withStyle(ChatFormatting.WHITE)),
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

	private fun checkNow(generation: Long) {
		val request = HttpRequest.newBuilder(URI.create(RELEASES_API_URL))
			.timeout(Duration.ofSeconds(15))
			.header("Accept", "application/vnd.github+json")
			.header("X-GitHub-Api-Version", "2022-11-28")
			.header("User-Agent", "xclipsen-mod-update-checker")
			.GET()
			.build()

		try {
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
			if (!isCurrentRequest(generation)) {
				response.body().close()
				return
			}
			if (response.statusCode() != 200) {
				response.body().close()
				lastError = "HTTP ${response.statusCode()}"
				state = UpdateState.ERROR
				return
			}
			val body = response.body().use { readBounded(it, MAX_METADATA_BYTES) }

			val release = gson.fromJson(body.toString(Charsets.UTF_8), GithubReleaseResponse::class.java) ?: run {
				lastError = "Empty response"
				state = UpdateState.ERROR
				return
			}
			if (release.draft || release.prerelease) throw IOException("Unsupported release type")

			val current = parseVersion(currentVersion()) ?: throw IOException("Invalid installed version")
			val latest = parseVersion(release.tagName) ?: throw IOException("Invalid release tag")
			latestVersion = latest
			latestReleaseUrl = "$RELEASES_BASE_URL/tag/v$latest"

			if (compareVersions(current, latest) < 0) {
				if (isAutoUpdateEnabled()) {
					val expectedName = "$ARTIFACT_PREFIX$latest.jar"
					val candidates = release.assets.filter { it.name == expectedName }
					val jarAsset = candidates.singleOrNull()
						?: throw IOException("Canonical release JAR missing or duplicated")
					validateAssetMetadata(jarAsset, latest)
					if (!isCurrentRequest(generation)) return
					downloadAndInstall(jarAsset, latest, generation)
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

	private fun downloadAndInstall(asset: GithubAsset, version: String, generation: Long) {
		state = UpdateState.DOWNLOADING

		val modsDir = FabricLoader.getInstance().gameDir.resolve("mods").toAbsolutePath().normalize()
		val expectedName = "$ARTIFACT_PREFIX$version.jar"
		val newJarPath = modsDir.resolve(expectedName).normalize()
		var tempPath: Path? = null
		val pendingPath = modsDir.resolve("${asset.name}.pending")
		val scriptPath = modsDir.resolve("xclipsen-mod-update.cmd")

		try {
			Files.createDirectories(modsDir)
			if (newJarPath.parent != modsDir || newJarPath.fileName.toString() != expectedName) throw IOException("Unsafe update path")
			deleteIfExistsQuietly(pendingPath)
			deleteIfExistsQuietly(scriptPath)
			tempPath = Files.createTempFile(modsDir, ".xclipsen-update-", ".tmp")

			val assetUri = validateAssetUri(asset.browserDownloadUrl, expectedName)
			val downloadRequest = HttpRequest.newBuilder(assetUri)
				.timeout(Duration.ofSeconds(120))
				.header("User-Agent", "xclipsen-mod-auto-updater")
				.GET()
				.build()

			val response = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream())
			if (!isCurrentRequest(generation) || !isAutoUpdateEnabled()) {
				response.body().close()
				state = if (isEnabled()) UpdateState.UPDATE_AVAILABLE else UpdateState.DISABLED
				return
			}
			if (response.statusCode() != 200) {
				response.body().close()
				throw IOException("Download HTTP ${response.statusCode()}")
			}
			validateDownloadHost(response.uri())
			val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
			if (contentLength > MAX_JAR_BYTES || (contentLength >= 0L && contentLength != asset.size)) {
				response.body().close()
				throw IOException("Unexpected download size")
			}
			val actualDigest = response.body().use { input -> writeBoundedAndDigest(input, tempPath, MAX_JAR_BYTES) }
			if (Files.size(tempPath) != asset.size) throw IOException("Downloaded size does not match release metadata")
			val expectedDigest = hexToBytes(requireNotNull(DIGEST_PATTERN.matchEntire(asset.digest)).groupValues[1])
			if (!MessageDigest.isEqual(actualDigest, expectedDigest)) throw IOException("Downloaded checksum mismatch")
			validateJar(tempPath, version)
			if (!isCurrentRequest(generation) || !isAutoUpdateEnabled()) {
				deleteIfExistsQuietly(tempPath)
				tempPath = null
				state = if (isEnabled()) UpdateState.UPDATE_AVAILABLE else UpdateState.DISABLED
				return
			}
			if (Files.exists(newJarPath)) throw IOException("Target update JAR already exists")

			if (isWindows()) {
				Files.move(tempPath, pendingPath, StandardCopyOption.REPLACE_EXISTING)
				tempPath = null
				scheduleWindowsInstall(modsDir, pendingPath, newJarPath, scriptPath)
			} else {
				installStagedJar(tempPath, newJarPath)
				tempPath = null
				cleanupPreviousJar(modsDir, newJarPath)
			}

			state = UpdateState.INSTALLED
		} catch (exception: IOException) {
			tempPath?.let(::deleteIfExistsQuietly)
			deleteIfExistsQuietly(pendingPath)
			lastError = exception::class.java.simpleName + (exception.message?.let { ": $it" } ?: "")
			state = UpdateState.DOWNLOAD_ERROR
		} catch (exception: InterruptedException) {
			tempPath?.let(::deleteIfExistsQuietly)
			deleteIfExistsQuietly(pendingPath)
			Thread.currentThread().interrupt()
			lastError = exception::class.java.simpleName
			state = UpdateState.DOWNLOAD_ERROR
		} catch (exception: RuntimeException) {
			tempPath?.let(::deleteIfExistsQuietly)
			deleteIfExistsQuietly(pendingPath)
			lastError = exception::class.java.simpleName + (exception.message?.let { ": $it" } ?: "")
			state = UpdateState.DOWNLOAD_ERROR
		}
	}

	private fun cleanupPreviousJar(modsDir: Path, installedJar: Path) {
		val previous = activeJarPath() ?: return
		if (previous != installedJar && previous.parent == modsDir && Files.isRegularFile(previous) && isCanonicalJarName(previous.fileName.toString())) {
			Files.deleteIfExists(previous)
		}
	}

	private fun scheduleWindowsInstall(modsDir: Path, pendingPath: Path, newJarPath: Path, scriptPath: Path) {
		val script = buildWindowsUpdateScript(modsDir, pendingPath, newJarPath, activeJarPath())
		Files.writeString(scriptPath, script)
		ProcessBuilder("cmd.exe", "/c", scriptPath.toString())
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.redirectError(ProcessBuilder.Redirect.DISCARD)
			.start()
	}

	private fun buildWindowsUpdateScript(modsDir: Path, pendingPath: Path, newJarPath: Path, previousJar: Path?): String {
		val modsDirText = windowsBatchPath(modsDir)
		val pendingText = windowsBatchPath(pendingPath)
		val targetText = windowsBatchPath(newJarPath)
		val previousText = previousJar?.takeIf { it.parent == modsDir && isCanonicalJarName(it.fileName.toString()) }?.let(::windowsBatchPath).orEmpty()
		return """
			@echo off
			setlocal EnableExtensions
			set "MODS_DIR=$modsDirText"
			set "PENDING=$pendingText"
			set "TARGET=$targetText"
			set "PREVIOUS=$previousText"
			set /a TRIES=0
			
			:install
			if exist "%TARGET%" goto end
			move /y "%PENDING%" "%TARGET%" >nul 2>&1
			if exist "%PENDING%" (
				copy /y "%PENDING%" "%TARGET%" >nul 2>&1
				if exist "%TARGET%" del /f /q "%PENDING%" >nul 2>&1
			)
			if exist "%PENDING%" goto end
			if not exist "%TARGET%" goto end
			if "%PREVIOUS%"=="" goto end
			:wait_loop
			del /f /q "%PREVIOUS%" >nul 2>&1
			if not exist "%PREVIOUS%" goto end
			set /a TRIES+=1
			if %TRIES% GEQ 180 goto end
			timeout /t 1 /nobreak >nul
			goto wait_loop
			
			:end
			endlocal
		""".trimIndent()
	}

	private fun windowsBatchPath(path: Path): String {
		return path.toAbsolutePath().normalize().toString()
			.replace("^", "^^")
			.replace("%", "%%")
			.replace("&", "^&")
			.replace("|", "^|")
			.replace("<", "^<")
			.replace(">", "^>")
	}

	private fun isWindows(): Boolean {
		return System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
	}

	private fun deleteIfExistsQuietly(path: Path) {
		runCatching { Files.deleteIfExists(path) }
	}

	private fun readBounded(input: InputStream, maximumBytes: Long): ByteArray {
		val bytes = input.readNBytes((maximumBytes + 1L).toInt())
		if (bytes.size > maximumBytes) throw IOException("Response exceeds size limit")
		return bytes
	}

	private fun validateAssetMetadata(asset: GithubAsset, version: String) {
		if (asset.name != "$ARTIFACT_PREFIX$version.jar" || asset.state != "uploaded") {
			throw IOException("Invalid release asset")
		}
		if (asset.size !in MIN_JAR_BYTES..MAX_JAR_BYTES) throw IOException("Release JAR size is outside limits")
		if (DIGEST_PATTERN.matchEntire(asset.digest) == null) throw IOException("Release SHA-256 digest missing")
		validateAssetUri(asset.browserDownloadUrl, asset.name)
	}

	private fun validateAssetUri(value: String, expectedName: String): URI {
		val uri = runCatching { URI.create(value) }.getOrElse { throw IOException("Invalid release URL") }
		if (uri.scheme != "https" || !uri.host.equals("github.com", ignoreCase = true)) throw IOException("Unsafe release URL")
		val expectedSuffix = "/$expectedName"
		if (!uri.path.startsWith("/Xclipsen/xclipsen-mod/releases/download/") || !uri.path.endsWith(expectedSuffix)) {
			throw IOException("Unexpected release URL")
		}
		return uri
	}

	private fun validateDownloadHost(uri: URI) {
		val host = uri.host.orEmpty().lowercase()
		if (uri.scheme != "https" || (host != "github.com" && !host.endsWith(".githubusercontent.com"))) {
			throw IOException("Download redirected to an untrusted host")
		}
	}

	private fun writeBoundedAndDigest(input: InputStream, target: Path, maximumBytes: Long): ByteArray {
		val digest = MessageDigest.getInstance("SHA-256")
		var total = 0L
		Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { output ->
			val buffer = ByteArray(16 * 1024)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				if (count == 0) continue
				total += count
				if (total > maximumBytes) throw IOException("Download exceeds size limit")
				digest.update(buffer, 0, count)
				output.write(buffer, 0, count)
			}
		}
		return digest.digest()
	}

	private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
		value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
	}

	private fun validateJar(path: Path, expectedVersion: String) {
		var entryCount = 0
		var inflatedBytes = 0L
		var metadata: JsonObject? = null
		JarFile(path.toFile(), true).use { jar ->
			val entries = jar.entries()
			while (entries.hasMoreElements()) {
				val entry = entries.nextElement()
				entryCount++
				if (entryCount > MAX_JAR_ENTRIES) throw IOException("JAR contains too many entries")
				val name = entry.name
				if (name.startsWith('/') || name.split('/').any { it == ".." }) throw IOException("Unsafe JAR entry")
				if (entry.isDirectory) continue
				var entryBytes = 0L
				val metadataBytes = if (name == "fabric.mod.json") java.io.ByteArrayOutputStream() else null
				jar.getInputStream(entry).use { input ->
					val buffer = ByteArray(16 * 1024)
					while (true) {
						val count = input.read(buffer)
						if (count < 0) break
						entryBytes += count
						inflatedBytes += count
						if (entryBytes > MAX_JAR_BYTES || inflatedBytes > MAX_INFLATED_JAR_BYTES) throw IOException("JAR contents exceed limits")
						metadataBytes?.write(buffer, 0, count)
					}
				}
				if (metadataBytes != null) {
					if (metadata != null) throw IOException("Duplicate Fabric metadata")
					metadata = gson.fromJson(metadataBytes.toString(Charsets.UTF_8), JsonObject::class.java)
				}
			}
		}
		val modMetadata = metadata ?: throw IOException("Fabric metadata missing")
		if (modMetadata.get("id")?.asString != MOD_ID || modMetadata.get("version")?.asString != expectedVersion) {
			throw IOException("Unexpected mod identity or version")
		}
		if (modMetadata.get("environment")?.asString != "client") throw IOException("Update is not client-only")
		val expectedMinecraft = FabricLoader.getInstance().getModContainer("minecraft")
			.map { it.metadata.version.friendlyString }
			.orElseThrow { IOException("Minecraft metadata unavailable") }
		val minecraftDependency = modMetadata.getAsJsonObject("depends")?.get("minecraft")?.asString
		if (minecraftDependency != expectedMinecraft) throw IOException("Update targets a different Minecraft version")
	}

	private fun installStagedJar(staged: Path, target: Path) {
		if (Files.exists(target)) throw IOException("Target update JAR already exists")
		try {
			Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
		} catch (_: AtomicMoveNotSupportedException) {
			Files.move(staged, target)
		}
		if (!Files.isRegularFile(target)) throw IOException("Installed JAR is missing")
	}

	private fun activeJarPath(): Path? = FabricLoader.getInstance().getModContainer(MOD_ID)
		.orElse(null)
		?.origin
		?.paths
		?.singleOrNull()
		?.toAbsolutePath()
		?.normalize()

	private fun isCanonicalJarName(name: String): Boolean =
		name.startsWith(ARTIFACT_PREFIX) && name.endsWith(".jar") && parseVersion(name.removePrefix(ARTIFACT_PREFIX).removeSuffix(".jar")) != null

	private fun currentVersion(): String {
		val metadataVersion = FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map { it.metadata.version.friendlyString }
			.orElse("0.0.0")
		return metadataVersion
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
		return version.split('.').map(String::toInt)
	}

	private fun parseVersion(version: String): String? {
		val normalized = version.trim().removePrefix("v")
		return normalized.takeIf(VERSION_PATTERN::matches)
	}

	private fun isEnabled(): Boolean {
		return XclipsenIrcBridgeClient.instance?.config()?.checkForUpdatesEnabled == true
	}

	private fun isAutoUpdateEnabled(): Boolean {
		return XclipsenIrcBridgeClient.instance?.config()?.autoUpdateEnabled == true
	}

	private fun isCurrentRequest(generation: Long): Boolean = generation == requestGeneration.get() && isEnabled()

	private fun clickableLink(label: String, url: String): MutableComponent {
		return Component.literal(label).setStyle(
			Style.EMPTY
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(ClickEvent.OpenUrl(URI.create(url))),
		)
	}

	private fun showClientMessage(client: Minecraft?, message: Component) {
		client?.execute {
			client.player?.sendSystemMessage(message) ?: client.gui.chat.addClientSystemMessage(message)
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
		var draft: Boolean = false
		var prerelease: Boolean = false

		var assets: List<GithubAsset> = emptyList()
	}

	private class GithubAsset {
		var name: String = ""
		var state: String = ""
		var size: Long = 0L
		var digest: String = ""

		@SerializedName("browser_download_url")
		var browserDownloadUrl: String = ""
	}
}
