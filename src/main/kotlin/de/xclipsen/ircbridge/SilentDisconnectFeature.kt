package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayNetworkHandler
import net.minecraft.text.Text
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.Locale

object SilentDisconnectFeature {
	private val logger: Logger = LoggerFactory.getLogger("xclipsen_mod")
	private val statusCommandPattern = Regex("^status\\s+(online|busy|away|offline)\\s*$", RegexOption.IGNORE_CASE)
	private val statusMessagePattern = Regex("^Your online status has been set to\\s+(.+?)\\s*$", RegexOption.IGNORE_CASE)
	private const val suppressionTtlMs = 15_000L
	private const val internalCommandTtlMs = 5_000L

	private val suppressionLock = Any()
	private val pendingSuppressions: ArrayDeque<PendingStatusSuppression> = ArrayDeque()
	private val internalCommandLock = Any()
	private val pendingInternalCommands: ArrayDeque<PendingInternalCommand> = ArrayDeque()

	@Volatile
	private var disconnectHandled = false

	fun onOutgoingCommand(command: String) {
		val normalizedStatus = parseStatusCommand(command) ?: return
		if (consumeInternalCommand(normalizedStatus)) {
			return
		}
		updateStoredStatus(normalizedStatus)
	}

	fun onDisconnectStarting() {
		if (disconnectHandled) {
			return
		}

		val mod = XclipsenIrcBridgeClient.instance ?: return
		val config = mod.config()
		if (!config.silentDisconnectModuleEnabled) {
			return
		}

		val client = MinecraftClient.getInstance()
		if (!isHypixelAddress(client.currentServerEntry?.address) && !LocationTracker.isOnHypixel) {
			return
		}

		disconnectHandled = true
		if (!sendStatusCommand(client, "offline")) {
			return
		}

		config.silentDisconnectRestorePending = true
		persistConfig("persist silent disconnect pending state")
		enqueueSuppression("offline")
	}

	fun onPlayDisconnect() {
		synchronized(suppressionLock) {
			pendingSuppressions.removeAll { it.status == "offline" }
		}
	}

	fun onJoin(handler: ClientPlayNetworkHandler, client: MinecraftClient) {
		disconnectHandled = false

		val mod = XclipsenIrcBridgeClient.instance ?: return
		val config = mod.config()
		if (!config.silentDisconnectModuleEnabled || !config.silentDisconnectRestorePending) {
			return
		}

		if (!isHypixelAddress(handler.serverInfo?.address) && !isHypixelAddress(client.currentServerEntry?.address)) {
			return
		}

		val statusToRestore = normalizeStatus(config.silentDisconnectLastStatus)
		if (!sendStatusCommand(handler, statusToRestore)) {
			return
		}

		config.silentDisconnectRestorePending = false
		persistConfig("persist silent disconnect restore state")
		enqueueSuppression(statusToRestore)
	}

	fun shouldSuppressStatusMessage(message: Text?): Boolean {
		val raw = message?.string?.trim().orEmpty()
		if (raw.isEmpty()) {
			return false
		}

		val match = statusMessagePattern.matchEntire(raw) ?: return false
		val reportedStatus = normalizeStatus(match.groupValues[1])

		synchronized(suppressionLock) {
			pruneSuppressions()
			val index = pendingSuppressions.indexOfFirst { it.status == reportedStatus }
			if (index < 0) {
				return false
			}

			pendingSuppressions.removeAt(index)
			return true
		}
	}

	fun statusLine(config: BridgeConfig): String {
		val last = normalizeStatus(config.silentDisconnectLastStatus)
		val restoreState = if (config.silentDisconnectRestorePending) "pending restore" else "idle"
		return "Hypixel only | last: $last | state: $restoreState"
	}

	private fun updateStoredStatus(status: String) {
		val mod = XclipsenIrcBridgeClient.instance ?: return
		val config = mod.config()
		val normalizedStatus = normalizeStatus(status)
		if (config.silentDisconnectLastStatus == normalizedStatus) {
			return
		}

		config.silentDisconnectLastStatus = normalizedStatus
		persistConfig("persist silent disconnect status")
	}

	private fun sendStatusCommand(client: MinecraftClient, status: String): Boolean {
		val networkHandler = client.player?.networkHandler ?: return false
		return sendStatusCommand(networkHandler, status)
	}

	private fun sendStatusCommand(handler: ClientPlayNetworkHandler, status: String): Boolean {
		val normalizedStatus = normalizeStatus(status)
		enqueueInternalCommand(normalizedStatus)
		return try {
			handler.sendChatCommand("status $normalizedStatus")
			true
		} catch (exception: RuntimeException) {
			discardInternalCommand(normalizedStatus)
			logger.warn("Failed to send silent disconnect status command.", exception)
			false
		}
	}

	private fun persistConfig(action: String) {
		val mod = XclipsenIrcBridgeClient.instance ?: return
		try {
			mod.saveCurrentConfig()
		} catch (exception: IOException) {
			logger.warn("Failed to {}.", action, exception)
		}
	}

	private fun enqueueSuppression(status: String) {
		synchronized(suppressionLock) {
			pruneSuppressions()
			pendingSuppressions.addLast(PendingStatusSuppression(normalizeStatus(status), System.currentTimeMillis() + suppressionTtlMs))
		}
	}

	private fun enqueueInternalCommand(status: String) {
		synchronized(internalCommandLock) {
			pruneInternalCommands()
			pendingInternalCommands.addLast(PendingInternalCommand(status, System.currentTimeMillis() + internalCommandTtlMs))
		}
	}

	private fun consumeInternalCommand(status: String): Boolean {
		synchronized(internalCommandLock) {
			pruneInternalCommands()
			val index = pendingInternalCommands.indexOfFirst { it.status == status }
			if (index < 0) {
				return false
			}

			pendingInternalCommands.removeAt(index)
			return true
		}
	}

	private fun discardInternalCommand(status: String) {
		synchronized(internalCommandLock) {
			pruneInternalCommands()
			val index = pendingInternalCommands.indexOfFirst { it.status == status }
			if (index >= 0) {
				pendingInternalCommands.removeAt(index)
			}
		}
	}

	private fun pruneSuppressions() {
		val now = System.currentTimeMillis()
		while (pendingSuppressions.isNotEmpty() && pendingSuppressions.first().expiresAt <= now) {
			pendingSuppressions.removeFirst()
		}
	}

	private fun pruneInternalCommands() {
		val now = System.currentTimeMillis()
		while (pendingInternalCommands.isNotEmpty() && pendingInternalCommands.first().expiresAt <= now) {
			pendingInternalCommands.removeFirst()
		}
	}

	private fun parseStatusCommand(command: String): String? {
		val match = statusCommandPattern.matchEntire(command.trim()) ?: return null
		return normalizeStatus(match.groupValues[1])
	}

	private fun normalizeStatus(value: String): String {
		val normalized = value.trim().lowercase(Locale.ROOT)
		return if (normalized in validStatuses) normalized else "online"
	}

	private fun isHypixelAddress(address: String?): Boolean {
		val normalized = address?.trim()?.lowercase(Locale.ROOT) ?: return false
		return normalized == "hypixel.net" || normalized.endsWith(".hypixel.net")
	}

	private val validStatuses = setOf("online", "busy", "away", "offline")

	private data class PendingStatusSuppression(
		val status: String,
		val expiresAt: Long,
	)

	private data class PendingInternalCommand(
		val status: String,
		val expiresAt: Long,
	)
}
