package de.xclipsen.ircbridge

import com.autocroesus.config.AcDataStore
import com.autocroesus.price.PriceFetcher
import com.google.gson.JsonParser
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import java.util.Locale
import kotlin.jvm.optionals.getOrNull
import kotlin.math.roundToLong

object AuctionHouseUnderbidFeature {
	private const val CREATE_BIN_AUCTION_TITLE = "Create BIN Auction"
	private const val CREATE_BIN_ITEM_SLOT = 13
	private const val PRICE_REFRESH_INTERVAL_MS = 30L * 60L * 1000L
	private val PET_RARITIES = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

	private var lastCopiedFingerprint: String? = null
	@Volatile private var priceRefreshInFlight = false

	fun onTick(client: MinecraftClient) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		if (!config.auctionHouseModuleEnabled || !config.auctionHouseAutoCopyUnderbidEnabled) {
			lastCopiedFingerprint = null
			return
		}

		val screen = client.currentScreen as? GenericContainerScreen ?: run {
			lastCopiedFingerprint = null
			return
		}
		if (screen.title.string != CREATE_BIN_AUCTION_TITLE) {
			lastCopiedFingerprint = null
			return
		}

		maybeRefreshPrices()

		val stack = screen.screenHandler.slots.getOrNull(CREATE_BIN_ITEM_SLOT)?.stack ?: return
		if (stack.isEmpty) {
			lastCopiedFingerprint = null
			return
		}

		val internalName = resolveInternalName(stack) ?: return
		val unitPrice = (
			AcDataStore.binValues[internalName]
				?: AcDataStore.getSellPrice(internalName, true)
			)?.roundToLong()?.takeIf { it > 0L } ?: return
		val totalPrice = unitPrice * stack.count.toLong()
		if (totalPrice <= 1L) {
			return
		}

		val fingerprint = "$internalName:${stack.count}:$unitPrice"
		if (lastCopiedFingerprint == fingerprint) {
			return
		}

		val underbidPrice = totalPrice - 1L
		client.keyboard.setClipboard(underbidPrice.toString())
		client.player?.sendMessage(
			Text.literal("Copied ${formatCoins(underbidPrice)} to clipboard. (Auto Underbid)"),
			false,
		)
		lastCopiedFingerprint = fingerprint
	}

	private fun maybeRefreshPrices() {
		if (priceRefreshInFlight) {
			return
		}

		val noData = AcDataStore.binValues.isEmpty() || AcDataStore.sbItems.isEmpty()
		val stale = System.currentTimeMillis() - AcDataStore.config.lastApiUpdate > PRICE_REFRESH_INTERVAL_MS
		if (!noData && !stale) {
			return
		}

		priceRefreshInFlight = true
		sendFeedback("Refreshing Auction House price data...")
		PriceFetcher.updatePrices().thenRun {
			AcDataStore.config.lastApiUpdate = System.currentTimeMillis()
			AcDataStore.saveConfig()
			priceRefreshInFlight = false
			sendFeedback("Auction House price data refreshed.")
		}.exceptionally { throwable ->
			priceRefreshInFlight = false
			val message = throwable.cause?.message ?: throwable.message ?: "unknown error"
			sendFeedback("Failed to refresh Auction House price data: $message")
			null
		}
	}

	private fun resolveInternalName(stack: ItemStack): String? {
		val customData = stack.get(DataComponentTypes.CUSTOM_DATA) as? NbtComponent ?: return null
		val nbt = customData.copyNbt()
		val rawId = nbt.getString("id").getOrNull()?.takeUnless { it.isBlank() } ?: return null
		if (rawId != "PET") {
			return rawId
		}

		val petInfoRaw = nbt.getString("petInfo").getOrNull()?.takeUnless { it.isBlank() } ?: return null
		return runCatching {
			val petInfo = JsonParser.parseString(petInfoRaw).asJsonObject
			val petType = petInfo.get("type")?.asString?.uppercase() ?: return null
			val petTier = petInfo.get("tier")?.asString?.uppercase() ?: return null
			val rarityIndex = PET_RARITIES.indexOf(petTier)
			if (rarityIndex < 0) return null
			"$petType;$rarityIndex"
		}.getOrNull()
	}

	private fun sendFeedback(message: String) {
		val client = MinecraftClient.getInstance()
		client.execute {
			client.player?.sendMessage(Text.literal(message), false)
		}
	}

	private fun formatCoins(value: Long): String = String.format(Locale.US, "%,d", value)
}
