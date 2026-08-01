package de.xclipsen.ircbridge

import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.entity.EntityTypeTest
import java.util.Locale

object FrozenCorpseDetector {
	fun isInMineshaftArea(): Boolean {
		return LocationTracker.isOnHypixelSkyBlock && LocationTracker.currentArea.contains("mineshaft", ignoreCase = true)
	}

	fun findNearbyCorpses(client: Minecraft, range: Double = DEFAULT_SCAN_RANGE): List<DetectedFrozenCorpse> {
		val world = client.level ?: return emptyList()
		val player = client.player ?: return emptyList()
		val searchBox = player.boundingBox.inflate(range)

		return world.getEntities(
			EntityTypeTest.forClass(ArmorStand::class.java),
			searchBox,
			::looksLikeCorpseStand,
		).mapNotNull { armorStand ->
			resolveCorpseType(armorStand)?.let { type -> DetectedFrozenCorpse(type, armorStand) }
		}
	}

	fun looksLikeCorpseStand(armorStand: ArmorStand): Boolean {
		if (!armorStand.isAlive || armorStand.isRemoved || armorStand.isInvisible) {
			return false
		}
		if (armorStand.showBasePlate()) {
			return false
		}
		if (!armorStand.showArms()) {
			return false
		}
		return armorStand.customName?.string?.trim().isNullOrEmpty()
	}

	fun resolveCorpseType(armorStand: ArmorStand): FrozenCorpseType? {
		return resolveCorpseType(armorStand.getItemBySlot(EquipmentSlot.HEAD))
	}

	fun resolveCorpseType(stack: ItemStack): FrozenCorpseType? {
		if (stack.isEmpty) {
			return null
		}

		val customData = stack.get(DataComponents.CUSTOM_DATA) as? CustomData ?: return null
		val id = customData.copyTag().getString("id").orElse("").trim()
		if (id.isEmpty()) {
			return null
		}
		return FrozenCorpseType.byInternalId(id)
	}

	data class DetectedFrozenCorpse(
		val type: FrozenCorpseType,
		val armorStand: ArmorStand,
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
