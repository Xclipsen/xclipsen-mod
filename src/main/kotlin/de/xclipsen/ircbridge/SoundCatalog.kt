package de.xclipsen.ircbridge

import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.util.Identifier
import java.util.Locale

object SoundCatalog {
	private const val DEFAULT_SOUND_ID = "minecraft:block.note_block.pling"

	val defaultSoundId: String = DEFAULT_SOUND_ID

	val entries: List<SoundEntry> by lazy {
		Registries.SOUND_EVENT.ids
			.map { id -> SoundEntry(id.toString(), prettyName(id)) }
			.sortedWith(compareBy<SoundEntry> { it.name }.thenBy { it.id })
	}

	fun normalizeSoundId(value: String?): String {
		val id = Identifier.tryParse(value?.trim().orEmpty()) ?: return DEFAULT_SOUND_ID
		return if (Registries.SOUND_EVENT.containsId(id)) id.toString() else DEFAULT_SOUND_ID
	}

	fun soundEvent(value: String?): SoundEvent {
		val id = Identifier.tryParse(normalizeSoundId(value)) ?: Identifier.of("minecraft", "block.note_block.pling")
		val event: SoundEvent? = Registries.SOUND_EVENT.get(id)
		return event ?: SoundEvents.BLOCK_NOTE_BLOCK_PLING.value()
	}

	fun displayName(value: String?): String {
		val id = Identifier.tryParse(normalizeSoundId(value)) ?: return prettyName(Identifier.of("minecraft", "block.note_block.pling"))
		return prettyName(id)
	}

	fun filtered(query: String): List<SoundEntry> {
		val normalizedQuery = query.trim()
		if (normalizedQuery.isBlank()) {
			return entries
		}

		return entries.filter {
			it.name.contains(normalizedQuery, ignoreCase = true) ||
				it.id.contains(normalizedQuery, ignoreCase = true)
		}
	}

	private fun prettyName(id: Identifier): String {
		val prefix = if (id.namespace == "minecraft") "" else "${id.namespace.uppercase(Locale.ROOT)}:"
		val parts = id.path.split('.', '_')
			.filter { it.isNotBlank() && it != "block" && it != "entity" && it != "item" }
		val name = parts.joinToString("_") { it.uppercase(Locale.ROOT) }
		return prefix + name.ifBlank { id.path.uppercase(Locale.ROOT) }
	}
}

data class SoundEntry(
	val id: String,
	val name: String,
)
