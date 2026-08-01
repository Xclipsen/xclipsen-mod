/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.player.AbstractClientPlayer
 *  net.minecraft.client.player.LocalPlayer
 *  com.mojang.blaze3d.platform.InputConstants
 *  com.mojang.blaze3d.platform.Window
 *  net.minecraft.client.multiplayer.ClientLevel
 *  net.minecraft.core.component.DataComponents
 *  net.minecraft.world.item.component.ItemLore
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.decoration.ArmorStand
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.Items
 *  net.minecraft.world.inventory.AbstractContainerMenu
 *  net.minecraft.world.inventory.Slot
 *  net.minecraft.world.inventory.ClickType
 *  net.minecraft.network.chat.Component
 *  net.minecraft.world.InteractionHand
 *  net.minecraft.core.NonNullList
 *  net.minecraft.world.phys.AABB
 */
package com.autocroesus.feature;

import com.autocroesus.XclipsenAutoCroesusModule;
import com.autocroesus.config.AcDataStore;
import com.autocroesus.util.ChatUtil;
import com.autocroesus.util.ColorUtil;
import com.autocroesus.util.ItemParser;
import com.autocroesus.util.LootLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.NonNullList;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.level.entity.EntityTypeTest;

public class CroesusClaimer {
    public static boolean autoClaiming = false;
    private static final List<Integer> failedIndexes = new ArrayList<Integer>();
    private static final List<Integer> loggedIndexes = new ArrayList<Integer>();
    private static String claimFloor = null;
    private static int claimPage = 0;
    private static int claimRunSlot = -1;
    private static int claimChestSlot = -1;
    private static boolean claimSkipKismet = false;
    private static boolean waitingForCroesus = false;
    private static boolean waitingForRunToOpen = false;
    private static boolean waitingForChestToOpen = false;
    private static int lastPageOn = -1;
    private static int waitingOnPage = -1;
    private static long pageChangedAt = 0L;
    private static boolean prevWasInCroesus = false;
    private static long croesusEnteredAt = 0L;
    private static boolean tryingToKismet = false;
    private static boolean canKismet = true;
    private static boolean anyRunsClaimed = false;
    private static boolean needsToLeaveRunGui = false;
    private static final Set<String> skippedKismetFloors = new LinkedHashSet<String>();
    private static long lastClick = 0L;
    private static int indexToClick = -1;
    private static long waitFlagSetAt = 0L;
    private static String prevScreenTitle = "";
    private static final int[] CHEST_SLOTS = new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final Pattern CHEST_NAME_PATTERN = Pattern.compile("^(Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)$");
    private static final Pattern CHEST_SCREEN_PATTERN = Pattern.compile("^(Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?$");
    private static final Pattern RUN_GUI_PATTERN = Pattern.compile("^(?:Master )?Catacombs - .+$");
    private static final Pattern PAGE_NUM_PATTERN = Pattern.compile("Page (\\d+)");
    private static final Pattern FLOOR_PATTERN = Pattern.compile("Floor (\\w+)");
    private static final Map<String, String> CHEST_COLORS = new LinkedHashMap<String, String>();

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(CroesusClaimer::onTick);
    }

    private static void onTick(Minecraft mc) {
        if (!XclipsenAutoCroesusModule.isEnabled()) {
            if (autoClaiming) {
                CroesusClaimer.reset();
            }
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        CroesusClaimer.tickKillSwitch(mc, player);
        CroesusClaimer.tickExecuteClick(mc, player);
        CroesusClaimer.tickStartClaiming(mc, player);
        CroesusClaimer.tickCroesusMenu(mc, player);
        CroesusClaimer.tickRunGui(mc, player);
        CroesusClaimer.tickChestScreen(mc, player);
    }

    private static void tickKillSwitch(Minecraft mc, LocalPlayer player) {
        String title;
        if (!autoClaiming) {
            return;
        }
        if (InputConstants.isKeyDown((Window)mc.getWindow(), 340) || InputConstants.isKeyDown((Window)mc.getWindow(), 344) || InputConstants.isKeyDown((Window)mc.getWindow(), 256)) {
            CroesusClaimer.reset();
            ChatUtil.msg("Kill switch activated!");
            return;
        }
        String string = title = mc.screen != null ? CroesusClaimer.getScreenTitle(mc) : "";
        if (title.isEmpty() && !prevScreenTitle.isEmpty()) {
            boolean prevWasOurs;
            boolean bl = prevWasOurs = prevScreenTitle.equals("Croesus") || RUN_GUI_PATTERN.matcher(prevScreenTitle).matches();
            if (prevWasOurs && !waitingForRunToOpen && !waitingForChestToOpen) {
                CroesusClaimer.reset();
                ChatUtil.msg("Kill switch activated!");
            }
        }
        prevScreenTitle = title;
    }

    private static void tickExecuteClick(Minecraft mc, LocalPlayer player) {
        if (indexToClick < 0) {
            return;
        }
        if (System.currentTimeMillis() - lastClick < (long)AcDataStore.config.minClickDelay) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.getItems().size() <= indexToClick) {
            return;
        }
        lastClick = System.currentTimeMillis();
        if (AcDataStore.config.noClick) {
            ChatUtil.msg("\u00a7eClick \u00a7f" + indexToClick);
            indexToClick = -1;
            return;
        }
        if (mc.gameMode != null) {
            mc.gameMode.handleContainerInput(menu.containerId, indexToClick, 1, ContainerInput.PICKUP, player);
        }
        indexToClick = -1;
    }

    private static void tickStartClaiming(Minecraft mc, LocalPlayer player) {
        if (!autoClaiming || waitingForCroesus) {
            return;
        }
        if (player.containerMenu != player.inventoryMenu) {
            return;
        }
        if (waitingOnPage >= 0) {
            return;
        }
        if (waitingForRunToOpen || waitingForChestToOpen) {
            if (System.currentTimeMillis() - waitFlagSetAt < 3000L) {
                return;
            }
            ChatUtil.msg("\u00a7c[Error 103] \u00a7fSequence out of sync, stopping. \u00a77DM 22yrs on Discord");
            CroesusClaimer.reset();
            return;
        }
        CroesusClaimer.startClaiming(mc, player);
    }

    private static void tickCroesusMenu(Minecraft mc, LocalPlayer player) {
        boolean nowInCroesus = CroesusClaimer.inCroesus(mc);
        if (nowInCroesus && !prevWasInCroesus) {
            croesusEnteredAt = System.currentTimeMillis();
        }
        prevWasInCroesus = nowInCroesus;
        if (!nowInCroesus) {
            return;
        }
        if (!autoClaiming || waitingForRunToOpen) {
            return;
        }
        waitingForCroesus = false;
        if (System.currentTimeMillis() - croesusEnteredAt < 300L) {
            return;
        }
        if (claimRunSlot < 0) {
            CroesusClaimer.resetClaimInfo();
        }
        AbstractContainerMenu menu = player.containerMenu;
        int page = CroesusClaimer.getCurrPage(menu);
        if (waitingOnPage >= 0) {
            if (page != waitingOnPage) {
                if (System.currentTimeMillis() - pageChangedAt > 5000L) {
                    ChatUtil.msg("\u00a7c[Error 110] \u00a7fTimed out waiting for page " + waitingOnPage + " (stuck on page " + page + "). \u00a77DM 22yrs on Discord");
                    CroesusClaimer.reset();
                }
                return;
            }
            waitingOnPage = -1;
        }
        if (claimRunSlot >= 0) {
            if (page != claimPage) {
                if (lastPageOn == page) {
                    if (System.currentTimeMillis() - pageChangedAt > 5000L) {
                        ChatUtil.msg("\u00a7c[Error 111] \u00a7fTimed out navigating to page " + claimPage + " (stuck on page " + page + "). \u00a77DM 22yrs on Discord");
                        CroesusClaimer.reset();
                    }
                    return;
                }
                lastPageOn = page;
                indexToClick = 53;
                pageChangedAt = System.currentTimeMillis();
                return;
            }
            lastPageOn = -1;
            indexToClick = claimRunSlot;
            waitingForRunToOpen = true;
            waitFlagSetAt = System.currentTimeMillis();
            return;
        }
        int[] slotAndFloor = CroesusClaimer.findUnopenedChest(menu, page);
        if (slotAndFloor != null) {
            int slot = slotAndFloor[0];
            int floorNum = slotAndFloor[1];
            boolean isMaster = slotAndFloor[2] == 1;
            claimFloor = (isMaster ? "M" : "F") + floorNum;
            claimPage = page;
            claimRunSlot = slot;
            claimChestSlot = -1;
            claimSkipKismet = false;
            waitingForRunToOpen = true;
            waitFlagSetAt = System.currentTimeMillis();
            indexToClick = slot;
            return;
        }
        ItemStack nextArrow = CroesusClaimer.getSlot(menu, 53);
        if (!nextArrow.isEmpty() && ColorUtil.stripColors(nextArrow.getHoverName().getString()).contains("Next Page")) {
            if (lastPageOn == page) {
                return;
            }
            lastPageOn = page;
            indexToClick = 53;
            waitingOnPage = page + 1;
            pageChangedAt = System.currentTimeMillis();
            return;
        }
        if (!canKismet) {
            StringBuilder fb = new StringBuilder();
            for (String f : skippedKismetFloors) {
                if (fb.length() > 0) {
                    fb.append("\u00a7f, ");
                }
                fb.append(CroesusClaimer.fmtFloor(f));
            }
            String floors = fb.toString();
            if (anyRunsClaimed) {
                ChatUtil.msg("\u00a7eSome chests looted! \u00a7fKismets needed for: " + floors);
            } else {
                ChatUtil.msg("\u00a7eCould not find kismets. \u00a7fAuto claiming for " + floors + " \u00a7fis disabled until kismetting is turned off or kismets are available to use.");
            }
        } else {
            ChatUtil.msg("\u00a7aAll chests looted!");
        }
        CroesusClaimer.reset();
        if (mc.screen != null) {
            mc.screen.onClose();
        }
    }

    private static void tickRunGui(Minecraft mc, LocalPlayer player) {
        int runIndex;
        boolean hasAlwaysBuyItem;
        if (!autoClaiming && claimRunSlot < 0) {
            CroesusClaimer.resetClaimInfo();
            return;
        }
        if (!CroesusClaimer.inRunGui(mc)) {
            if (claimRunSlot < 0) {
                CroesusClaimer.resetClaimInfo();
            }
            return;
        }
        if (!CroesusClaimer.isInvLoaded(mc, player)) {
            return;
        }
        if (waitingForChestToOpen) {
            return;
        }
        waitingForRunToOpen = false;
        lastPageOn = -1;
        waitingOnPage = -1;
        if (claimChestSlot >= 0) {
            waitingForChestToOpen = true;
            waitFlagSetAt = System.currentTimeMillis();
            indexToClick = claimChestSlot;
            claimChestSlot = -1;
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        ArrayList<ItemParser.ChestInfo> chestData = new ArrayList<ItemParser.ChestInfo>();
        int totalChestCount = 0;
        for (int i = 0; i < 27; ++i) {
            String itemName;
            Matcher m;
            ItemStack stack = CroesusClaimer.getSlot(menu, i);
            if (stack.isEmpty() || !(m = CHEST_NAME_PATTERN.matcher(itemName = ColorUtil.stripColors(stack.getHoverName().getString()))).matches()) continue;
            ++totalChestCount;
            String chestName = m.group(1);
            List<String> tooltip = CroesusClaimer.getTooltip(stack);
            int costIdx = -1;
            for (int j = 0; j < tooltip.size(); ++j) {
                if (!ColorUtil.stripColors(tooltip.get(j)).contains("Cost")) continue;
                costIdx = j;
                break;
            }
            if (costIdx < 0) {
                if (autoClaiming) {
                    ChatUtil.msg("\u00a7c[Error 104] \u00a7fCould not find loot end. \u00a77DM 22yrs on Discord");
                    failedIndexes.add(claimRunSlot + (claimPage - 1) * 54);
                    CroesusClaimer.resetClaimInfo();
                    indexToClick = 30;
                }
                return;
            }
            String[] errorOut = new String[]{null};
            ItemParser.ChestInfo info = ItemParser.parseRewards(tooltip, errorOut);
            if (info == null) {
                if (autoClaiming) {
                    String chestColor = CHEST_COLORS.getOrDefault(chestName, "\u00a7f");
                    ChatUtil.msg("\u00a7c[Error 105] \u00a7fFailed to check " + chestColor + chestName + "\u00a7r Chest: \u00a7r" + errorOut[0] + " \u00a77DM 22yrs on Discord");
                    ChatUtil.msg("\u00a7eThis run will be skipped as the info for this chest is incomplete.");
                    failedIndexes.add(claimRunSlot + (claimPage - 1) * 54);
                    CroesusClaimer.resetClaimInfo();
                    indexToClick = 30;
                }
                return;
            }
            info.slot = i;
            info.chestName = chestName;
            info.chestColor = CHEST_COLORS.getOrDefault(chestName, "\u00a7f");
            chestData.add(info);
        }
        CroesusClaimer.sortChestData(chestData);
        if (!autoClaiming || claimRunSlot < 0) {
            if (needsToLeaveRunGui && CroesusClaimer.inRunGui(mc)) {
                needsToLeaveRunGui = false;
                indexToClick = 30;
            }
            return;
        }
        if (chestData.isEmpty()) {
            ChatUtil.msg("\u00a7c[Error 106] \u00a7fNo chest data found, skipping run. \u00a77DM 22yrs on Discord");
            failedIndexes.add(claimRunSlot + (claimPage - 1) * 54);
            CroesusClaimer.resetClaimInfo();
            indexToClick = 30;
            return;
        }
        ItemParser.ChestInfo bedrockChest = chestData.stream().filter(c -> "Bedrock".equals(c.chestName)).findFirst().orElse(null);
        boolean bl = hasAlwaysBuyItem = bedrockChest != null && bedrockChest.items.stream().anyMatch(item -> AcDataStore.alwaysBuy.contains(item.id));
        if (!hasAlwaysBuyItem && !claimSkipKismet && canKismet && AcDataStore.config.useKismets && bedrockChest != null && AcDataStore.config.kismetFloors.contains(claimFloor) && bedrockChest.profit < AcDataStore.config.kismetMinProfit) {
            tryingToKismet = true;
            indexToClick = bedrockChest.slot;
            waitingForChestToOpen = true;
            waitFlagSetAt = System.currentTimeMillis();
            return;
        }
        ItemParser.ChestInfo bestChest = (ItemParser.ChestInfo)chestData.getFirst();
        ChatUtil.msg("Claiming the " + bestChest.chestColor + bestChest.chestName + " Chest");
        ArrayList<ItemParser.ChestInfo> chestsToClaim = new ArrayList<ItemParser.ChestInfo>();
        chestsToClaim.add(bestChest);
        if (chestData.size() > 1) {
            ItemParser.ChestInfo second = (ItemParser.ChestInfo)chestData.get(1);
            if (AcDataStore.config.useChestKeys && second.profit >= AcDataStore.config.chestKeyMinProfit) {
                ChatUtil.msg("Using chest key on the " + second.chestColor + second.chestName + " Chest");
                claimChestSlot = second.slot;
                chestsToClaim.add(second);
            }
        }
        if (!loggedIndexes.contains(runIndex = claimRunSlot + (claimPage - 1) * 54)) {
            loggedIndexes.add(runIndex);
            LootLogger.logLoot(claimFloor, chestsToClaim, totalChestCount);
        }
        anyRunsClaimed = true;
        indexToClick = bestChest.slot;
        waitingForChestToOpen = true;
        waitFlagSetAt = System.currentTimeMillis();
        failedIndexes.add(runIndex);
    }

    private static void tickChestScreen(Minecraft mc, LocalPlayer player) {
        if (!waitingForChestToOpen) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (!CroesusClaimer.isInvLoaded(mc, player) || menu.getItems().size() < 32) {
            return;
        }
        String title = CroesusClaimer.getScreenTitle(mc);
        Matcher m = CHEST_SCREEN_PATTERN.matcher(title);
        if (!m.matches()) {
            return;
        }
        String chestName = m.group(1);
        waitingForChestToOpen = false;
        if (tryingToKismet && "Bedrock".equals(chestName) && !claimSkipKismet) {
            boolean noKismet;
            tryingToKismet = false;
            ItemStack kismetSlot = CroesusClaimer.getSlot(menu, 50);
            boolean bl = noKismet = kismetSlot.isEmpty() || !ColorUtil.stripColors(kismetSlot.getHoverName().getString()).equals("Reroll Chest");
            if (!noKismet) {
                noKismet = CroesusClaimer.getLorePlain(kismetSlot).contains("Bring a Kismet Feather");
            }
            if (noKismet) {
                canKismet = false;
                skippedKismetFloors.add(claimFloor);
                failedIndexes.add(claimRunSlot + (claimPage - 1) * 54);
                CroesusClaimer.resetClaimInfo();
                tryingToKismet = false;
                needsToLeaveRunGui = true;
                indexToClick = 49;
                waitingForRunToOpen = true;
                waitFlagSetAt = System.currentTimeMillis();
                return;
            }
            if (CroesusClaimer.getLorePlain(kismetSlot).contains("You already rerolled a chest!")) {
                ChatUtil.msg("\u00a7eAlready rerolled!");
                claimSkipKismet = true;
                indexToClick = 49;
                waitingForRunToOpen = true;
                waitFlagSetAt = System.currentTimeMillis();
                return;
            }
            claimSkipKismet = true;
            indexToClick = 50;
            return;
        }
        indexToClick = 31;
        if (claimChestSlot < 0) {
            CroesusClaimer.resetClaimInfo();
        }
    }

    public static void startAutoClaiming() {
        if (!XclipsenAutoCroesusModule.isEnabled()) {
            ChatUtil.msg(XclipsenAutoCroesusModule.disabledMessage());
            return;
        }
        autoClaiming = true;
    }

    public static void reset() {
        autoClaiming = false;
        failedIndexes.clear();
        CroesusClaimer.resetClaimInfo();
        waitingForCroesus = false;
        waitingForRunToOpen = false;
        waitingForChestToOpen = false;
        lastPageOn = -1;
        waitingOnPage = -1;
        pageChangedAt = 0L;
        indexToClick = -1;
        tryingToKismet = false;
        canKismet = true;
        anyRunsClaimed = false;
        needsToLeaveRunGui = false;
        skippedKismetFloors.clear();
        waitFlagSetAt = 0L;
        prevScreenTitle = "";
        prevWasInCroesus = false;
        croesusEnteredAt = 0L;
    }

    private static void resetClaimInfo() {
        claimFloor = null;
        claimPage = 0;
        claimRunSlot = -1;
        claimChestSlot = -1;
        claimSkipKismet = false;
    }

    private static void startClaiming(Minecraft mc, LocalPlayer player) {
        autoClaiming = true;
        if (!CroesusClaimer.tryClickCroesus(mc, player)) {
            autoClaiming = false;
            ChatUtil.msg("\u00a7c[Error 101] \u00a7fCould not find or reach Croesus. \u00a77DM 22yrs on Discord");
            CroesusClaimer.reset();
            return;
        }
        waitingForCroesus = true;
    }

    private static boolean tryClickCroesus(Minecraft mc, LocalPlayer player) {
        ClientLevel level = mc.level;
        if (level == null) {
            return false;
        }
        AABB box = new AABB(player.getX() - 5.0, player.getY() - 3.0, player.getZ() - 5.0, player.getX() + 5.0, player.getY() + 3.0, player.getZ() + 5.0);
        List<ArmorStand> stands = level.getEntities(EntityTypeTest.forClass(ArmorStand.class), box, stand -> ColorUtil.stripColors(stand.getName().getString()).equals("Croesus"));
        if (stands.isEmpty()) {
            return false;
        }
        ArmorStand displayStand = (ArmorStand)stands.getFirst();
        List<AbstractClientPlayer> npcs = level.getEntities(EntityTypeTest.forClass(AbstractClientPlayer.class), box, p -> p != player && p.getUUID().version() == 2 && Math.abs(p.getX() - displayStand.getX()) < 0.01 && Math.abs(p.getZ() - displayStand.getZ()) < 0.01);
        if (npcs.isEmpty()) {
            return false;
        }
        if (npcs.size() > 1) {
            ChatUtil.msg("\u00a7c[Error 102] \u00a7fFound multiple possible Croesus entities. \u00a77DM 22yrs on Discord");
            return false;
        }
        AbstractClientPlayer croesus = (AbstractClientPlayer)npcs.getFirst();
        double distSq = player.distanceToSqr(croesus);
        if (distSq > 16.0) {
            ChatUtil.msg("\u00a7c[Error 101] \u00a7fCroesus is too far away! \u00a77DM 22yrs on Discord");
            return false;
        }
        if (mc.gameMode != null) {
            mc.gameMode.interact(player, croesus, new EntityHitResult(croesus), InteractionHand.MAIN_HAND);
        }
        return true;
    }

    private static String getScreenTitle(Minecraft mc) {
        if (mc.screen == null) {
            return "";
        }
        return ColorUtil.stripColors(mc.screen.getTitle().getString());
    }

    private static boolean inCroesus(Minecraft mc) {
        return CroesusClaimer.getScreenTitle(mc).equals("Croesus");
    }

    private static boolean inRunGui(Minecraft mc) {
        return RUN_GUI_PATTERN.matcher(CroesusClaimer.getScreenTitle(mc)).matches();
    }

    private static boolean isInvLoaded(Minecraft mc, LocalPlayer player) {
        if (mc.screen == null) {
            return false;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) {
            return false;
        }
        NonNullList<ItemStack> items = menu.getItems();
        return items.size() > 45 && !((ItemStack)items.get(items.size() - 45)).isEmpty();
    }

    private static int getCurrPage(AbstractContainerMenu menu) {
        String prevName;
        ItemStack next = CroesusClaimer.getSlot(menu, 53);
        ItemStack prev = CroesusClaimer.getSlot(menu, 45);
        String nextName = ColorUtil.stripColors(next.getHoverName().getString());
        if (nextName.contains("Next Page")) {
            for (String line : CroesusClaimer.getLoreLines(next)) {
                Matcher m = PAGE_NUM_PATTERN.matcher(ColorUtil.stripColors(line));
                if (!m.find()) continue;
                return Integer.parseInt(m.group(1)) - 1;
            }
        }
        if ((prevName = ColorUtil.stripColors(prev.getHoverName().getString())).contains("Previous Page")) {
            for (String line : CroesusClaimer.getLoreLines(prev)) {
                Matcher m = PAGE_NUM_PATTERN.matcher(ColorUtil.stripColors(line));
                if (!m.find()) continue;
                return Integer.parseInt(m.group(1)) + 1;
            }
        }
        return 1;
    }

    private static int[] findUnopenedChest(AbstractContainerMenu menu, int page) {
        for (int slotIdx : CHEST_SLOTS) {
            int floorNum;
            String lorePlain;
            int extendedIndex = slotIdx + (page - 1) * 54;
            if (failedIndexes.contains(extendedIndex)) continue;
            ItemStack stack = CroesusClaimer.getSlot(menu, slotIdx);
            if (stack.isEmpty()) {
                return null;
            }
            if (!stack.is(Items.PLAYER_HEAD) || !(lorePlain = CroesusClaimer.getLorePlain(stack)).contains("No chests opened yet!")) continue;
            String dungeonType = ColorUtil.stripColors(stack.getHoverName().getString());
            boolean isMaster = dungeonType.contains("Master Mode");
            List<String> loreLines = CroesusClaimer.getLoreLines(stack);
            if (loreLines.isEmpty()) continue;
            String floorLine = ColorUtil.stripColors(loreLines.getFirst());
            Matcher fm = FLOOR_PATTERN.matcher(floorLine);
            if (!fm.find()) {
                failedIndexes.add(extendedIndex);
                continue;
            }
            String floorStr = fm.group(1);
            try {
                floorNum = Integer.parseInt(floorStr);
            }
            catch (NumberFormatException e) {
                floorNum = ItemParser.decodeRoman(floorStr);
            }
            String floorKey = (isMaster ? "M" : "F") + floorNum;
            if (!canKismet && AcDataStore.config.kismetFloors.contains(floorKey)) {
                skippedKismetFloors.add(floorKey);
                failedIndexes.add(extendedIndex);
                continue;
            }
            return new int[]{slotIdx, floorNum, isMaster ? 1 : 0};
        }
        return null;
    }

    private static void sortChestData(List<ItemParser.ChestInfo> chestData) {
        chestData.sort((a, b) -> {
            boolean aHasAlways = a.items.stream().anyMatch(i -> AcDataStore.alwaysBuy.contains(i.id));
            boolean bHasAlways = b.items.stream().anyMatch(i -> AcDataStore.alwaysBuy.contains(i.id));
            if (bHasAlways && !aHasAlways) {
                return 1;
            }
            if (aHasAlways && !bHasAlways) {
                return -1;
            }
            return Long.compare(b.profit, a.profit);
        });
    }

    private static String fmtFloor(String floor) {
        if (floor == null) {
            return "\u00a7f?";
        }
        return floor.startsWith("M") ? "\u00a74\u00a7l" + floor : "\u00a7a" + floor;
    }

    private static ItemStack getSlot(AbstractContainerMenu menu, int index) {
        if (index < 0 || index >= menu.slots.size()) {
            return ItemStack.EMPTY;
        }
        return menu.slots.get(index).getItem();
    }

    private static List<String> getLoreLines(ItemStack stack) {
        ItemLore lore = (ItemLore)stack.get(DataComponents.LORE);
        if (lore == null) {
            return Collections.emptyList();
        }
        ArrayList<String> lines = new ArrayList<String>();
        for (Component c : lore.styledLines()) {
            lines.add(c.getString());
        }
        return lines;
    }

    private static String getLorePlain(ItemStack stack) {
        ItemLore lore = (ItemLore)stack.get(DataComponents.LORE);
        if (lore == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Component c : lore.styledLines()) {
            sb.append(ColorUtil.stripColors(c.getString())).append(' ');
        }
        return sb.toString();
    }

    private static List<String> getTooltip(ItemStack stack) {
        ArrayList<String> tooltip = new ArrayList<String>();
        tooltip.add(stack.getHoverName().getString());
        ItemLore lore = (ItemLore)stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component c : lore.styledLines()) {
                tooltip.add(c.getString());
            }
        }
        return tooltip;
    }

    static {
        CHEST_COLORS.put("Wood", "\u00a7f");
        CHEST_COLORS.put("Gold", "\u00a76");
        CHEST_COLORS.put("Diamond", "\u00a7b");
        CHEST_COLORS.put("Emerald", "\u00a72");
        CHEST_COLORS.put("Obsidian", "\u00a75");
        CHEST_COLORS.put("Bedrock", "\u00a78");
    }
}
