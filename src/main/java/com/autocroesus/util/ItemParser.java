/*
 * Decompiled with CFR 0.152.
 */
package com.autocroesus.util;

import com.autocroesus.config.AcDataStore;
import com.autocroesus.util.ColorUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ItemParser {
    private static final Set<String> ULTIMATE_ENCHANTS = new HashSet<String>(Arrays.asList("Bank", "Bobbin Time", "Chimera", "Combo", "Duplex", "Fatal Tempo", "Flash", "Habanero Tactics", "Inferno", "Last Stand", "Legion", "No Pain No Gain", "One For All", "Rend", "Soul Eater", "Swarm", "The One", "Ultimate Jerry", "Ultimate Wise", "Wisdom"));
    private static final Map<String, String> ITEM_REPLACEMENTS = new HashMap<String, String>();
    private static final Pattern BOOK_PATTERN;
    private static final Pattern ESSENCE_PATTERN;
    private static final Pattern COST_PATTERN;
    private static final String[] ROMAN_NUMS;
    private static final Pattern ULTIMATE_ENCHANT_PATTERN;
    private static final Pattern ENCHANT_PATTERN;
    private static final Map<Character, Integer> ROMAN_VALUES;
    private static final Map<String, String> TIER_COLORS;

    public static int decodeRoman(String s) {
        int sum = 0;
        for (int i = 0; i < s.length(); ++i) {
            int next;
            int curr = ROMAN_VALUES.getOrDefault(Character.valueOf(s.charAt(i)), 0);
            int n = next = i < s.length() - 1 ? ROMAN_VALUES.getOrDefault(Character.valueOf(s.charAt(i + 1)), 0) : 0;
            if (curr < next) {
                sum += next - curr;
                ++i;
                continue;
            }
            sum += curr;
        }
        return sum;
    }

    public static String romanNumeral(int n) {
        if (n >= 0 && n < ROMAN_NUMS.length) {
            return ROMAN_NUMS[n];
        }
        return String.valueOf(n);
    }

    private static String[] tryParseBook(String line) {
        int tier;
        Matcher m = BOOK_PATTERN.matcher(line);
        if (!m.find()) {
            return null;
        }
        String bookName = m.group(1).trim();
        String tierStr = m.group(2).trim();
        bookName = ColorUtil.stripColors(bookName).trim();
        boolean isUltimate = ULTIMATE_ENCHANTS.contains(bookName);
        try {
            tier = Integer.parseInt(tierStr);
        }
        catch (NumberFormatException e) {
            tier = ItemParser.decodeRoman(tierStr);
        }
        String enchantPart = bookName.toUpperCase().replace(" ", "_").replace("'", "");
        String sbId = "ENCHANTMENT_" + (isUltimate ? "ULTIMATE_" : "") + enchantPart + "_" + tier;
        sbId = ((String)sbId).replace("ULTIMATE_ULTIMATE_", "ULTIMATE_");
        return new String[]{sbId, "1"};
    }

    private static String[] tryParseEssence(String line) {
        Matcher m = ESSENCE_PATTERN.matcher(line);
        if (!m.matches()) {
            return null;
        }
        return new String[]{"ESSENCE_" + m.group(1).toUpperCase(), m.group(2)};
    }

    public static String[] parseLine(String line) {
        String[] book = ItemParser.tryParseBook(line);
        if (book != null) {
            return book;
        }
        String clean = ColorUtil.stripColors(line).trim();
        String[] essence = ItemParser.tryParseEssence(clean);
        if (essence != null) {
            return essence;
        }
        if (ITEM_REPLACEMENTS.containsKey(clean)) {
            return new String[]{ITEM_REPLACEMENTS.get(clean), "1"};
        }
        for (AcDataStore.SbItem item : AcDataStore.sbItems) {
            if (!item.name.equals(clean) || item.id.startsWith("STARRED_")) continue;
            return new String[]{item.id, "1"};
        }
        return new String[]{"false", "Could not find item ID for line \"" + clean + "\""};
    }

    public static ChestInfo parseRewards(List<String> fullTooltip, String[] errorOut) {
        int costIdx = -1;
        for (int i = 0; i < fullTooltip.size(); ++i) {
            if (!ColorUtil.stripColors(fullTooltip.get(i)).contains("Cost")) continue;
            costIdx = i;
            break;
        }
        if (costIdx < 0) {
            if (errorOut != null) {
                errorOut[0] = "Could not find Cost line";
            }
            return null;
        }
        if (costIdx + 1 >= fullTooltip.size()) {
            if (errorOut != null) {
                errorOut[0] = "Cost value line missing";
            }
            return null;
        }
        String costStr = ColorUtil.stripColors(fullTooltip.get(costIdx + 1)).trim();
        ChestInfo info = new ChestInfo();
        if (!costStr.contains("FREE")) {
            Matcher cm = COST_PATTERN.matcher(costStr);
            if (!cm.matches()) {
                if (errorOut != null) {
                    errorOut[0] = "Could not parse cost: \"" + costStr + "\"";
                }
                return null;
            }
            info.cost = Long.parseLong(cm.group(1).replace(",", ""));
        }
        int lootEnd = costIdx - 1;
        for (int i = 2; i < lootEnd; ++i) {
            String line = fullTooltip.get(i);
            String clean = ColorUtil.stripColors(line).trim();
            if (clean.isEmpty()) continue;
            String[] result = ItemParser.parseLine(line);
            if (result[0].equals("false")) {
                if (errorOut != null) {
                    errorOut[0] = result[1];
                }
                return null;
            }
            String sbId = result[0];
            int qty = Integer.parseInt(result[1]);
            Double itemValue = AcDataStore.getSellPrice(sbId, true);
            if (itemValue == null) {
                if (errorOut != null) {
                    errorOut[0] = "Could not find value of \"" + clean + "\"";
                }
                return null;
            }
            info.value += itemValue * (double)qty;
            RewardItem ri = new RewardItem();
            ri.id = sbId;
            ri.qty = qty;
            ri.value = itemValue;
            ri.displayName = line.replaceAll("^\u00a75\u00a7o", "").trim();
            info.items.add(ri);
        }
        info.items.sort(Comparator.comparingDouble((RewardItem a) -> a.value * (double)a.qty).reversed());
        info.profit = Math.round(info.value - (double)info.cost);
        return info;
    }

    public static String getFormattedNameFromId(String itemId) {
        Matcher m;
        if (itemId.startsWith("ENCHANTMENT_ULTIMATE_") && (m = ULTIMATE_ENCHANT_PATTERN.matcher(itemId)).matches()) {
            String enchant = ItemParser.toTitleCase(m.group(1).replace("_", " ").toLowerCase());
            if (itemId.startsWith("ENCHANTMENT_ULTIMATE_WISE")) {
                enchant = "Ultimate Wise";
            }
            if (itemId.startsWith("ENCHANTMENT_ULTIMATE_JERRY")) {
                enchant = "Ultimate Jerry";
            }
            return "\u00a7aEnchanted Book (\u00a7d\u00a7l" + enchant + " " + ItemParser.romanNumeral(Integer.parseInt(m.group(2))) + "\u00a7a)\u00a7r";
        }
        if (itemId.startsWith("ENCHANTMENT_") && (m = ENCHANT_PATTERN.matcher(itemId)).matches()) {
            String enchant = ItemParser.toTitleCase(m.group(1).replace("_", " ").toLowerCase());
            int tier = Integer.parseInt(m.group(2));
            String color = tier >= 9 ? "\u00a7d" : (tier == 8 ? "\u00a76" : (tier == 7 ? "\u00a75" : (tier == 6 ? "\u00a79" : (tier == 5 ? "\u00a7a" : "\u00a7f"))));
            return "\u00a7aEnchanted Book (" + color + enchant + " " + ItemParser.romanNumeral(tier) + "\u00a7a)\u00a7r";
        }
        if (itemId.startsWith("ESSENCE_")) {
            String essType = itemId.substring(8);
            return "\u00a7d" + ItemParser.toTitleCase(essType.replace("_", " ").toLowerCase()) + " Essence\u00a7r";
        }
        AcDataStore.SbItem entry = AcDataStore.getItemApiData(itemId);
        if (entry == null) {
            return itemId;
        }
        String color = TIER_COLORS.getOrDefault(entry.tier, "\u00a7f");
        return color + entry.name;
    }

    private static String toTitleCase(String s) {
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = true;
        for (char c : s.toCharArray()) {
            if (c == ' ') {
                sb.append(c);
                nextUpper = true;
                continue;
            }
            if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static {
        ITEM_REPLACEMENTS.put("Shiny Wither Boots", "WITHER_BOOTS");
        ITEM_REPLACEMENTS.put("Shiny Wither Leggings", "WITHER_LEGGINGS");
        ITEM_REPLACEMENTS.put("Shiny Wither Chestplate", "WITHER_CHESTPLATE");
        ITEM_REPLACEMENTS.put("Shiny Wither Helmet", "WITHER_HELMET");
        ITEM_REPLACEMENTS.put("Shiny Necron's Handle", "NECRON_HANDLE");
        ITEM_REPLACEMENTS.put("Wither Shard", "SHARD_WITHER");
        ITEM_REPLACEMENTS.put("Thorn Shard", "SHARD_THORN");
        ITEM_REPLACEMENTS.put("Apex Dragon Shard", "SHARD_APEX_DRAGON");
        ITEM_REPLACEMENTS.put("Power Dragon Shard", "SHARD_POWER_DRAGON");
        ITEM_REPLACEMENTS.put("Scarf Shard", "SHARD_SCARF");
        ITEM_REPLACEMENTS.put("Necron Dye", "DYE_NECRON");
        ITEM_REPLACEMENTS.put("Livid Dye", "DYE_LIVID");
        BOOK_PATTERN = Pattern.compile("Enchanted Book \\((?:\u00a7.)*([\\w' ]+?) ((?:[IVX]+|\\d+))(?:\u00a7.)*\\)");
        ESSENCE_PATTERN = Pattern.compile("^(\\w+) Essence x(\\d+)$");
        COST_PATTERN = Pattern.compile("^([\\d,]+) Coins$");
        ROMAN_NUMS = new String[]{"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        ULTIMATE_ENCHANT_PATTERN = Pattern.compile("^ENCHANTMENT_ULTIMATE_([\\w_]+)_(\\d+)$");
        ENCHANT_PATTERN = Pattern.compile("^ENCHANTMENT_([\\w_]+)_(\\d+)$");
        ROMAN_VALUES = new HashMap<Character, Integer>();
        ROMAN_VALUES.put(Character.valueOf('I'), 1);
        ROMAN_VALUES.put(Character.valueOf('V'), 5);
        ROMAN_VALUES.put(Character.valueOf('X'), 10);
        ROMAN_VALUES.put(Character.valueOf('L'), 50);
        ROMAN_VALUES.put(Character.valueOf('C'), 100);
        ROMAN_VALUES.put(Character.valueOf('D'), 500);
        ROMAN_VALUES.put(Character.valueOf('M'), 1000);
        TIER_COLORS = new LinkedHashMap<String, String>();
        TIER_COLORS.put("COMMON", "\u00a7f");
        TIER_COLORS.put("UNCOMMON", "\u00a7a");
        TIER_COLORS.put("RARE", "\u00a79");
        TIER_COLORS.put("EPIC", "\u00a75");
        TIER_COLORS.put("LEGENDARY", "\u00a76");
        TIER_COLORS.put("MYTHIC", "\u00a7d");
        TIER_COLORS.put("SPECIAL", "\u00a7c");
        TIER_COLORS.put("VERY_SPECIAL", "\u00a7c");
        TIER_COLORS.put("SUPREME", "\u00a74");
    }

    public static class ChestInfo {
        public long cost;
        public double value;
        public long profit;
        public List<RewardItem> items = new ArrayList<RewardItem>();
        public int slot;
        public String chestName;
        public String chestColor;
    }

    public static class RewardItem {
        public String id;
        public int qty;
        public double value;
        public String displayName;
    }
}
