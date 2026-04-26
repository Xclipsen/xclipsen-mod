/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.GsonBuilder
 *  com.google.gson.JsonArray
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  com.google.gson.JsonParser
 *  net.fabricmc.loader.api.FabricLoader
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.autocroesus.config;

import com.autocroesus.config.AcConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AcDataStore {
    private static final Logger LOG = LoggerFactory.getLogger((String)"AutoCroesus/DataStore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("AutoCroesus");
    public static final Path SETTINGS_FILE = CONFIG_DIR.resolve("settings.json");
    public static final Path ALWAYS_BUY_FILE = CONFIG_DIR.resolve("always_buy.txt");
    public static final Path WORTHLESS_FILE = CONFIG_DIR.resolve("worthless.txt");
    public static final Path BZ_VALUES_FILE = CONFIG_DIR.resolve("bzValues.json");
    public static final Path ITEMS_FILE = CONFIG_DIR.resolve("items.json");
    public static final Path BIN_VALUES_FILE = CONFIG_DIR.resolve("binValues.json");
    public static final Path LOOT_LOG_FILE = CONFIG_DIR.resolve("runLoot.txt");
    public static AcConfig config = new AcConfig();
    public static final Map<String, BzEntry> bzValues = new HashMap<String, BzEntry>();
    public static final List<SbItem> sbItems = new ArrayList<SbItem>();
    public static final Map<String, SbItem> sbItemsById = new HashMap<String, SbItem>();
    public static final Map<String, Double> binValues = new HashMap<String, Double>();
    public static final Set<String> alwaysBuy = new LinkedHashSet<String>();
    public static final Set<String> worthless = new LinkedHashSet<String>();
    public static final String[] DEFAULT_ALWAYS_BUY = new String[]{"NECRON_HANDLE", "DARK_CLAYMORE", "FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR", "SHADOW_FURY", "SHADOW_WARP_SCROLL", "IMPLOSION_SCROLL", "WITHER_SHIELD_SCROLL", "DYE_LIVID"};
    public static final String[] DEFAULT_WORTHLESS = new String[]{"DUNGEON_DISC_5", "DUNGEON_DISC_4", "DUNGEON_DISC_3", "DUNGEON_DISC_2", "DUNGEON_DISC_1", "MAXOR_THE_FISH", "STORM_THE_FISH", "GOLDOR_THE_FISH", "ENCHANTMENT_ULTIMATE_NO_PAIN_NO_GAIN_1", "ENCHANTMENT_ULTIMATE_NO_PAIN_NO_GAIN_2", "ENCHANTMENT_ULTIMATE_NO_PAIN_NO_GAIN_3", "ENCHANTMENT_ULTIMATE_NO_PAIN_NO_GAIN_4", "ENCHANTMENT_ULTIMATE_NO_PAIN_NO_GAIN_5", "ENCHANTMENT_ULTIMATE_COMBO_1", "ENCHANTMENT_ULTIMATE_COMBO_2", "ENCHANTMENT_ULTIMATE_COMBO_3", "ENCHANTMENT_ULTIMATE_COMBO_4", "ENCHANTMENT_ULTIMATE_COMBO_5", "ENCHANTMENT_ULTIMATE_BANK_1", "ENCHANTMENT_ULTIMATE_BANK_2", "ENCHANTMENT_ULTIMATE_BANK_3", "ENCHANTMENT_ULTIMATE_BANK_4", "ENCHANTMENT_ULTIMATE_BANK_5", "ENCHANTMENT_ULTIMATE_JERRY_1", "ENCHANTMENT_ULTIMATE_JERRY_2", "ENCHANTMENT_ULTIMATE_JERRY_3", "ENCHANTMENT_ULTIMATE_JERRY_4", "ENCHANTMENT_ULTIMATE_JERRY_5", "ENCHANTMENT_FEATHER_FALLING_6", "ENCHANTMENT_FEATHER_FALLING_7", "ENCHANTMENT_FEATHER_FALLING_8", "ENCHANTMENT_FEATHER_FALLING_9", "ENCHANTMENT_FEATHER_FALLING_10", "ENCHANTMENT_INFINITE_QUIVER_6", "ENCHANTMENT_INFINITE_QUIVER_7", "ENCHANTMENT_INFINITE_QUIVER_8", "ENCHANTMENT_INFINITE_QUIVER_9", "ENCHANTMENT_INFINITE_QUIVER_10"};

    public static void load() {
        JsonObject obj;
        try {
            Files.createDirectories(CONFIG_DIR, new FileAttribute[0]);
        }
        catch (IOException e) {
            return;
        }
        if (Files.exists(SETTINGS_FILE, new LinkOption[0])) {
            try (BufferedReader r = Files.newBufferedReader(SETTINGS_FILE);){
                AcConfig loaded = (AcConfig)GSON.fromJson((Reader)r, AcConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            }
            catch (Exception e) {
                LOG.warn("[Error 201] Failed to load settings: {}", (Object)e.getMessage());
            }
        }
        if (Files.exists(ALWAYS_BUY_FILE, new LinkOption[0])) {
            AcDataStore.loadList(ALWAYS_BUY_FILE, alwaysBuy);
        } else {
            alwaysBuy.addAll(Arrays.asList(DEFAULT_ALWAYS_BUY));
            AcDataStore.saveList(ALWAYS_BUY_FILE, alwaysBuy);
        }
        if (Files.exists(WORTHLESS_FILE, new LinkOption[0])) {
            AcDataStore.loadList(WORTHLESS_FILE, worthless);
        } else {
            worthless.addAll(Arrays.asList(DEFAULT_WORTHLESS));
            AcDataStore.saveList(WORTHLESS_FILE, worthless);
        }
        if (Files.exists(BZ_VALUES_FILE, new LinkOption[0])) {
            try {
                obj = JsonParser.parseString((String)Files.readString(BZ_VALUES_FILE)).getAsJsonObject();
                bzValues.clear();
                for (Map.Entry e : obj.entrySet()) {
                    JsonObject v = ((JsonElement)e.getValue()).getAsJsonObject();
                    BzEntry entry = new BzEntry();
                    entry.sellOrderValue = v.get("sellOrderValue").getAsDouble();
                    entry.instaSellValue = v.get("instaSellValue").getAsDouble();
                    bzValues.put((String)e.getKey(), entry);
                }
            }
            catch (Exception e) {
                LOG.warn("[Error 202] Failed to load bzValues.json: {}", (Object)e.getMessage());
            }
        }
        if (Files.exists(ITEMS_FILE, new LinkOption[0])) {
            try {
                JsonArray arr = JsonParser.parseString((String)Files.readString(ITEMS_FILE)).getAsJsonArray();
                sbItems.clear();
                sbItemsById.clear();
                for (JsonElement el : arr) {
                    JsonObject obj2 = el.getAsJsonObject();
                    SbItem item = new SbItem();
                    item.id = obj2.has("id") ? obj2.get("id").getAsString() : "";
                    item.name = obj2.has("name") ? obj2.get("name").getAsString() : "";
                    item.tier = obj2.has("tier") ? obj2.get("tier").getAsString() : "COMMON";
                    sbItems.add(item);
                    if (item.id.startsWith("STARRED_")) continue;
                    sbItemsById.put(item.id, item);
                }
            }
            catch (Exception e) {
                LOG.warn("[Error 203] Failed to load items.json: {}", (Object)e.getMessage());
            }
        }
        if (Files.exists(BIN_VALUES_FILE, new LinkOption[0])) {
            try {
                obj = JsonParser.parseString((String)Files.readString(BIN_VALUES_FILE)).getAsJsonObject();
                binValues.clear();
                for (Map.Entry e : obj.entrySet()) {
                    binValues.put((String)e.getKey(), ((JsonElement)e.getValue()).getAsDouble());
                }
            }
            catch (Exception e) {
                LOG.warn("[Error 204] Failed to load binValues.json: {}", (Object)e.getMessage());
            }
        }
    }

    public static void saveConfig() {
        try (BufferedWriter w = Files.newBufferedWriter(SETTINGS_FILE, new OpenOption[0]);){
            GSON.toJson((Object)config, (Appendable)w);
        }
        catch (IOException e) {
            LOG.warn("[Error 205] Failed to save config: {}", (Object)e.getMessage());
        }
    }

    public static void saveAlwaysBuy() {
        AcDataStore.saveList(ALWAYS_BUY_FILE, alwaysBuy);
    }

    public static void saveWorthless() {
        AcDataStore.saveList(WORTHLESS_FILE, worthless);
    }

    private static void loadList(Path path, Set<String> set) {
        try {
            set.clear();
            for (String line : Files.readString(path).split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                set.add(t);
            }
        }
        catch (IOException e) {
            LOG.warn("[Error 206] Failed to load list {}: {}", (Object)path, (Object)e.getMessage());
        }
    }

    private static void saveList(Path path, Set<String> set) {
        try {
            Files.createDirectories(CONFIG_DIR, new FileAttribute[0]);
            Files.writeString(path, (CharSequence)String.join((CharSequence)"\n", set), new OpenOption[0]);
        }
        catch (IOException e) {
            LOG.warn("[Error 207] Failed to save list {}: {}", (Object)path, (Object)e.getMessage());
        }
    }

    public static void appendLootLog(String line) {
        try {
            Files.createDirectories(CONFIG_DIR, new FileAttribute[0]);
            if (Files.exists(LOOT_LOG_FILE, new LinkOption[0])) {
                Files.writeString(LOOT_LOG_FILE, (CharSequence)("\n" + line), StandardOpenOption.APPEND);
            } else {
                Files.writeString(LOOT_LOG_FILE, (CharSequence)line, new OpenOption[0]);
            }
        }
        catch (IOException e) {
            LOG.warn("[Error 208] Failed to append loot log: {}", (Object)e.getMessage());
        }
    }

    public static void updateBzValues(Map<String, BzEntry> newValues) {
        bzValues.clear();
        bzValues.putAll(newValues);
        try {
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, BzEntry> e : newValues.entrySet()) {
                JsonObject v = new JsonObject();
                v.addProperty("sellOrderValue", (Number)e.getValue().sellOrderValue);
                v.addProperty("instaSellValue", (Number)e.getValue().instaSellValue);
                obj.add(e.getKey(), (JsonElement)v);
            }
            Files.createDirectories(CONFIG_DIR, new FileAttribute[0]);
            Files.writeString(BZ_VALUES_FILE, (CharSequence)GSON.toJson((JsonElement)obj), new OpenOption[0]);
        }
        catch (IOException e) {
            LOG.warn("[Error 209] Failed to save bzValues.json: {}", (Object)e.getMessage());
        }
    }

    public static void updateSbItems(List<SbItem> newItems) {
        sbItems.clear();
        sbItems.addAll(newItems);
        sbItemsById.clear();
        for (SbItem item : newItems) {
            if (item.id.startsWith("STARRED_")) continue;
            sbItemsById.put(item.id, item);
        }
        try {
            Files.createDirectories(CONFIG_DIR, new FileAttribute[0]);
            Files.writeString(ITEMS_FILE, (CharSequence)GSON.toJson(newItems), new OpenOption[0]);
        }
        catch (IOException e) {
            LOG.warn("[Error 210] Failed to save items.json: {}", (Object)e.getMessage());
        }
    }

    public static void updateBinValues(Map<String, Double> newBins) {
        binValues.clear();
        binValues.putAll(newBins);
        try {
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, Double> e : newBins.entrySet()) {
                obj.addProperty(e.getKey(), (Number)e.getValue());
            }
            Files.createDirectories(CONFIG_DIR, new FileAttribute[0]);
            Files.writeString(BIN_VALUES_FILE, (CharSequence)GSON.toJson((JsonElement)obj), new OpenOption[0]);
        }
        catch (IOException e) {
            LOG.warn("[Error 211] Failed to save binValues.json: {}", (Object)e.getMessage());
        }
    }

    public static Double getSellPrice(String sbId, boolean useSellOrder) {
        if (worthless.contains(sbId)) {
            return 0.0;
        }
        BzEntry bz = bzValues.get(sbId);
        if (bz != null) {
            return useSellOrder ? bz.sellOrderValue : bz.instaSellValue;
        }
        return binValues.get(sbId);
    }

    public static boolean itemIdExists(String id) {
        return sbItemsById.containsKey(id) || bzValues.containsKey(id);
    }

    public static SbItem getItemApiData(String id) {
        return sbItemsById.get(id);
    }

    public static class BzEntry {
        public double sellOrderValue;
        public double instaSellValue;
    }

    public static class SbItem {
        public String id;
        public String name;
        public String tier;
    }
}

