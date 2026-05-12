package de.xclipsen.ircbridge

import net.minecraft.client.MinecraftClient
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.item.ItemStack
import java.util.Locale
import kotlin.jvm.optionals.getOrNull

object FrozenCorpseDetector {
	fun isInMineshaftArea(): Boolean {
		return LocationTracker.isOnHypixelSkyBlock && LocationTracker.currentArea.contains("mineshaft", ignoreCase = true)
	}

	fun findNearbyCorpses(client: MinecraftClient, range: Double = DEFAULT_SCAN_RANGE): List<DetectedFrozenCorpse> {
		val world = client.world ?: return emptyList()
		val player = client.player ?: return emptyList()
		val searchBox = player.boundingBox.expand(range)

		return world.getEntitiesByClass(
			ArmorStandEntity::class.java,
			searchBox,
			::looksLikeCorpseStand,
		).mapNotNull { armorStand ->
			resolveCorpseType(armorStand)?.let { type -> DetectedFrozenCorpse(type, armorStand) }
		}
	}

	fun looksLikeCorpseStand(armorStand: ArmorStandEntity): Boolean {
		if (!armorStand.isAlive || armorStand.isRemoved || armorStand.isInvisible) {
			return false
		}
		if (armorStand.shouldShowBasePlate()) {
			return false
		}
		if (!armorStand.shouldShowArms()) {
			return false
		}
		return armorStand.customName?.string?.trim().isNullOrEmpty()
	}

	fun resolveCorpseType(armorStand: ArmorStandEntity): FrozenCorpseType? {
		return resolveCorpseType(armorStand.getEquippedStack(EquipmentSlot.HEAD))
	}

	fun resolveCorpseType(stack: ItemStack): FrozenCorpseType? {
		if (stack.isEmpty) {
			return null
		}

		val customData = stack.get(DataComponentTypes.CUSTOM_DATA) as? NbtComponent ?: return null
		val id = customData.copyNbt().getString("id").getOrNull()?.trim().orEmpty()
		if (id.isEmpty()) {
			return null
		}
		return FrozenCorpseType.byInternalId(id)
	}

	data class DetectedFrozenCorpse(
		val type: FrozenCorpseType,
		val armorStand: ArmorStandEntity,
	)

	enum class FrozenCorpseType(
		val shortCode: String,
		val displayName: String,
		val aliases: Set<String>,
		val internalIds: Set<String>,
		val colorRgb: Int,
	) {
		LAPIS(
			shortCode = "L",
			displayName = "Lapis",
			aliases = setOf("L", "LAPIS"),
			internalIds = setOf("LAPIS_ARMOR_HELMET"),
			colorRgb = 0x2563EB,
		),
		TUNGSTEN(
			shortCode = "T",
			displayName = "Tungsten",
			aliases = setOf("T", "TUNGSTEN"),
			internalIds = setOf("MINERAL_HELMET"),
			colorRgb = 0x9CA3AF,
		),
		UMBER(
			shortCode = "U",
			displayName = "Umber",
			aliases = setOf("U", "UMBER"),
			internalIds = setOf("ARMOR_OF_YOG_HELMET", "YOG_HELMET"),
			colorRgb = 0xF97316,
		),
		VANGUARD(
			shortCode = "V",
			displayName = "Vanguard",
			aliases = setOf("V", "VANGUARD"),
			internalIds = setOf("VANGUARD_HELMET"),
			colorRgb = 0x7DD3FC,
		),
		;

		companion object {
			fun byInternalId(id: String): FrozenCorpseType? {
				return entries.firstOrNull { id in it.internalIds }
			}

			fun byAlias(alias: String): FrozenCorpseType? {
				val normalized = alias.uppercase(Locale.ROOT)
				return entries.firstOrNull { normalized in it.aliases }
			}
		}
	}

	private const val DEFAULT_SCAN_RANGE = 196.0
}
