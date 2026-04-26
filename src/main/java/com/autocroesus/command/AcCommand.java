/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
 *  net.minecraft.text.HoverEvent
 *  net.minecraft.text.HoverEvent$ShowText
 *  net.minecraft.text.MutableText
 *  net.minecraft.text.Style
 *  net.minecraft.text.Text
 */
package com.autocroesus.command;

import com.autocroesus.XclipsenAutoCroesusModule;
import com.autocroesus.config.AcDataStore;
import com.autocroesus.feature.CroesusClaimer;
import com.autocroesus.price.PriceFetcher;
import com.autocroesus.util.ChatUtil;
import com.autocroesus.util.ColorUtil;
import com.autocroesus.util.ItemParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

public class AcCommand {
    private static final Pattern FLOOR_PATTERN = Pattern.compile("^[FfMm][1-7]$");
    private static final Pattern LOOT_FLOOR_PATTERN = Pattern.compile("^(?:f|floor):([fFmM][1-7])$");
    private static final Pattern LOOT_SCORE_PATTERN = Pattern.compile("^(?:score|s):(\\d+)$");
    private static final Pattern LOOT_LIMIT_PATTERN = Pattern.compile("^(?:l|limit):(\\d+)$");

    public static void register(Object ignored) {
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            boolean isLong;
            String cmd = command.trim();
            boolean isAc = cmd.equals("/ac") || cmd.startsWith("/ac ");
            boolean bl = isLong = cmd.equals("/autocroesus") || cmd.startsWith("/autocroesus ");
            if (!isAc && !isLong) {
                return true;
            }
            String[] parts = cmd.split(" ", -1);
            String[] args = Arrays.copyOfRange(parts, 1, parts.length);
            AcCommand.handleCommand(args);
            return false;
        });
    }

    private static void handleCommand(String[] args) {
        if (!XclipsenAutoCroesusModule.isEnabled()) {
            ChatUtil.msg(XclipsenAutoCroesusModule.disabledMessage());
            return;
        }
        if (args.length == 0) {
            AcCommand.printHelp();
            return;
        }
        switch (args[0].toLowerCase()) {
            case "go": {
                AcCommand.cmdGo(false);
                break;
            }
            case "forcego": {
                AcCommand.cmdGo(true);
                break;
            }
            case "reset": {
                CroesusClaimer.reset();
                ChatUtil.msg("Reset!");
                break;
            }
            case "api": {
                AcCommand.cmdApi();
                break;
            }
            case "settings": 
            case "config": 
            case "s": 
            case "c": {
                AcCommand.printSettings();
                break;
            }
            case "noclick": {
                AcCommand.cmdNoClick();
                break;
            }
            case "delay": {
                AcCommand.cmdDelay(args);
                break;
            }
            case "key": 
            case "chestkey": {
                AcCommand.cmdKey(args.length > 1 ? args[1] : null);
                break;
            }
            case "kismet": 
            case "reroll": {
                AcCommand.cmdKismet(args.length > 1 ? args[1] : null);
                break;
            }
            case "alwaysbuy": {
                AcCommand.cmdAlwaysBuy(args.length > 1 ? args[1] : null);
                break;
            }
            case "worthless": {
                AcCommand.cmdWorthless(args.length > 1 ? args[1] : null);
                break;
            }
            case "loot": {
                AcCommand.cmdLoot(Arrays.copyOfRange(args, 1, args.length));
                break;
            }
            default: {
                AcCommand.printHelp();
            }
        }
    }

    private static void cmdGo(boolean force) {
        if (force) {
            ChatUtil.msg("\u00a7aClaiming without updating API.");
            CroesusClaimer.startAutoClaiming();
            return;
        }
        long sinceUpdate = System.currentTimeMillis() - AcDataStore.config.lastApiUpdate;
        if (sinceUpdate <= 1800000L) {
            CroesusClaimer.startAutoClaiming();
            return;
        }
        ChatUtil.msg("\u00a7ePrices have not been updated in over 30 minutes. Grabbing data...");
        PriceFetcher.updatePrices().thenRun(() -> {
            AcDataStore.config.lastApiUpdate = System.currentTimeMillis();
            AcDataStore.saveConfig();
            ChatUtil.msg("\u00a7aSuccessfully grabbed data from API!");
            CroesusClaimer.startAutoClaiming();
        }).exceptionally(e -> {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            ChatUtil.msg("\u00a7c[Error 107] \u00a7fFailed to grab data from API: " + cause.getMessage() + " \u00a77DM 22yrs on Discord");
            ChatUtil.msg("\u00a7cTo try again, run //ac api");
            return null;
        });
    }

    private static void cmdApi() {
        ChatUtil.msg("\u00a7aGrabbing data...");
        PriceFetcher.updatePrices().thenRun(() -> {
            AcDataStore.config.lastApiUpdate = System.currentTimeMillis();
            AcDataStore.saveConfig();
            ChatUtil.msg("\u00a7aSuccessfully grabbed data from API!");
        }).exceptionally(e -> {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            ChatUtil.msg("\u00a7c[Error 107] \u00a7fFailed to grab data from API: " + cause.getMessage() + " \u00a77DM 22yrs on Discord");
            ChatUtil.msg("\u00a7cTo try again, run //ac api");
            return null;
        });
    }

    private static void cmdDelay(String[] args) {
        if (args.length < 2) {
            ChatUtil.msg("\u00a7cUsage: //ac delay <ms>");
            return;
        }
        try {
            int ms;
            AcDataStore.config.minClickDelay = ms = Integer.parseInt(args[1]);
            AcDataStore.saveConfig();
            ChatUtil.msg("Min Click Delay is now \u00a76" + ms + "ms");
            if (ms < 150) {
                ChatUtil.msg("\u00a7cWarning: Setting the delay to a low value with low ping will claim chests so quickly that people in chat might notice. Be careful setting this so low.");
            }
        }
        catch (NumberFormatException e) {
            ChatUtil.msg("\u00a7cUsage: //ac delay <ms>");
        }
    }

    private static void cmdNoClick() {
        AcDataStore.config.noClick = !AcDataStore.config.noClick;
        AcDataStore.saveConfig();
        ChatUtil.msg("No Click is now set to " + ColorUtil.formattedBool(AcDataStore.config.noClick));
    }

    private static void cmdKey(String arg) {
        if (arg != null) {
            try {
                long profit;
                AcDataStore.config.chestKeyMinProfit = profit = Long.parseLong(arg.replace(",", "").replace("_", ""));
                AcDataStore.saveConfig();
                ChatUtil.msg("Min chest key profit is now " + ColorUtil.formatNumber(profit));
                return;
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        AcDataStore.config.useChestKeys = !AcDataStore.config.useChestKeys;
        AcDataStore.saveConfig();
        ChatUtil.msg("Use Chest Keys is now " + ColorUtil.formattedBool(AcDataStore.config.useChestKeys));
    }

    private static void cmdKismet(String arg) {
        if (arg == null) {
            AcDataStore.config.useKismets = !AcDataStore.config.useKismets;
            AcDataStore.saveConfig();
            ChatUtil.msg("Use Kismets is now " + ColorUtil.formattedBool(AcDataStore.config.useKismets));
            return;
        }
        if (FLOOR_PATTERN.matcher(arg).matches()) {
            String floor = arg.toUpperCase();
            if (AcDataStore.config.kismetFloors.contains(floor)) {
                AcDataStore.config.kismetFloors.remove(floor);
                ChatUtil.msg("\u00a7fRemoved " + AcCommand.fmtFloor(floor) + "\u00a7f from kismet floors");
            } else {
                AcDataStore.config.kismetFloors.add(floor);
                ChatUtil.msg("\u00a7fAdded " + AcCommand.fmtFloor(floor) + "\u00a7f to kismet floors");
            }
            AcDataStore.saveConfig();
            return;
        }
        try {
            long profit;
            AcDataStore.config.kismetMinProfit = profit = Long.parseLong(arg.replace(",", "").replace("_", ""));
            AcDataStore.saveConfig();
            ChatUtil.msg("Min kismet profit is now " + ColorUtil.formatNumber(profit));
        }
        catch (NumberFormatException e) {
            ChatUtil.msg("\u00a7cUsage: //ac kismet [toggle|<floor>|<min_profit>]");
        }
    }

    private static void cmdAlwaysBuy(String id) {
        if (id == null) {
            ChatUtil.msg("\u00a7b\u00a7lAlways Buy Items:");
            for (String entry : AcDataStore.alwaysBuy) {
                ChatUtil.msg("  " + ItemParser.getFormattedNameFromId(entry));
            }
            return;
        }
        if (id.equalsIgnoreCase("reset")) {
            AcDataStore.alwaysBuy.clear();
            AcDataStore.alwaysBuy.addAll(Arrays.asList(AcDataStore.DEFAULT_ALWAYS_BUY));
            AcDataStore.saveAlwaysBuy();
            ChatUtil.msg("\u00a7aResetting the list of items to always buy to their defaults.");
            return;
        }
        String upper = id.toUpperCase();
        if (AcDataStore.alwaysBuy.contains(upper)) {
            AcDataStore.alwaysBuy.remove(upper);
            AcDataStore.saveAlwaysBuy();
            ChatUtil.msg("\u00a7cRemoved \u00a7f" + upper + " \u00a7cfrom Always Buy list!");
        } else {
            if (!AcDataStore.itemIdExists(upper)) {
                ChatUtil.msg("\u00a7cWarning: Could not find \u00a7f" + upper + " \u00a7cin the Skyblock items database. This could be a new item, so it will be added to the list anyway.");
            }
            AcDataStore.alwaysBuy.add(upper);
            AcDataStore.saveAlwaysBuy();
            ChatUtil.msg("\u00a7aAdded \u00a7f" + upper + " \u00a7ato the list of items to always be bought.");
        }
    }

    private static void cmdWorthless(String id) {
        if (id == null) {
            ChatUtil.msg("\u00a7b\u00a7lWorthless Items:");
            for (String entry : AcDataStore.worthless) {
                ChatUtil.msg("  " + ItemParser.getFormattedNameFromId(entry));
            }
            return;
        }
        if (id.equalsIgnoreCase("reset")) {
            AcDataStore.worthless.clear();
            AcDataStore.worthless.addAll(Arrays.asList(AcDataStore.DEFAULT_WORTHLESS));
            AcDataStore.saveWorthless();
            ChatUtil.msg("\u00a7aResetting the list of worthless items to their defaults.");
            return;
        }
        String upper = id.toUpperCase();
        if (AcDataStore.worthless.contains(upper)) {
            AcDataStore.worthless.remove(upper);
            AcDataStore.saveWorthless();
            ChatUtil.msg("\u00a7cRemoved \u00a7f" + upper + " \u00a7cfrom Worthless list!");
        } else {
            if (!AcDataStore.itemIdExists(upper)) {
                ChatUtil.msg("\u00a7cWarning: Could not find \u00a7f" + upper + " \u00a7cin the Skyblock items database. This could be a new item, so it will be added to the list anyway.");
            }
            AcDataStore.worthless.add(upper);
            AcDataStore.saveWorthless();
            ChatUtil.msg("\u00a7aAdded \u00a7f" + upper + " \u00a7ato the list of worthless items.");
        }
    }

    private static void cmdLoot(String[] args) {
        List<String> lines;
        if (args.length > 0 && args[0].equalsIgnoreCase("help")) {
            ChatUtil.msg("\u00a7e//ac loot [floor:<floor>] [limit:<limit>] [score:<score>]");
            ChatUtil.msg("\u00a77Example: \u00a7b//ac loot floor:F7 limit:100 score:300");
            return;
        }
        int minScore = 0;
        String floor = null;
        int limit = Integer.MAX_VALUE;
        for (String seg : args) {
            Matcher fm = LOOT_FLOOR_PATTERN.matcher(seg);
            if (fm.matches()) {
                floor = fm.group(1).toUpperCase();
                continue;
            }
            Matcher sm = LOOT_SCORE_PATTERN.matcher(seg);
            if (sm.matches()) {
                int s = Integer.parseInt(sm.group(1));
                if (s < 0 || s > 317) {
                    ChatUtil.msg("\u00a7cScore " + s + " not in valid range 0-317");
                    return;
                }
                minScore = s;
                continue;
            }
            Matcher lm = LOOT_LIMIT_PATTERN.matcher(seg);
            if (!lm.matches()) continue;
            limit = Integer.parseInt(lm.group(1));
        }
        if (!Files.exists(AcDataStore.LOOT_LOG_FILE, new LinkOption[0])) {
            ChatUtil.msg("\u00a7cNo loot has been logged!");
            return;
        }
        try {
            lines = Files.readAllLines(AcDataStore.LOOT_LOG_FILE);
        }
        catch (IOException e) {
            ChatUtil.msg("\u00a7c[Error 108] \u00a7fFailed to read loot log: " + e.getMessage() + " \u00a77DM 22yrs on Discord");
            return;
        }
        Collections.reverse(lines);
        int dungeons = 0;
        long totalChestCost = 0L;
        LinkedHashMap<String, Integer> loot = new LinkedHashMap<String, Integer>();
        for (String line : lines) {
            long chestCost;
            int runScore;
            String[] parts;
            if (line.isBlank() || (parts = line.split(" ")).length < 3) continue;
            String runFloor = parts[0];
            try {
                runScore = Integer.parseInt(parts[1]);
                chestCost = Long.parseLong(parts[2]);
            }
            catch (NumberFormatException e) {
                continue;
            }
            if (floor != null && !runFloor.equals(floor) || runScore < minScore) continue;
            ++dungeons;
            totalChestCost += chestCost;
            for (int i = 3; i < parts.length; ++i) {
                String[] kv = parts[i].split(":");
                if (kv.length != 2) continue;
                try {
                    loot.merge(kv[0], Integer.parseInt(kv[1]), Integer::sum);
                    continue;
                }
                catch (NumberFormatException numberFormatException) {
                    // empty catch block
                }
            }
            if (dungeons < limit) continue;
            break;
        }
        if (dungeons == 0) {
            ChatUtil.msg("\u00a7cNo runs found matching those filters.");
            return;
        }
        String floorLabel = floor != null ? AcCommand.fmtFloor(floor) : "\u00a7bAll Floors";
        ArrayList<long[]> itemsSorted = new ArrayList<long[]>();
        String[] itemIds = loot.keySet().toArray(new String[0]);
        long totalSellPrice = 0L;
        for (int i = 0; i < itemIds.length; ++i) {
            int qty = (Integer)loot.get(itemIds[i]);
            Double valueD = AcDataStore.getSellPrice(itemIds[i], true);
            long value = valueD != null ? (long)valueD.doubleValue() : 0L;
            long total = value * (long)qty;
            totalSellPrice += total;
            itemsSorted.add(new long[]{total, value, qty, i});
        }
        itemsSorted.sort((a, b) -> Long.compare(b[0], a[0]));
        long totalProfit = totalSellPrice - totalChestCost;
        long profitPerRun = dungeons > 0 ? totalProfit / (long)dungeons : 0L;
        StringBuilder hover = new StringBuilder();
        hover.append("\u00a7aLoot from \u00a7e").append(ColorUtil.formatNumber(dungeons)).append(" \u00a7aruns on ").append(floorLabel).append("\u00a7a:\n");
        int shown = 0;
        long extraValue = 0L;
        int extraCount = 0;
        for (long[] entry : itemsSorted) {
            long totalVal = entry[0];
            long value = entry[1];
            int qty = (int)entry[2];
            String id = itemIds[(int)entry[3]];
            if (shown >= 25) {
                ++extraCount;
                extraValue += totalVal;
                continue;
            }
            ++shown;
            double pct = totalSellPrice > 0L ? (double)totalVal * 100.0 / (double)totalSellPrice : 0.0;
            hover.append("\u00a7b").append(ColorUtil.formatNumber(qty)).append("x \u00a7a").append(ItemParser.getFormattedNameFromId(id)).append(" \u00a7a(\u00a76").append(ColorUtil.formatNumber(value)).append("\u00a7a)").append(" = \u00a76").append(ColorUtil.formatNumber(totalVal)).append(" \u00a78(").append(String.format("%.2f", pct)).append("%)\n");
        }
        if (extraCount > 0) {
            hover.append("\u00a7a... and ").append(extraCount).append(" more (\u00a76").append(ColorUtil.formatNumber(extraValue)).append("\u00a7a)\n");
        }
        hover.append("\u00a7cTotal Chest Cost: \u00a76").append(ColorUtil.formatNumber(totalChestCost)).append("\n");
        hover.append("\u00a7cTotal Sell Price: \u00a76").append(ColorUtil.formatNumber(totalSellPrice)).append("\n");
        hover.append("\u00a7eTotal Profit: \u00a76").append(ColorUtil.formatNumber(totalProfit)).append("\n");
        hover.append("\u00a7bProfit/Run: \u00a76").append(ColorUtil.formatNumber(profitPerRun));
        ChatUtil.msg("\u00a7aAverage profit from \u00a7e" + ColorUtil.formatNumber(dungeons) + " \u00a7aruns on " + floorLabel + "\u00a7a: \u00a76" + ColorUtil.formatNumber(profitPerRun));
        MutableText hoverText = Text.literal((String)hover.toString());
        MutableText totalLine = Text.literal((String)("\u00a7aTotal Profit: \u00a76" + ColorUtil.formatNumber(totalProfit) + " \u00a77(hover for details)"));
        totalLine.styled(arg_0 -> AcCommand.lambda$cmdLoot$6((Text)hoverText, arg_0));
        ChatUtil.msg((Text)totalLine);
    }

    private static String fmtFloor(String floor) {
        if (floor == null) {
            return "\u00a7f?";
        }
        return floor.startsWith("M") ? "\u00a74\u00a7l" + floor : "\u00a7a" + floor;
    }

    private static void printHelp() {
        ChatUtil.msg(" ");
        ChatUtil.msg("\u00a7b\u00a7lAuto Croesus \u00a7aCommands");
        ChatUtil.msg("\u00a7a//ac \u00a78- \u00a77Show help.");
        ChatUtil.msg("\u00a7a//ac go \u00a78- \u00a77Start looting.");
        ChatUtil.msg("\u00a7a//ac forcego \u00a78- \u00a77Start without API check.");
        ChatUtil.msg("\u00a7a//ac api \u00a78- \u00a77Refresh API.");
        ChatUtil.msg("\u00a7a//ac settings \u00a78- \u00a77View settings.");
        ChatUtil.msg("\u00a7a//ac delay <ms> \u00a78- \u00a77Set click delay.");
        ChatUtil.msg("");
        ChatUtil.msg("\u00a7a//ac kismet \u00a78- \u00a77Toggle rerolls.");
        ChatUtil.msg("\u00a7a//ac kismet <min_profit> \u00a78- \u00a77Configure how much profit is required for the chest to not be rerolled. Eg 2,000,000 would mean any chest with >=2m profit will not be rerolled.");
        ChatUtil.msg("\u00a7a//ac kismet <floor> \u00a78- \u00a77Toggle floor for rerolls.");
        ChatUtil.msg("\u00a7a//ac key \u00a78- \u00a77Toggle chest keys.");
        ChatUtil.msg("\u00a7a//ac key <min_profit> \u00a78- \u00a77Set min profit for keys.");
        ChatUtil.msg("");
        ChatUtil.msg("\u00a7a//ac alwaysbuy [id|reset] \u00a78- \u00a77Manage always-buy items.");
        ChatUtil.msg("\u00a7a//ac worthless [id|reset] \u00a78- \u00a77Manage worthless items.");
        ChatUtil.msg("");
        ChatUtil.msg("\u00a7a//ac loot \u00a78- \u00a77View loot summary.");
        ChatUtil.msg("\u00a7a//ac loot floor:F7 limit:100 score:300 \u00a78- \u00a77Filter loot log.");
        ChatUtil.msg(" ");
    }

    private static void printSettings() {
        String kismetFloors;
        if (AcDataStore.config.kismetFloors.isEmpty()) {
            kismetFloors = "\u00a7cNONE";
        } else {
            StringBuilder kfb = new StringBuilder();
            for (String f : AcDataStore.config.kismetFloors) {
                if (kfb.length() > 0) {
                    kfb.append("\u00a77, ");
                }
                kfb.append(AcCommand.fmtFloor(f));
            }
            kismetFloors = kfb.toString();
        }
        ChatUtil.msg("\u00a7b\u00a7lAutoCroesus \u00a7aSettings");
        ChatUtil.msg("\u00a77Commands to change settings are shown in brackets.");
        ChatUtil.msg("  Min Click Delay: \u00a76" + AcDataStore.config.minClickDelay + "ms \u00a78(//ac delay <ms>)");
        ChatUtil.msg("  \u00a7cWarning: Low values with low ping will make this module ZOOM. Be safe!");
        ChatUtil.msg("");
        ChatUtil.msg("  Use Chest Keys: " + ColorUtil.formattedBool(AcDataStore.config.useChestKeys) + " \u00a78(//ac key)");
        ChatUtil.msg("  Min Chest Key Profit: \u00a76" + ColorUtil.formatNumber(AcDataStore.config.chestKeyMinProfit) + " \u00a78(//ac key <min_profit>)");
        ChatUtil.msg("");
        ChatUtil.msg("  Use Kismets: " + ColorUtil.formattedBool(AcDataStore.config.useKismets) + " \u00a78(//ac kismet)");
        ChatUtil.msg("  Min Kismet Profit: \u00a76" + ColorUtil.formatNumber(AcDataStore.config.kismetMinProfit) + " \u00a78(//ac kismet <min_profit>)");
        ChatUtil.msg("  Kismet Floors: " + kismetFloors + " \u00a78(//ac kismet <floor>)");
    }

    private static /* synthetic */ Style lambda$cmdLoot$6(Text hoverText, Style s) {
        return s.withHoverEvent((HoverEvent)new HoverEvent.ShowText(hoverText));
    }
}
