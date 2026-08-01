package de.xclipsen.ircbridge

import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.sounds.SoundSource
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.resources.Identifier
import net.minecraft.util.RandomSource
import java.util.Locale

object SoundCatalog {
	private const val DEFAULT_SOUND_ID = "minecraft:block.note_block.pling"

	val defaultSoundId: String = DEFAULT_SOUND_ID

	val entries: List<SoundEventRegistration> by lazy {
		BuiltInRegistries.SOUND_EVENT.keySet()
			.map { id -> SoundEventRegistration(id.toString(), prettyName(id)) }
			.sortedWith(compareBy<SoundEventRegistration> { it.name }.thenBy { it.id })
	}

	fun normalizeSoundId(value: String?): String {
		val id = Identifier.tryParse(value?.trim().orEmpty()) ?: return DEFAULT_SOUND_ID
		return if (BuiltInRegistries.SOUND_EVENT.containsKey(id)) id.toString() else DEFAULT_SOUND_ID
	}

	fun soundEvent(value: String?): SoundEvent {
		val id = Identifier.tryParse(normalizeSoundId(value)) ?: Identifier.withDefaultNamespace("block.note_block.pling")
		return BuiltInRegistries.SOUND_EVENT.getValue(id) ?: SoundEvents.NOTE_BLOCK_PLING.value()
	}

	fun masterSound(value: String?, pitch: Float, volume: Float): SimpleSoundInstance {
		return SimpleSoundInstance(
			soundEvent(value),
			SoundSource.MASTER,
			volume,
			pitch,
			RandomSource.create(),
			0.0,
			0.0,
			0.0,
		)
	}

	fun displayName(value: String?): String {
		val id = Identifier.tryParse(normalizeSoundId(value)) ?: return prettyName(Identifier.withDefaultNamespace("block.note_block.pling"))
		return prettyName(id)
	}

	fun filtered(query: String): List<SoundEventRegistration> {
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

data class SoundEventRegistration(
	val id: String,
	val name: String,
)
