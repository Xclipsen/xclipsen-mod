package de.xclipsen.ircbridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AutoExperimentsFeature {
	private static final int CENTER_SLOT = 49;
	private static final Pattern BONUS_CLICKS_PATTERN = Pattern.compile("Series of \\d+ \\+(\\d+) Clicks?");

	private ExperimentHandler handler;
	private String lastTitle = "";
	private long lastClickAt;
	private int detectedSerumCount = -1;
	private int configuredSerumCount;

	public void handleEndTick(MinecraftClient client, BridgeConfig config) {
		if (client == null || client.player == null || client.interactionManager == null) {
			handler = null;
			lastTitle = "";
			return;
		}

		if (!config.autoExperimentsEnabled) {
			handler = null;
			lastTitle = "";
			return;
		}

		configuredSerumCount = config.autoExperimentsSerumCount;

		if (!(client.currentScreen instanceof HandledScreen<?> handledScreen)) {
			handler = null;
			lastTitle = "";
			return;
		}

		Integer serumCountFromLore = detectSerumCount(handledScreen.getScreenHandler());
		if (serumCountFromLore != null) {
			detectedSerumCount = serumCountFromLore;
		}

		String title = handledScreen.getTitle().getString();
		if (!title.startsWith("Chronomatron (") && !title.startsWith("Ultrasequencer (")) {
			handler = null;
			lastTitle = "";
			return;
		}

		if (!title.equals(lastTitle) || handler == null) {
			lastTitle = title;
			handler = title.startsWith("Chronomatron (")
				? new ChronomatronHandler(this::effectiveSerumCount)
				: new UltrasequencerHandler(this::effectiveSerumCount);
			lastClickAt = 0L;
		}

		handler.update(handledScreen.getScreenHandler(), config);

		long now = System.currentTimeMillis();
		if (now - lastClickAt < nextDelay(config)) {
			return;
		}

		Integer slotId = handler.nextClick();
		if (slotId != null) {
			clickSlot(client, handledScreen.getScreenHandler(), slotId);
			lastClickAt = now;
			return;
		}

		if (handler.shouldClose(config) && client.player != null) {
			client.player.closeHandledScreen();
			handler = null;
			lastTitle = "";
		}
	}

	private void clickSlot(MinecraftClient client, ScreenHandler screenHandler, int slotId) {
		ClientPlayerInteractionManager interactionManager = client.interactionManager;
		if (interactionManager == null || client.player == null) {
			return;
		}

		interactionManager.clickSlot(screenHandler.syncId, slotId, 0, SlotActionType.PICKUP, client.player);
	}

	private static long nextDelay(BridgeConfig config) {
		long variance = Math.max(0L, config.autoExperimentsDelayVarianceMs);
		long base = Math.max(0L, config.autoExperimentsClickDelayMs);
		if (variance == 0L) {
			return base;
		}

		return base + ThreadLocalRandom.current().nextLong(variance + 1L);
	}

	private int effectiveSerumCount() {
		return detectedSerumCount >= 0 ? detectedSerumCount : configuredSerumCount;
	}

	private static Integer detectSerumCount(ScreenHandler screenHandler) {
		for (Slot slot : screenHandler.slots) {
			if (!slot.hasStack()) {
				continue;
			}

			Integer serumCount = detectSerumCount(slot.getStack());
			if (serumCount != null) {
				return serumCount;
			}
		}

		return null;
	}

	private static Integer detectSerumCount(ItemStack stack) {
		LoreComponent lore = stack.get(DataComponentTypes.LORE);
		if (lore == null) {
			return null;
		}

		boolean experimentLore = false;
		int serumCount = 0;

		for (Text line : lore.lines()) {
			String stripped = stripFormatting(line.getString()).trim();
			if (stripped.contains("Ultrasequencer") || stripped.contains("Chronomatron")) {
				experimentLore = true;
			}

			Matcher matcher = BONUS_CLICKS_PATTERN.matcher(stripped);
			if (matcher.find()) {
				serumCount += Integer.parseInt(matcher.group(1));
			}
		}

		if (!experimentLore || serumCount <= 0) {
			return null;
		}

		return Math.max(0, Math.min(3, serumCount));
	}

	private abstract static class ExperimentHandler {
		protected int clicks;
		protected boolean hasData;

		abstract void update(ScreenHandler screenHandler, BridgeConfig config);

		abstract Integer nextClick();

		abstract boolean shouldClose(BridgeConfig config);
	}

	private static final class ChronomatronHandler extends ExperimentHandler {
		private final IntSupplier serumCountSupplier;
		private final List<Integer> order = new ArrayList<>();
		private int lastAddedSlotId = -1;
		private boolean close;

		private ChronomatronHandler(IntSupplier serumCountSupplier) {
			this.serumCountSupplier = serumCountSupplier;
		}

		@Override
		void update(ScreenHandler screenHandler, BridgeConfig config) {
			List<Slot> slots = screenHandler.slots;
			if (slots.size() <= CENTER_SLOT) {
				return;
			}

			ItemStack center = slots.get(CENTER_SLOT).getStack();

			if (lastAddedSlotId != -1
				&& center.isOf(Items.GLOWSTONE)
				&& slotById(slots, lastAddedSlotId) != null
				&& !slotById(slots, lastAddedSlotId).getStack().hasGlint()) {
				close = order.size() > (config.autoExperimentsGetMaxXp ? 15 : 11 - serumCountSupplier.getAsInt());
				hasData = false;
				return;
			}

			if (hasData || !center.isOf(Items.CLOCK)) {
				return;
			}

			for (Slot slot : slots) {
				int index = slot.getIndex();
				if (index >= 10 && index <= 43 && slot.hasStack() && slot.getStack().hasGlint()) {
					order.add(slot.id);
					lastAddedSlotId = slot.id;
					hasData = true;
					clicks = 0;
					return;
				}
			}
		}

		@Override
		Integer nextClick() {
			return hasData && clicks < order.size() ? order.get(clicks++) : null;
		}

		@Override
		boolean shouldClose(BridgeConfig config) {
			if (!config.autoExperimentsAutoClose || !close) {
				return false;
			}

			if (clicks < order.size()) {
				return false;
			}

			close = false;
			return true;
		}
	}

	private static final class UltrasequencerHandler extends ExperimentHandler {
		private final IntSupplier serumCountSupplier;
		private final List<Integer> orderedSlots = new ArrayList<>();
		private Phase phase = Phase.REMEMBER;

		private UltrasequencerHandler(IntSupplier serumCountSupplier) {
			this.serumCountSupplier = serumCountSupplier;
		}

		@Override
		void update(ScreenHandler screenHandler, BridgeConfig config) {
			List<Slot> slots = screenHandler.slots;
			if (slots.size() <= CENTER_SLOT) {
				return;
			}

			String centerName = stripFormatting(slots.get(CENTER_SLOT).getStack().getName().getString()).trim();
			if (centerName.equals("Remember the pattern!")) {
				readRememberPhase(slots);
				phase = Phase.WAIT;
				return;
			}

			if (centerName.startsWith("Timer: ")) {
				phase = hasData && !orderedSlots.isEmpty() ? Phase.SHOW : Phase.REMEMBER;
				return;
			}

			phase = Phase.REMEMBER;
			hasData = false;
			orderedSlots.clear();
			clicks = 0;
		}

		@Override
		Integer nextClick() {
			if (!hasData || phase != Phase.SHOW || clicks >= orderedSlots.size()) {
				return null;
			}

			return orderedSlots.get(clicks++);
		}

		@Override
		boolean shouldClose(BridgeConfig config) {
			return config.autoExperimentsAutoClose
				&& phase == Phase.SHOW
				&& clicks >= orderedSlots.size()
				&& orderedSlots.size() > (config.autoExperimentsGetMaxXp ? 20 : 9 - serumCountSupplier.getAsInt());
		}

		private void readRememberPhase(List<Slot> slots) {
			List<OrderedSlot> foundSlots = new ArrayList<>();
			clicks = 0;
			hasData = false;

			for (Slot slot : slots) {
				int index = slot.getIndex();
				if (index < 9 || index > 44 || !slot.hasStack()) {
					continue;
				}

				int orderNumber = parseOrderNumber(slot.getStack());
				if (orderNumber <= 0) {
					continue;
				}

				foundSlots.add(new OrderedSlot(orderNumber - 1, slot.id));
			}

			foundSlots.sort((left, right) -> Integer.compare(left.order(), right.order()));
			orderedSlots.clear();
			for (OrderedSlot entry : foundSlots) {
				orderedSlots.add(entry.slotId());
			}

			hasData = !orderedSlots.isEmpty();
		}

		private static int parseOrderNumber(ItemStack stack) {
			String strippedName = stripFormatting(stack.getName().getString()).trim();
			if (strippedName.matches("\\d+")) {
				return Integer.parseInt(strippedName);
			}

			return 0;
		}

		private enum Phase {
			REMEMBER,
			WAIT,
			SHOW
		}

		private record OrderedSlot(int order, int slotId) {
		}
	}

	private static Slot slotById(List<Slot> slots, int slotId) {
		for (Slot slot : slots) {
			if (slot.id == slotId) {
				return slot;
			}
		}

		return null;
	}

	private static String stripFormatting(String value) {
		StringBuilder builder = new StringBuilder(value.length());
		boolean skip = false;

		for (int index = 0; index < value.length(); index++) {
			char current = value.charAt(index);
			if (skip) {
				skip = false;
				continue;
			}

			if (current == '\u00A7') {
				skip = true;
				continue;
			}

			builder.append(current);
		}

		return builder.toString();
	}
}
