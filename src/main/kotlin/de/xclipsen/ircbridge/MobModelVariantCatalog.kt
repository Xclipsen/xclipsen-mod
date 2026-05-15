package de.xclipsen.ircbridge

import de.xclipsen.ircbridge.mixin.EntityComponentInvoker
import de.xclipsen.ircbridge.mixin.FoxEntityInvoker
import de.xclipsen.ircbridge.mixin.HorseEntityInvoker
import de.xclipsen.ircbridge.mixin.MooshroomEntityInvoker
import net.minecraft.component.ComponentType
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.passive.AxolotlEntity
import net.minecraft.entity.passive.FoxEntity
import net.minecraft.entity.passive.HorseColor
import net.minecraft.entity.passive.HorseEntity
import net.minecraft.entity.passive.HorseMarking
import net.minecraft.entity.passive.LlamaEntity
import net.minecraft.entity.passive.MooshroomEntity
import net.minecraft.entity.passive.PandaEntity
import net.minecraft.entity.passive.ParrotEntity
import net.minecraft.entity.passive.RabbitEntity
import net.minecraft.entity.passive.TropicalFishEntity
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.util.DyeColor
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.util.Locale

object MobModelVariantCatalog {
	private val LOGGER = LoggerFactory.getLogger("xclipsen_mob_model_variants")
	const val DEFAULT_OPTION = "default"

	private val dyeColors: Map<String, DyeColor> = enumValues<DyeColor>().associateBy { it.asString() }
	private val horseColors: Map<String, HorseColor> = enumValues<HorseColor>().associateBy { it.asString() }
	private val horseMarkings: Map<String, HorseMarking> = enumValues<HorseMarking>().associateBy { it.name.lowercase(Locale.ROOT) }
	private val axolotlVariants: Map<String, AxolotlEntity.Variant> = enumValues<AxolotlEntity.Variant>().associateBy { it.asString() }
	private val foxVariants: Map<String, FoxEntity.Variant> = enumValues<FoxEntity.Variant>().associateBy { it.asString() }
	private val llamaVariants: Map<String, LlamaEntity.Variant> = enumValues<LlamaEntity.Variant>().associateBy { it.asString() }
	private val rabbitVariants: Map<String, RabbitEntity.Variant> = enumValues<RabbitEntity.Variant>().associateBy { it.asString() }
	private val parrotVariants: Map<String, ParrotEntity.Variant> = enumValues<ParrotEntity.Variant>().associateBy { it.asString() }
	private val mooshroomVariants: Map<String, MooshroomEntity.Variant> = enumValues<MooshroomEntity.Variant>().associateBy { it.asString() }
	private val pandaGenes: Map<String, PandaEntity.Gene> = enumValues<PandaEntity.Gene>().associateBy { it.asString() }
	private val tropicalFishPatterns: Map<String, TropicalFishEntity.Pattern> = enumValues<TropicalFishEntity.Pattern>().associateBy { it.asString() }

	private val handlers: Map<String, VariantHandler> = linkedMapOf(
		"minecraft:cat" to registryEntryHandler(
			ids = listOf("tabby", "black", "red", "siamese", "british_shorthair", "calico", "persian", "ragdoll", "white", "jellie", "all_black"),
			lookup = { entity, variantId -> registryEntry(entity, RegistryKeys.CAT_VARIANT, variantId) },
			apply = { entity, entry -> applyComponent(entity, DataComponentTypes.CAT_VARIANT, entry) },
		),
		"minecraft:frog" to registryEntryHandler(
			ids = listOf("temperate", "warm", "cold"),
			lookup = { entity, variantId -> registryEntry(entity, RegistryKeys.FROG_VARIANT, variantId) },
			apply = { entity, entry -> applyComponent(entity, DataComponentTypes.FROG_VARIANT, entry) },
		),
		"minecraft:wolf" to registryEntryHandler(
			ids = listOf("pale", "spotted", "snowy", "black", "ashen", "rusty", "woods", "chestnut", "striped"),
			lookup = { entity, variantId -> registryEntry(entity, RegistryKeys.WOLF_VARIANT, variantId) },
			apply = { entity, entry -> applyComponent(entity, DataComponentTypes.WOLF_VARIANT, entry) },
		),
		"minecraft:axolotl" to enumHandler(axolotlVariants) { entity, variant ->
			applyComponent(entity, DataComponentTypes.AXOLOTL_VARIANT, variant)
		},
		"minecraft:fox" to enumHandler(foxVariants) { entity, variant ->
			(entity as FoxEntityInvoker).`xclipsen$setVariant`(variant)
		},
		"minecraft:llama" to enumHandler(llamaVariants) { entity, variant ->
			applyComponent(entity, DataComponentTypes.LLAMA_VARIANT, variant)
		},
		"minecraft:rabbit" to enumHandler(rabbitVariants) { entity, variant ->
			applyComponent(entity, DataComponentTypes.RABBIT_VARIANT, variant)
		},
		"minecraft:parrot" to enumHandler(parrotVariants) { entity, variant ->
			applyComponent(entity, DataComponentTypes.PARROT_VARIANT, variant)
		},
		"minecraft:mooshroom" to enumHandler(mooshroomVariants) { entity, variant ->
			(entity as MooshroomEntityInvoker).`xclipsen$setVariant`(variant)
		},
		"minecraft:sheep" to enumHandler(dyeColors) { entity, variant ->
			applyComponent(entity, DataComponentTypes.SHEEP_COLOR, variant)
		},
		"minecraft:shulker" to enumHandler(dyeColors) { entity, variant ->
			applyComponent(entity, DataComponentTypes.SHULKER_COLOR, variant)
		},
		"minecraft:panda" to enumHandler(pandaGenes) { entity, variant ->
			val panda = entity as PandaEntity
			panda.setMainGene(variant)
			panda.setHiddenGene(variant)
		},
		"minecraft:horse" to horseHandler(),
		"minecraft:tropical_fish" to tropicalFishHandler(),
	)

	fun normalize(raw: String?): String {
		return raw.orEmpty()
			.replace('\r', ' ')
			.replace('\n', ' ')
			.trim()
			.lowercase(Locale.ROOT)
			.take(96)
	}

	fun validate(entityTypeId: String, rawVariant: String): String? {
		val variant = normalize(rawVariant)
		if (variant.isBlank()) {
			return null
		}

		val handler = handlers[entityTypeId]
			?: return "This mob has no configurable variants yet."
		return handler.validate(variant)
	}

	fun helpLine(entityTypeId: String): String {
		return handlers[entityTypeId]?.helpLine ?: "No extra variants for this mob."
	}

	fun options(entityTypeId: String): List<String> {
		val handler = handlers[entityTypeId] ?: return emptyList()
		return listOf(DEFAULT_OPTION) + handler.options
	}

	fun apply(entity: LivingEntity, entityTypeId: String, rawVariant: String) {
		val variant = normalize(rawVariant)
		if (variant.isBlank()) {
			return
		}

		val handler = handlers[entityTypeId] ?: return
		runCatching {
			handler.apply(entity, variant)
		}.onFailure {
			LOGGER.debug("Failed to apply mob variant '{}' to {}", variant, entity.type, it)
		}
	}

	private fun horseHandler(): VariantHandler {
		val help = "color or color:marking (${horseColors.keys.joinToString(", ")} | ${horseMarkings.keys.joinToString(", ")})"
		val options = buildList {
			addAll(horseColors.keys.sorted())
			horseColors.keys.sorted().forEach { color ->
				horseMarkings.keys.sorted().forEach { marking ->
					add("$color:$marking")
				}
			}
		}
		return VariantHandler(
			options = options,
			helpLine = help,
			validate = { raw ->
				val parts = splitParts(raw)
				if (parts.isEmpty() || parts.size > 2) {
					"Use horse variants like white or white:white_dots."
				} else if (!horseColors.containsKey(parts[0])) {
					"Unknown horse color '${parts[0]}'."
				} else if (parts.size == 2 && !horseMarkings.containsKey(parts[1])) {
					"Unknown horse marking '${parts[1]}'."
				} else {
					null
				}
			},
			apply = { entity, raw ->
				val parts = splitParts(raw)
				val horse = entity as? HorseEntity
				val color = horseColors[parts.firstOrNull()]
				if (horse != null && color != null) {
					val marking = horseMarkings[parts.getOrNull(1)]
					if (marking != null) {
						(horse as HorseEntityInvoker).`xclipsen$setHorseVariant`(color, marking)
					} else {
						applyComponent(horse, DataComponentTypes.HORSE_VARIANT, color)
					}
				}
			},
		)
	}

	private fun tropicalFishHandler(): VariantHandler {
		val help = "pattern:base_color:pattern_color"
		val options = buildList {
			tropicalFishPatterns.keys.sorted().forEach { pattern ->
				dyeColors.keys.sorted().forEach { baseColor ->
					dyeColors.keys.sorted().forEach { patternColor ->
						add("$pattern:$baseColor:$patternColor")
					}
				}
			}
		}
		return VariantHandler(
			options = options,
			helpLine = help,
			validate = { raw ->
				val parts = splitParts(raw)
				if (parts.size != 3) {
					"Use tropical fish variants like kob:white:orange."
				} else if (!tropicalFishPatterns.containsKey(parts[0])) {
					"Unknown tropical fish pattern '${parts[0]}'."
				} else if (!dyeColors.containsKey(parts[1])) {
					"Unknown base color '${parts[1]}'."
				} else if (!dyeColors.containsKey(parts[2])) {
					"Unknown pattern color '${parts[2]}'."
				} else {
					null
				}
			},
			apply = { entity, raw ->
				val parts = splitParts(raw)
				val pattern = tropicalFishPatterns[parts.getOrNull(0)]
				val baseColor = dyeColors[parts.getOrNull(1)]
				val patternColor = dyeColors[parts.getOrNull(2)]
				if (pattern != null && baseColor != null && patternColor != null) {
					applyComponent(entity, DataComponentTypes.TROPICAL_FISH_PATTERN, pattern)
					applyComponent(entity, DataComponentTypes.TROPICAL_FISH_BASE_COLOR, baseColor)
					applyComponent(entity, DataComponentTypes.TROPICAL_FISH_PATTERN_COLOR, patternColor)
				}
			},
		)
	}

	private fun <T : Any> enumHandler(options: Map<String, T>, apply: (LivingEntity, T) -> Unit): VariantHandler {
		return VariantHandler(
			options = options.keys.sorted(),
			helpLine = options.keys.joinToString(", "),
			validate = { raw ->
				if (options.containsKey(raw)) null else "Unknown variant '$raw'."
			},
			apply = { entity, raw ->
				options[raw]?.let { variant ->
					apply(entity, variant)
				}
			},
		)
	}

	private fun <T : Any> registryEntryHandler(
		ids: List<String>,
		lookup: (LivingEntity, String) -> RegistryEntry.Reference<T>?,
		apply: (LivingEntity, RegistryEntry.Reference<T>) -> Unit,
	): VariantHandler {
		return VariantHandler(
			options = ids,
			helpLine = ids.joinToString(", "),
			validate = { raw ->
				if (raw in ids) null else "Unknown variant '$raw'."
			},
			apply = { entity, raw ->
				lookup(entity, raw)?.let { entry ->
					apply(entity, entry)
				}
			},
		)
	}

	private fun splitParts(raw: String): List<String> {
		return raw.replace('/', ':')
			.split(':')
			.map { it.trim().replace(' ', '_').replace('-', '_') }
			.filter { it.isNotEmpty() }
	}

	private fun <T : Any> registryEntry(
		entity: LivingEntity,
		registryKey: RegistryKey<Registry<T>>,
		variantId: String,
	): RegistryEntry.Reference<T>? {
		val registry = entity.entityWorld.registryManager.getOrThrow(registryKey)
		return registry.getEntry(Identifier.ofVanilla(variantId)).orElse(null)
	}

	private fun applyComponent(entity: LivingEntity, type: ComponentType<*>, value: Any) {
		(entity as EntityComponentInvoker).`xclipsen$setApplicableComponent`(type, value)
	}

	private data class VariantHandler(
		val options: List<String>,
		val helpLine: String,
		val validate: (String) -> String?,
		val apply: (LivingEntity, String) -> Unit,
	)
}
