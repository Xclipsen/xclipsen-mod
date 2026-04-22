package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.text.ClickEvent
import net.minecraft.text.Text
import net.minecraft.text.Style
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

object ImagePreviewManager {
	private val httpClient: HttpClient = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(10))
		.build()

	private val downloadExecutor = Executors.newFixedThreadPool(2) { runnable ->
		Thread(runnable, "xclipsen-image-preview").apply { isDaemon = true }
	}

	private val previews = ConcurrentHashMap<String, PreviewState>()
	@Volatile
	private var hoverPreviewActive = false

	fun renderHoverPreview(graphics: DrawContext, style: Style?, mouseX: Int, mouseY: Int) {
		val imageUrl = extractImageUrl(style)
		hoverPreviewActive = imageUrl != null
		XclipsenIrcBridgeClient.instance?.setPreviewHoverPaused(imageUrl != null)
		imageUrl ?: return
		val state = previews.computeIfAbsent(imageUrl) { PreviewState() }
		state.requestLoad(imageUrl)

		val client = MinecraftClient.getInstance() ?: return
		when (val current = state.state.get()) {
			is LoadedPreview -> renderLoadedPreview(graphics, client, current, mouseX, mouseY)
			is FailedPreview -> graphics.drawTooltip(client.textRenderer, Text.literal(current.message), mouseX, mouseY)
			else -> graphics.drawTooltip(client.textRenderer, Text.literal("Loading image preview..."), mouseX, mouseY)
		}
	}

	private fun renderLoadedPreview(
		graphics: DrawContext,
		client: MinecraftClient,
		preview: LoadedPreview,
		mouseX: Int,
		mouseY: Int,
	) {
		val fullscreen = isShiftDown(client)
		val maxWidth = if (fullscreen) {
			max(320, client.window.scaledWidth - 80)
		} else {
			max(220, client.window.scaledWidth * 35 / 100)
		}
		val maxHeight = if (fullscreen) {
			max(220, client.window.scaledHeight - 80)
		} else {
			max(160, client.window.scaledHeight * 35 / 100)
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
		val footerHeight = client.textRenderer.fontHeight + 6

		var x = if (fullscreen) {
			(client.window.scaledWidth - renderWidth - padding * 2) / 2
		} else {
			mouseX + 18
		}
		var y = if (fullscreen) {
			(client.window.scaledHeight - renderHeight - padding * 2 - footerHeight) / 2
		} else {
			mouseY + 18
		}

		if (!fullscreen && x + renderWidth + padding * 2 > client.window.scaledWidth) {
			x = mouseX - renderWidth - padding * 2 - 18
		}
		if (x < 4) {
			x = 4
		}

		if (!fullscreen && y + renderHeight + padding * 2 + footerHeight > client.window.scaledHeight) {
			y = client.window.scaledHeight - renderHeight - padding * 2 - footerHeight - 4
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
			graphics.fill(0, 0, client.window.scaledWidth, client.window.scaledHeight, 0x96000000.toInt())
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

		graphics.drawTexture(
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
		graphics.drawText(
			client.textRenderer,
			Text.literal(dimensionText),
			panelLeft + padding,
			imageBottom + 4,
			0xFFD7DBFF.toInt(),
			false,
		)
	}

	private fun extractImageUrl(style: Style?): String? {
		val clickEvent = style?.clickEvent ?: return null
		if (clickEvent.action != ClickEvent.Action.OPEN_URL) {
			return null
		}

		val value = when (clickEvent) {
			is ClickEvent.OpenUrl -> clickEvent.uri().toString()
			else -> ""
		}.trim()
		return value.takeIf(::isPreviewableImageUrl)
	}

	private fun isShiftDown(client: MinecraftClient): Boolean {
		val handle = client.window.handle
		return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
			GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS
	}

	fun setHoverPreviewActive(active: Boolean) {
		hoverPreviewActive = active
	}

	fun isHoverPreviewActive(): Boolean = hoverPreviewActive

	private fun isPreviewableImageUrl(url: String): Boolean {
		if (!url.startsWith("http://") && !url.startsWith("https://")) {
			return false
		}

		val path = try {
			URI.create(url).path.orEmpty().lowercase()
		} catch (_: IllegalArgumentException) {
			return false
		}

		return path.endsWith(".png") ||
			path.endsWith(".jpg") ||
			path.endsWith(".jpeg") ||
			path.endsWith(".webp") ||
			path.endsWith(".gif")
	}

	private class PreviewState {
		val state: AtomicReference<Any> = AtomicReference(NotLoadedPreview)

		fun requestLoad(url: String) {
			if (!state.compareAndSet(NotLoadedPreview, LoadingPreview)) {
				return
			}

			downloadExecutor.execute {
				try {
					val request = HttpRequest.newBuilder(URI.create(url))
						.timeout(Duration.ofSeconds(15))
						.GET()
						.build()
					val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
					if (response.statusCode() != 200) {
						state.set(FailedPreview("Image preview failed (${response.statusCode()})."))
						return@execute
					}

					ByteArrayInputStream(response.body()).use { stream ->
						val image = NativeImage.read(stream)
						val width = image.width
						val height = image.height
						val client = MinecraftClient.getInstance()
						if (client == null) {
							image.close()
							state.set(FailedPreview("Minecraft client unavailable."))
							return@use
						}

						client.execute {
							try {
								val textureId = Identifier.of("xclipsen_mod", "preview/${url.hashCode().toUInt().toString(16)}")
								client.textureManager.registerTexture(textureId, NativeImageBackedTexture({ "IRC preview" }, image))
								state.set(LoadedPreview(textureId, width, height))
							} catch (exception: Exception) {
								image.close()
								state.set(FailedPreview("Image preview failed."))
							}
						}
					}
				} catch (_: Exception) {
					state.set(FailedPreview("Image preview failed."))
				}
			}
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
