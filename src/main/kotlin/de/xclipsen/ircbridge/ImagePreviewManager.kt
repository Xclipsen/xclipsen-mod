package de.xclipsen.ircbridge

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.gui.GuiGraphicsExtractor
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

object ImagePreviewManager {
	private const val MAX_REDIRECTS = 4
	private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
	private const val MAX_IMAGE_DIMENSION = 4096
	private const val MAX_IMAGE_PIXELS = 16_777_216L
	private const val MAX_CACHE_ENTRIES = 32
	private val allowedContentTypes = setOf("image/png", "image/jpeg", "image/gif")
	private val trustedImageHosts = setOf(
		"cdn.discordapp.com",
		"media.discordapp.net",
		"images-ext-1.discordapp.net",
		"images-ext-2.discordapp.net",
	)
	private val httpClient: HttpClient = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(10))
		.build()

	private val downloadExecutor = Executors.newFixedThreadPool(2) { runnable ->
		Thread(runnable, "xclipsen-image-preview").apply { isDaemon = true }
	}

	private val cacheLock = Any()
	private val previews = LinkedHashMap<String, PreviewState>(16, 0.75f, true)
	private val generation = AtomicLong()
	private val textureSequence = AtomicLong()
	private val shutDown = AtomicBoolean()
	@Volatile
	private var hoverPreviewActive = false

	fun renderHoverPreview(graphics: GuiGraphicsExtractor, style: Style?, mouseX: Int, mouseY: Int) {
		val imageUrl = extractImageUrl(style)
		hoverPreviewActive = imageUrl != null
		XclipsenIrcBridgeClient.instance?.setPreviewHoverPaused(imageUrl != null)
		imageUrl ?: return
		val state = previewState(imageUrl)
		state.requestLoad(imageUrl)

		val client = Minecraft.getInstance() ?: return
		when (val current = state.state.get()) {
			is LoadedPreview -> renderLoadedPreview(graphics, client, current, mouseX, mouseY)
			is FailedPreview -> graphics.setTooltipForNextFrame(client.font, Component.literal(current.message), mouseX, mouseY)
			else -> graphics.setTooltipForNextFrame(client.font, Component.literal("Loading image preview..."), mouseX, mouseY)
		}
	}

	private fun renderLoadedPreview(
		graphics: GuiGraphicsExtractor,
		client: Minecraft,
		preview: LoadedPreview,
		mouseX: Int,
		mouseY: Int,
	) {
		val fullscreen = client.hasShiftDown()
		val scaledWidth = graphics.guiWidth()
		val scaledHeight = graphics.guiHeight()
		val maxWidth = if (fullscreen) {
			max(320, scaledWidth - 80)
		} else {
			max(220, scaledWidth * 35 / 100)
		}
		val maxHeight = if (fullscreen) {
			max(220, scaledHeight - 80)
		} else {
			max(160, scaledHeight * 35 / 100)
		}
		val scale = if (fullscreen) {
			min(maxWidth.toFloat() / preview.width.toFloat(), maxHeight.toFloat() / preview.height.toFloat())
		} else {
			min(1.0f, min(maxWidth.toFloat() / preview.width.toFloat(), maxHeight.toFloat() / preview.height.toFloat()))
		}
		val renderWidth = max(1, (preview.width * scale).toInt())
		val renderHeight = max(1, (preview.height * scale).toInt())
		val padding = 8
		val shadowPadding = 2
		val footerHeight = client.font.lineHeight + 6

		var x = if (fullscreen) {
			(scaledWidth - renderWidth - padding * 2) / 2
		} else {
			mouseX + 18
		}
		var y = if (fullscreen) {
			(scaledHeight - renderHeight - padding * 2 - footerHeight) / 2
		} else {
			mouseY + 18
		}

		if (!fullscreen && x + renderWidth + padding * 2 > scaledWidth) {
			x = mouseX - renderWidth - padding * 2 - 18
		}
		if (x < 4) {
			x = 4
		}

		if (!fullscreen && y + renderHeight + padding * 2 + footerHeight > scaledHeight) {
			y = scaledHeight - renderHeight - padding * 2 - footerHeight - 4
		}
		if (y < 4) {
			y = 4
		}

		val panelLeft = x
		val panelTop = y
		val panelRight = x + renderWidth + padding * 2
		val panelBottom = y + renderHeight + padding * 2 + footerHeight
		val imageLeft = x + padding
		val imageTop = y + padding
		val imageBottom = imageTop + renderHeight

		if (fullscreen) {
			graphics.fill(0, 0, scaledWidth, scaledHeight, 0x96000000.toInt())
		}

		graphics.fill(
			panelLeft + shadowPadding,
			panelTop + shadowPadding,
			panelRight + shadowPadding,
			panelBottom + shadowPadding,
			0x70000000,
		)
		graphics.fill(panelLeft, panelTop, panelRight, panelBottom, 0xE1111111.toInt())
		graphics.fill(panelLeft + 1, panelTop + 1, panelRight - 1, panelBottom - 1, 0xF0191B26.toInt())
		graphics.fill(panelLeft + 1, panelTop + 1, panelRight - 1, panelTop + 3, 0xFF8AA0FF.toInt())
		graphics.fill(imageLeft - 1, imageTop - 1, imageLeft + renderWidth + 1, imageBottom + 1, 0xCC000000.toInt())

		graphics.blit(
			RenderPipelines.GUI_TEXTURED,
			preview.textureId,
			imageLeft,
			imageTop,
			0f,
			0f,
			renderWidth,
			renderHeight,
			preview.width,
			preview.height,
			preview.width,
			preview.height,
		)

		val dimensionText = if (fullscreen) {
			"${preview.width}x${preview.height}  |  Hold Shift for fullscreen"
		} else {
			"${preview.width}x${preview.height}  |  Hold Shift"
		}
		graphics.text(
			client.font,
			Component.literal(dimensionText),
			panelLeft + padding,
			imageBottom + 4,
			0xFFD7DBFF.toInt(),
			false,
		)
	}

	private fun extractImageUrl(style: Style?): String? {
		val clickEvent = style?.clickEvent ?: return null
		if (clickEvent.action() != ClickEvent.Action.OPEN_URL) {
			return null
		}

		val value = when (clickEvent) {
			is ClickEvent.OpenUrl -> clickEvent.uri().toString()
			else -> ""
		}.trim()
		return value.takeIf(::isPreviewableImageUrl)
	}

	fun setHoverPreviewActive(active: Boolean) {
		hoverPreviewActive = active
	}

	fun isHoverPreviewActive(): Boolean = hoverPreviewActive

	/** Invalidates in-flight work and releases cached textures. Call on disconnect or world replacement. */
	fun reset() {
		generation.incrementAndGet()
		hoverPreviewActive = false
		val removed = synchronized(cacheLock) {
			previews.values.toList().also { previews.clear() }
		}
		removed.forEach(PreviewState::dispose)
	}

	/** Permanently stops image loading. Call once when the Minecraft client is stopping. */
	fun shutdown() {
		if (shutDown.compareAndSet(false, true)) {
			reset()
			downloadExecutor.shutdownNow()
		}
	}

	private fun previewState(url: String): PreviewState {
		val removed = ArrayList<PreviewState>(1)
		val state = synchronized(cacheLock) {
			previews[url] ?: PreviewState(generation.get()).also {
				previews[url] = it
				while (previews.size > MAX_CACHE_ENTRIES) {
					val iterator = previews.entries.iterator()
					removed += iterator.next().value
					iterator.remove()
				}
			}
		}
		removed.forEach(PreviewState::dispose)
		return state
	}

	private fun isPreviewableImageUrl(url: String): Boolean {
		val uri = try {
			URI.create(url)
		} catch (_: IllegalArgumentException) {
			return false
		}
		if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.rawUserInfo != null) {
			return false
		}
		val path = uri.path.orEmpty().lowercase(Locale.ROOT)

		return path.endsWith(".png") ||
			path.endsWith(".jpg") ||
			path.endsWith(".jpeg") ||
			path.endsWith(".gif")
	}

	private fun validatedPublicUri(uri: URI): URI {
		val scheme = uri.scheme?.lowercase(Locale.ROOT)
		val host = uri.host?.lowercase(Locale.ROOT)
		require(scheme == "https") { "Image previews require HTTPS" }
		require(uri.rawUserInfo == null) { "Image URL userinfo is not allowed" }
		require(host in trustedImageHosts && uri.port == -1) { "Image URL host is not trusted" }
		val addresses = InetAddress.getAllByName(host)
		require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) { "Image URL is not public" }
		return uri
	}

	private fun isPublicAddress(address: InetAddress): Boolean {
		if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
			address.isSiteLocalAddress || address.isMulticastAddress
		) {
			return false
		}
		val bytes = address.address
		if (bytes.size == 4) {
			val first = bytes[0].toInt() and 0xff
			val second = bytes[1].toInt() and 0xff
			val third = bytes[2].toInt() and 0xff
			return when {
				first == 0 || first == 10 || first == 127 || first >= 224 -> false
				first == 100 && second in 64..127 -> false
				first == 169 && second == 254 -> false
				first == 172 && second in 16..31 -> false
				first == 192 && second == 0 && (third == 0 || third == 2) -> false
				first == 192 && second == 88 && third == 99 -> false
				first == 192 && second == 168 -> false
				first == 198 && second in 18..19 -> false
				first == 198 && second == 51 && third == 100 -> false
				first == 203 && second == 0 && third == 113 -> false
				else -> true
			}
		}
		if (bytes.size != 16) {
			return false
		}
		val first = bytes[0].toInt() and 0xff
		val second = bytes[1].toInt() and 0xff
		return when {
			first and 0xfe == 0xfc -> false
			first == 0x00 && second == 0x64 && bytes[2] == 0xff.toByte() && bytes[3] == 0x9b.toByte() -> false
			first == 0x01 && second == 0x00 -> false
			first == 0x20 && second == 0x01 && bytes[2] == 0x0d.toByte() && bytes[3] == 0xb8.toByte() -> false
			first == 0x20 && second == 0x01 && bytes[2] == 0.toByte() && bytes[3] == 0.toByte() -> false
			first == 0x20 && second == 0x02 -> false
			first == 0x3f && second and 0xf0 == 0xf0 -> false
			first == 0x5f -> false
			else -> true
		}
	}

	private fun download(uri: URI): ByteArray {
		var current = validatedPublicUri(uri)
		repeat(MAX_REDIRECTS + 1) { redirectCount ->
			val request = HttpRequest.newBuilder(current)
				.timeout(Duration.ofSeconds(15))
				.header("Accept", allowedContentTypes.joinToString(", "))
				.GET()
				.build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
			response.body().use { body ->
				if (response.statusCode() in setOf(301, 302, 303, 307, 308)) {
					require(redirectCount < MAX_REDIRECTS) { "Too many image redirects" }
					val location = response.headers().firstValue("Location").orElseThrow {
						IllegalArgumentException("Image redirect has no location")
					}
					val target = validatedPublicUri(current.resolve(location))
					require(current.scheme.lowercase(Locale.ROOT) != "https" || target.scheme.lowercase(Locale.ROOT) == "https") {
						"HTTPS image redirect downgrade rejected"
					}
					current = target
					return@use
				}
				require(response.statusCode() == 200) { "Image response was ${response.statusCode()}" }
				val contentType = response.headers().firstValue("Content-Type").orElse("")
					.substringBefore(';').trim().lowercase(Locale.ROOT)
				require(contentType in allowedContentTypes) { "Unsupported image content type" }
				val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
				require(contentLength in -1L..MAX_RESPONSE_BYTES.toLong()) { "Image response is too large" }
				return readBounded(body)
			}
		}
		error("Too many image redirects")
	}

	private fun readBounded(input: InputStream): ByteArray {
		val output = ByteArrayOutputStream()
		val buffer = ByteArray(8192)
		var total = 0
		while (true) {
			val read = input.read(buffer)
			if (read < 0) break
			total += read
			require(total <= MAX_RESPONSE_BYTES) { "Image response is too large" }
			output.write(buffer, 0, read)
		}
		return output.toByteArray()
	}

	private fun probeDimensions(data: ByteArray): Pair<Int, Int> {
		ImageIO.createImageInputStream(ByteArrayInputStream(data)).use { input ->
			val readers = ImageIO.getImageReaders(input)
			require(readers.hasNext()) { "Unsupported image encoding" }
			val reader = readers.next()
			try {
				reader.input = input
				return reader.getWidth(0) to reader.getHeight(0)
			} finally {
				reader.dispose()
			}
		}
	}

	private fun dimensionsAllowed(width: Int, height: Int): Boolean =
		width > 0 && height > 0 && width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION &&
			width.toLong() * height.toLong() <= MAX_IMAGE_PIXELS

	private fun textureId(url: String): Identifier {
		val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
		val hash = digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
		return Identifier.fromNamespaceAndPath(
			"xclipsen_mod",
			"preview/$hash/${generation.get()}-${textureSequence.incrementAndGet()}",
		)
	}

	private fun releaseTexture(textureId: Identifier) {
		val client = Minecraft.getInstance()
		if (client.isSameThread) {
			client.textureManager.release(textureId)
		} else {
			client.execute { client.textureManager.release(textureId) }
		}
	}

	private class PreviewState(private val requestGeneration: Long) {
		val state: AtomicReference<Any> = AtomicReference(NotLoadedPreview)
		private val disposed = AtomicBoolean()
		private val pendingImage = AtomicReference<NativeImage?>()

		fun requestLoad(url: String) {
			if (shutDown.get() || disposed.get() || !state.compareAndSet(NotLoadedPreview, LoadingPreview)) {
				return
			}

			try {
				downloadExecutor.execute {
					try {
						val imageData = download(URI.create(url))
						val (probedWidth, probedHeight) = probeDimensions(imageData)
						if (!dimensionsAllowed(probedWidth, probedHeight)) {
							fail("Image dimensions are too large.")
							return@execute
						}
						ByteArrayInputStream(imageData).use { stream ->
							val image = NativeImage.read(stream)
							val width = image.width
							val height = image.height
							if (!dimensionsAllowed(width, height) || width != probedWidth || height != probedHeight) {
								image.close()
								fail("Image dimensions are too large.")
								return@use
							}
							if (!isCurrent() || !pendingImage.compareAndSet(null, image)) {
								image.close()
								return@use
							}
							val client = Minecraft.getInstance()

							client.execute {
								val ownedImage = pendingImage.getAndSet(null) ?: return@execute
								if (!isCurrent()) {
									ownedImage.close()
									return@execute
								}
								var texture: DynamicTexture? = null
								try {
									val textureId = textureId(url)
									texture = DynamicTexture({ "IRC preview" }, ownedImage)
									client.textureManager.register(textureId, texture)
									val loaded = LoadedPreview(textureId, width, height)
									if (!isCurrent() || !state.compareAndSet(LoadingPreview, loaded)) {
										client.textureManager.release(textureId)
									}
								} catch (exception: Exception) {
									texture?.close() ?: ownedImage.close()
									fail("Image preview failed.")
								}
							}
						}
					} catch (_: InterruptedException) {
						Thread.currentThread().interrupt()
						fail("Image preview cancelled.")
					} catch (_: Exception) {
						fail("Image preview failed.")
					}
				}
			} catch (_: RejectedExecutionException) {
				fail("Image preview unavailable.")
			}
		}

		fun dispose() {
			if (!disposed.compareAndSet(false, true)) return
			pendingImage.getAndSet(null)?.close()
			(state.getAndSet(NotLoadedPreview) as? LoadedPreview)?.let { releaseTexture(it.textureId) }
		}

		private fun isCurrent(): Boolean = !disposed.get() && !shutDown.get() && generation.get() == requestGeneration

		private fun fail(message: String) {
			if (isCurrent()) state.set(FailedPreview(message))
		}
	}

	private data object NotLoadedPreview
	private data object LoadingPreview
	private data class FailedPreview(val message: String)
	private data class LoadedPreview(
		val textureId: Identifier,
		val width: Int,
		val height: Int,
	)
}
