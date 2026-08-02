package de.xclipsen.ircbridge

import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.network.chat.Component
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.jvm.optionals.getOrNull
import kotlin.math.roundToLong

object AuctionHouseUnderbidFeature {
	private const val CREATE_BIN_AUCTION_TITLE = "Create BIN Auction"
	private const val CREATE_BIN_ITEM_SLOT = 13
	private const val PRICE_REFRESH_INTERVAL_MS = 5L * 60L * 1000L
	private const val MAX_LOCAL_STALE_MS = 60L * 60L * 1000L
	private const val FAILURE_RETRY_MS = 30_000L
	private const val MAX_CACHE_ENTRIES = 128
	private val PET_RARITIES = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")
	private val priceExecutor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "xclipsen-auction-prices").apply { isDaemon = true }
	}

	private var lastCopiedFingerprint: String? = null
	@Volatile private var priceRefreshInFlight = false
	@Volatile private var lastPriceRefreshAt = 0L
	private val retryAfterByItem = mutableMapOf<String, Long>()
	@Volatile private var refreshingItemId: String? = null
	@Volatile private var lastRefreshPartial = false
	@Volatile private var lastRefreshStale = false
	@Volatile private var lastRefreshError = ""
	private val priceCache = linkedMapOf<String, CachedPrice>()
	@Volatile private var cacheOrigin = ""
	@Volatile private var refreshFuture: CompletableFuture<*>? = null

	fun onWorldChange() {
		lastCopiedFingerprint = null
		priceRefreshInFlight = false
		refreshFuture?.cancel(true)
		refreshFuture = null
		refreshingItemId = null
	}

	fun shutdown() {
		onWorldChange()
		priceExecutor.shutdownNow()
	}

	fun statusLine(): String {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return "Unavailable"
		return "enabled=${config.auctionHouseModuleEnabled && config.auctionHouseAutoCopyUnderbidEnabled}, " +
			"refreshing=$priceRefreshInFlight, cachedItems=${priceCache.size}, partial=$lastRefreshPartial, " +
			"stale=$lastRefreshStale, lastRefreshAt=$lastPriceRefreshAt, lastError=${lastRefreshError.ifBlank { "none" }}"
	}

	fun onTick(client: Minecraft) {
		val config = XclipsenIrcBridgeClient.instance?.config() ?: return
		val origin = activeModBackendBaseUrl(config)
		if (origin != cacheOrigin) {
			cacheOrigin = origin
			synchronized(priceCache) { priceCache.clear() }
			retryAfterByItem.clear()
			refreshFuture?.cancel(true)
			refreshingItemId = null
			priceRefreshInFlight = false
		}
		if (!config.auctionHouseModuleEnabled || !config.auctionHouseAutoCopyUnderbidEnabled) {
			lastCopiedFingerprint = null
			return
		}

		val screen = client.screen as? ContainerScreen ?: run {
			lastCopiedFingerprint = null
			return
		}
		if (screen.title.string != CREATE_BIN_AUCTION_TITLE) {
			lastCopiedFingerprint = null
			return
		}

		val stack = screen.menu.slots.getOrNull(CREATE_BIN_ITEM_SLOT)?.item ?: return
		if (stack.isEmpty) {
			lastCopiedFingerprint = null
			return
		}

		val internalName = resolveInternalName(stack) ?: return
		maybeRefreshPrice(internalName)
		val cached = synchronized(priceCache) { priceCache[internalName] }
			?.takeIf { System.currentTimeMillis() - it.sourceFetchedAt <= MAX_LOCAL_STALE_MS }
		val unitPrice = (cached?.lowestBin ?: cached?.bazaarSellReference)
			?.roundToLong()
			?.takeIf { it > 0L } ?: return
		if (unitPrice > Long.MAX_VALUE / stack.count.toLong()) return
		val totalPrice = unitPrice * stack.count.toLong()
		if (totalPrice <= 1L) {
			return
		}

		val fingerprint = "$internalName:${stack.count}:$unitPrice"
		if (lastCopiedFingerprint == fingerprint) {
			return
		}

		val underbidPrice = totalPrice - 1L
		client.keyboardHandler.setClipboard(underbidPrice.toString())
		client.player?.sendSystemMessage(Component.literal("Copied ${formatCoins(underbidPrice)} to clipboard. (Auto Underbid)"))
		lastCopiedFingerprint = fingerprint
	}

	private fun maybeRefreshPrice(itemId: String) {
		if (priceRefreshInFlight) {
			return
		}

		val now = System.currentTimeMillis()
		val cached = synchronized(priceCache) { priceCache[itemId] }
		if (cached != null && now - cached.receivedAt <= PRICE_REFRESH_INTERVAL_MS) {
			return
		}
		if (now < (retryAfterByItem[itemId] ?: 0L)) {
			return
		}

		priceRefreshInFlight = true
		refreshingItemId = itemId
		val generation = ClientSessionLifecycle.snapshot()
		val requestOrigin = cacheOrigin
		val backend = XclipsenIrcBridgeClient.instance?.backendBridge() ?: run {
			priceRefreshInFlight = false
			return
		}
		refreshFuture = CompletableFuture.supplyAsync({ backend.fetchAuctionHousePrices(listOf(itemId)) }, priceExecutor)
			.whenComplete { payload, throwable ->
				val client = Minecraft.getInstance()
				client.execute {
					if (!ClientSessionLifecycle.isCurrent(generation) || refreshingItemId != itemId || cacheOrigin != requestOrigin) return@execute
					priceRefreshInFlight = false
					refreshingItemId = null
					val item = payload?.items?.singleOrNull()
					if (throwable != null || item == null || (item.lowestBin == null && item.bazaarSellReference == null)) {
						retryAfterByItem[itemId] = System.currentTimeMillis() + FAILURE_RETRY_MS
						lastRefreshError = "price data unavailable"
						return@execute
					}
					val sourceTimestamp = (if (item.lowestBin != null) payload.sources.lowestBin.fetchedAt else payload.sources.bazaar.fetchedAt)
						?: return@execute
					val cachedPrice = CachedPrice(item.lowestBin, item.bazaarSellReference, sourceTimestamp, System.currentTimeMillis())
					synchronized(priceCache) {
						priceCache[itemId] = cachedPrice
						while (priceCache.size > MAX_CACHE_ENTRIES) priceCache.remove(priceCache.keys.first())
					}
					lastPriceRefreshAt = cachedPrice.receivedAt
					lastRefreshPartial = payload.partial
					lastRefreshStale = payload.sources.lowestBin.stale || payload.sources.bazaar.stale
					lastRefreshError = ""
					retryAfterByItem.remove(itemId)
				}
			}
	}

	private fun resolveInternalName(stack: ItemStack): String? {
		val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return null
		val nbt = customData.copyTag()
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
			val baseId = "$petType;$rarityIndex"
			val level = PET_LEVEL_PATTERN.find(stack.hoverName.string)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
			when {
				level == 200 -> "$baseId+200"
				level == 100 -> "$baseId+100"
				petType == "GOLDEN_DRAGON" && level != null && level >= 100 -> "$baseId+100"
				else -> baseId
			}
		}.getOrNull()
	}

	private fun formatCoins(value: Long): String = String.format(Locale.US, "%,d", value)

	private data class CachedPrice(
		val lowestBin: Double?,
		val bazaarSellReference: Double?,
		val sourceFetchedAt: Long,
		val receivedAt: Long,
	)

	private val PET_LEVEL_PATTERN = Regex("\\[Lvl ([0-9,]+)]")
}
