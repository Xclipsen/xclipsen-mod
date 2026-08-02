package de.xclipsen.ircbridge

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.slf4j.Logger
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Locale
import java.util.UUID

class ModBackendCredentialManager(
	private val logger: Logger,
	private val path: Path,
) {
	@Synchronized
	fun credential(backendBaseUrl: String, profileId: UUID): String? {
		val entry = load().credentials[credentialKey(backendBaseUrl, profileId)] ?: return null
		if (entry.expiresAt <= System.currentTimeMillis() || !CREDENTIAL_PATTERN.matches(entry.token)) {
			return null
		}
		return entry.token
	}

	@Synchronized
	@Throws(IOException::class)
	fun store(backendBaseUrl: String, profileId: UUID, account: BackendMinecraftAccount, token: String, expiresAt: Long) {
		require(account.uuid.equals(profileId.toString(), ignoreCase = true)) { "Credential account does not match the active profile." }
		require(CREDENTIAL_PATTERN.matches(token)) { "Credential format is invalid." }
		require(expiresAt > System.currentTimeMillis()) { "Credential is already expired." }

		val state = load()
		state.credentials[credentialKey(backendBaseUrl, profileId)] = StoredCredential().apply {
			this.token = token
			this.expiresAt = expiresAt
			this.minecraftUuid = profileId.toString().lowercase(Locale.ROOT)
			this.minecraftName = account.name.trim().take(16)
		}
		writeAtomically(state)
	}

	@Synchronized
	fun remove(backendBaseUrl: String, profileId: UUID) {
		val state = load()
		if (state.credentials.remove(credentialKey(backendBaseUrl, profileId)) != null) {
			try {
				writeAtomically(state)
			} catch (exception: IOException) {
				logger.warn("Failed to remove expired mod backend credential for profile {}", profileId, exception)
			}
		}
	}

	private fun load(): CredentialFile {
		if (Files.notExists(path)) {
			return CredentialFile()
		}
		return try {
			Files.newBufferedReader(path).use { reader -> GSON.fromJson(reader, CredentialFile::class.java) } ?: CredentialFile()
		} catch (exception: Exception) {
			logger.warn("Failed to load mod backend credential store {}", path, exception)
			CredentialFile()
		}
	}

	private fun writeAtomically(state: CredentialFile) {
		Files.createDirectories(path.parent)
		val temporaryPath = path.resolveSibling("${path.fileName}.tmp")
		try {
			Files.newBufferedWriter(temporaryPath).use { writer -> GSON.toJson(state, writer) }
			setOwnerOnlyPermissions(temporaryPath)
			try {
				Files.move(temporaryPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
			} catch (_: AtomicMoveNotSupportedException) {
				Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING)
			}
			setOwnerOnlyPermissions(path)
		} finally {
			Files.deleteIfExists(temporaryPath)
		}
	}

	private fun setOwnerOnlyPermissions(target: Path) {
		try {
			Files.setPosixFilePermissions(target, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
		} catch (_: UnsupportedOperationException) {
			// Non-POSIX filesystems rely on their platform access controls.
		}
	}

	private fun credentialKey(backendBaseUrl: String, profileId: UUID): String =
		"${normalizeOrigin(backendBaseUrl)}|${profileId.toString().lowercase(Locale.ROOT)}"

	private fun normalizeOrigin(value: String): String {
		val uri = URI.create(value.trim())
		val scheme = uri.scheme?.lowercase(Locale.ROOT)
		require(scheme == "http" || scheme == "https") { "Unsupported backend scheme." }
		val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
		require(host.isNotBlank()) { "Backend host is missing." }
		val defaultPort = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
		return "$scheme://$host${if (uri.port >= 0 && !defaultPort) ":${uri.port}" else ""}"
	}

	private class CredentialFile {
		@JvmField var schemaVersion: Int = 1
		@JvmField var credentials: MutableMap<String, StoredCredential> = mutableMapOf()
	}

	private class StoredCredential {
		@JvmField var token: String = ""
		@JvmField var expiresAt: Long = 0L
		@JvmField var minecraftUuid: String = ""
		@JvmField var minecraftName: String = ""
	}

	companion object {
		private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
		private val CREDENTIAL_PATTERN = Regex("[A-Za-z0-9_-]{43}")
	}
}
