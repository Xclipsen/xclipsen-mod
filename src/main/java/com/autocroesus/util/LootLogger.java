/*
 * Decompiled with CFR 0.152.
 */
package com.autocroesus.util;

import com.autocroesus.config.AcDataStore;
import com.autocroesus.util.ItemParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LootLogger {
    private static int getScoreFromChestCount(int chestCount) {
        return switch (chestCount) {
            case 3 -> 229;
            case 4 -> 230;
            case 5 -> 270;
            case 6 -> 300;
            default -> 0;
        };
    }

    public static void logLoot(String floor, List<ItemParser.ChestInfo> chests, int chestCount) {
        long totalCost = chests.stream().mapToLong(c -> c.cost).sum();
        int score = LootLogger.getScoreFromChestCount(chestCount);
        HashMap<String, Integer> combined = new HashMap<String, Integer>();
        for (ItemParser.ChestInfo chest : chests) {
            for (ItemParser.RewardItem item : chest.items) {
                combined.merge(item.id, item.qty, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(floor).append(' ').append(score).append(' ').append(totalCost);
        for (Map.Entry e : combined.entrySet()) {
            sb.append(' ').append((String)e.getKey()).append(':').append(e.getValue());
        }
        AcDataStore.appendLootLog(sb.toString());
    }
}

