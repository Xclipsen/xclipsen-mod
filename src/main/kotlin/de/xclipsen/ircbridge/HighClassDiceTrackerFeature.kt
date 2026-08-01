package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object HighClassDiceTrackerFeature {
	private const val POLL_INTERVAL_MS = 5L * 60L * 1000L
	private const val ALERT_COOLDOWN_MS = 30L * 60L * 1000L

	private val logger = LoggerFactory.getLogger("xclipsen_high_class_dice_tracker")
	private var scheduler: ScheduledExecutorService? = null
	@Volatile private var lastAlertAt = 0L
	@Volatile private var currentlyGood = false

	@Synchronized
	fun init() {
		restartScheduler()
	}

	@Synchronized
	fun onConfigChanged() {
		val enabled = XclipsenIrcBridgeClient.instance?.config()?.highClassDiceTrackerEnabled == true
		if (!enabled) {
			lastAlertAt = 0L
			currentlyGood = false
		}
		if (scheduler == null || scheduler?.isShutdown == true) {
			restartScheduler()
		}
	}

	@Synchronized
	fun shutdown() {
		scheduler?.shutdownNow()
		scheduler = null
	}

	fun requestStatus(onMessage: (Text) -> Unit) {
		ensureScheduler()
		scheduler?.execute {
			val response = fetchTracker()
			val text = if (response == null) {
				Text.literal("High Class Dice tracker status unavailable. Make sure the Xclipsen backend is updated.")
			} else {
				statusText(response)
			}
			MinecraftClient.getInstance().execute {
				onMessage(text)
			}
		}
	}

	private fun restartScheduler() {
		scheduler?.shutdownNow()
		scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "xclipsen-highclass-dice-tracker").apply { isDaemon = true }
		}
		scheduler?.scheduleAtFixedRate(::poll, 5_000L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
	}

	@Synchronized
	private fun ensureScheduler() {
		if (scheduler == null || scheduler?.isShutdown == true) {
			restartScheduler()
		}
	}

	private fun poll() {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.highClassDiceTrackerEnabled) {
			currentlyGood = false
			return
		}

		val response = fetchTracker() ?: return
		if (!response.ok) {
			logger.debug("High Class Dice tracker returned not-ok: {}", response.error)
			return
		}

		if (!response.goodToSell) {
			currentlyGood = false
			lastAlertAt = 0L
			return
		}

		val now = System.currentTimeMillis()
		if (currentlyGood && now - lastAlertAt < ALERT_COOLDOWN_MS) {
			return
		}

		currentlyGood = true
		lastAlertAt = now
		sendNotification(notificationText(response))
	}

	private fun fetchTracker(): BackendHighClassDiceTrackerResponse? {
		return try {
			XclipsenIrcBridgeClient.instance?.backendBridge()?.fetchHighClassDiceTracker()
		} catch (exception: RuntimeException) {
			logger.debug("High Class Dice tracker poll failed", exception)
			null
		}
	}

	private fun sendNotification(message: String) {
		val client = MinecraftClient.getInstance()
		client.execute {
			client.player?.sendMessage(Text.literal(message), false)
		}
	}

	private fun statusText(response: BackendHighClassDiceTrackerResponse): Text {
		if (!response.ok) {
			return Text.literal("High Class Dice tracker status unavailable: ${response.error.ifBlank { "unknown error" }}")
		}

		val verdict = if (response.goodToSell) "good to sell" else "not a sell signal"
		return Text.literal(
			"High Class Dice: LBIN ${formatCoins(response.currentLbin)} coins, " +
				"${formatPercent(response.percentAboveMedian)} above 7d median ${formatCoins(response.median7d)}. " +
				"Status: $verdict.",
		)
	}

	private fun notificationText(response: BackendHighClassDiceTrackerResponse): String {
		return "High Class Dice sell signal: LBIN ${formatCoins(response.currentLbin)} coins, " +
			"${formatPercent(response.percentAboveMedian)} above 7d median ${formatCoins(response.median7d)}."
	}

	private fun formatCoins(value: Long): String = String.format(Locale.US, "%,d", value)

	private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)
}
