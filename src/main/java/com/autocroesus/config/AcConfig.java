/*
 * Decompiled with CFR 0.152.
 */
package com.autocroesus.config;

import java.util.ArrayList;
import java.util.List;

public class AcConfig {
    public long lastApiUpdate = 0L;
    public int minClickDelay = 500;
    public boolean noClick = false;
    public boolean useKismets = true;
    public long kismetMinProfit = 2000000L;
    public List<String> kismetFloors = new ArrayList<String>(List.of("M7"));
    public boolean useChestKeys = false;
    public long chestKeyMinProfit = 200000L;
}

