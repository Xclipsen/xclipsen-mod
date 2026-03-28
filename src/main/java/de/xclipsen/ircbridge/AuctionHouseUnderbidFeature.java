package de.xclipsen.ircbridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import de.xclipsen.ircbridge.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AuctionHouseUnderbidFeature {
	private static final Gson GSON = new Gson();
	private static final Pattern AUCTION_PRICE_PATTERN = Pattern.compile("(?:Buy it now|Starting bid|Top bid):\\s*([0-9,]+)\\s+coins", Pattern.CASE_INSENSITIVE);
	private static final Pattern ALLOWED_SCREEN_PATTERN = Pattern.compile("Auctions Browser|Manage Auctions|Auctions: \".*\"?", Pattern.CASE_INSENSITIVE);
	private static final Pattern PET_LEVEL_PATTERN = Pattern.compile("\\[Lvl\\s+(\\d+)]", Pattern.CASE_INSENSITIVE);
	private static final int CREATE_BIN_ITEM_SLOT = 13;

	private final Logger logger;
	private final KeyBinding copyUnderbidKey;
	private final LowestBinService lowestBinService;
	private String lastCreateBinItemKey = "";

	public AuctionHouseUnderbidFeature(Logger logger, KeyBinding copyUnderbidKey) {
		this.logger = logger;
		this.copyUnderbidKey = copyUnderbidKey;
		this.lowestBinService = new LowestBinService(logger);
	}

	public void handleEndTick(MinecraftClient client, BridgeConfig config) {
		lowestBinService.refreshIfNeeded();
		handleCreateBinAutoCopy(client, config);

		while (copyUnderbidKey.wasPressed()) {
			copyHoveredUnderbid(client, config);
		}
	}

	public void stop() {
		lowestBinService.stop();
	}

	private void copyHoveredUnderbid(MinecraftClient client, BridgeConfig config) {
		if (client == null || client.player == null || client.currentScreen == null) {
			return;
		}

		if (!config.auctionHouseUnderbidEnabled) {
			showMessage(client, Text.literal("[AH] Underbid copy is disabled in config.").formatted(Formatting.RED));
			return;
		}

		if (!(client.currentScreen instanceof HandledScreen<?> handledScreen)) {
			return;
		}

		String title = handledScreen.getTitle().getString();
		if (!ALLOWED_SCREEN_PATTERN.matcher(title).matches()) {
			return;
		}

		Slot hoveredSlot = ((HandledScreenAccessor) handledScreen).xclipsenIrcBridge$getFocusedSlot();
		if (hoveredSlot == null || !hoveredSlot.hasStack()) {
			showMessage(client, Text.literal("[AH] Hover an auction item first.").formatted(Formatting.RED));
			return;
		}

		Long price = extractAuctionPrice(client, hoveredSlot.getStack());
		if (price == null || price <= 0L) {
			showMessage(client, Text.literal("[AH] No auction price found on the hovered item.").formatted(Formatting.RED));
			return;
		}

		long underbidPrice = Math.max(0L, price - 1L);
		client.keyboard.setClipboard(Long.toString(underbidPrice));
		showMessage(client, createSuccessMessage(underbidPrice));
	}

	private void handleCreateBinAutoCopy(MinecraftClient client, BridgeConfig config) {
		if (client == null || client.player == null || client.currentScreen == null) {
			lastCreateBinItemKey = "";
			return;
		}

		if (!config.auctionHouseCreateBinAutoCopyEnabled) {
			lastCreateBinItemKey = "";
			return;
		}

		if (!(client.currentScreen instanceof HandledScreen<?> handledScreen)) {
			lastCreateBinItemKey = "";
			return;
		}

		if (!"Create BIN Auction".equals(handledScreen.getTitle().getString())) {
			lastCreateBinItemKey = "";
			return;
		}

		if (!(handledScreen.getScreenHandler() instanceof GenericContainerScreenHandler genericHandler)) {
			return;
		}

		if (genericHandler.getInventory().size() <= CREATE_BIN_ITEM_SLOT) {
			return;
		}

		ItemStack stack = genericHandler.getInventory().getStack(CREATE_BIN_ITEM_SLOT);
		if (stack.isEmpty()) {
			lastCreateBinItemKey = "";
			return;
		}

		String internalName = extractInternalName(stack);
		if (internalName.isBlank()) {
			return;
		}

		String dedupeKey = internalName + "#" + stack.getCount();
		if (dedupeKey.equals(lastCreateBinItemKey)) {
			return;
		}

		long lowestBin = resolveLowestBin(stack, internalName);
		if (lowestBin <= 0L) {
			return;
		}

		lastCreateBinItemKey = dedupeKey;
		long underbidPrice = Math.max(0L, lowestBin * stack.getCount() - 1L);
		client.keyboard.setClipboard(Long.toString(underbidPrice));
		showMessage(client, Text.literal("")
			.append(Text.literal("[AH] ").formatted(Formatting.GREEN))
			.append(Text.literal("Auto copied Lowest BIN - 1: "))
			.append(Text.literal(formatNumber(underbidPrice)).formatted(Formatting.GOLD)));
	}

	private static Long extractAuctionPrice(MinecraftClient client, ItemStack stack) {
		List<Text> tooltip = stack.getTooltip(Item.TooltipContext.create(client.world), client.player, TooltipType.BASIC);
		for (Text line : tooltip) {
			Matcher matcher = AUCTION_PRICE_PATTERN.matcher(line.getString());
			if (!matcher.find()) {
				continue;
			}

			String digits = matcher.group(1).replace(",", "");
			try {
				return Long.parseLong(digits);
			} catch (NumberFormatException ignored) {
				return null;
			}
		}

		return null;
	}

	private long resolveLowestBin(ItemStack stack, String internalName) {
		long direct = lowestBinService.getLowestBin(internalName);
		if (direct > 0L) {
			return direct;
		}

		String leveledPetName = extractLeveledPetInternalName(stack, internalName);
		if (!leveledPetName.equals(internalName)) {
			long petPrice = lowestBinService.getLowestBin(leveledPetName);
			if (petPrice > 0L) {
				return petPrice;
			}
		}

		return -1L;
	}

	private static String extractInternalName(ItemStack stack) {
		NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (customData == null) {
			return "";
		}

		var customNbt = customData.copyNbt();
		String itemId = customNbt.getString("id", "");
		if (itemId.isBlank()) {
			return "";
		}

		if (!"PET".equalsIgnoreCase(itemId)) {
			return itemId.toUpperCase(Locale.ROOT).replace(':', '-');
		}

		String petInfo = customNbt.getString("petInfo", "");
		if (petInfo.isBlank()) {
			return "PET";
		}

		try {
			JsonObject petInfoObject = GSON.fromJson(petInfo, JsonObject.class);
			if (petInfoObject == null) {
				return "PET";
			}

			String petType = petInfoObject.has("type") ? petInfoObject.get("type").getAsString() : "";
			String petTier = petInfoObject.has("tier") ? petInfoObject.get("tier").getAsString() : "";
			int rarityIndex = switch (petTier.toUpperCase(Locale.ROOT)) {
				case "COMMON" -> 0;
				case "UNCOMMON" -> 1;
				case "RARE" -> 2;
				case "EPIC" -> 3;
				case "LEGENDARY" -> 4;
				case "MYTHIC" -> 5;
				case "DIVINE" -> 6;
				default -> -1;
			};

			if (petType.isBlank() || rarityIndex < 0) {
				return "PET";
			}

			return petType.toUpperCase(Locale.ROOT) + ";" + rarityIndex;
		} catch (Exception exception) {
			return "PET";
		}
	}

	private static String extractLeveledPetInternalName(ItemStack stack, String internalName) {
		if (!internalName.contains(";")) {
			return internalName;
		}

		Matcher matcher = PET_LEVEL_PATTERN.matcher(stack.getName().getString());
		if (!matcher.find()) {
			return internalName;
		}

		return internalName + "+" + matcher.group(1);
	}

	private static MutableText createSuccessMessage(long underbidPrice) {
		return Text.literal("")
			.append(Text.literal("[AH] ").formatted(Formatting.GREEN))
			.append(Text.literal("Copied "))
			.append(Text.literal(formatNumber(underbidPrice)).formatted(Formatting.GOLD))
			.append(Text.literal(" to clipboard."));
	}

	private static void showMessage(MinecraftClient client, Text message) {
		if (client.player != null) {
			client.player.sendMessage(message, false);
		}
	}

	private static String formatNumber(long value) {
		return String.format(java.util.Locale.US, "%,d", value);
	}
}
