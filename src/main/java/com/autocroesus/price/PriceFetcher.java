/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonArray
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  com.google.gson.JsonParser
 */
package com.autocroesus.price;

import com.autocroesus.config.AcDataStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class PriceFetcher {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static CompletableFuture<String> fetchUrl(String url) {
        return HTTP.sendAsync(HttpRequest.newBuilder().uri(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).thenApply(HttpResponse::body);
    }

    private static double averageTopN(JsonArray orders, int n) {
        int count = Math.min(n, orders.size());
        double sum = 0.0;
        for (int i = 0; i < count; ++i) {
            sum += orders.get(i).getAsJsonObject().get("pricePerUnit").getAsDouble();
        }
        return sum / (double)count;
    }

    public static CompletableFuture<Void> updatePrices() {
        CompletableFuture<String> bzFuture = PriceFetcher.fetchUrl("https://api.hypixel.net/skyblock/bazaar");
        CompletableFuture<String> itemsFuture = PriceFetcher.fetchUrl("https://api.hypixel.net/v2/resources/skyblock/items");
        CompletableFuture<String> binFuture = PriceFetcher.fetchUrl("https://moulberry.codes/lowestbin.json");
        return CompletableFuture.allOf(bzFuture, itemsFuture, binFuture).thenAccept(v -> {
            JsonObject resp;
            try {
                resp = JsonParser.parseString((String)((String)bzFuture.join())).getAsJsonObject();
                if (!resp.get("success").getAsBoolean()) {
                    throw new RuntimeException("[Error 301] Bazaar API error: " + resp.get("cause").getAsString());
                }
                HashMap<String, AcDataStore.BzEntry> newBz = new HashMap<String, AcDataStore.BzEntry>();
                JsonObject products = resp.getAsJsonObject("products");
                for (Map.Entry entry : products.entrySet()) {
                    JsonObject info = ((JsonElement)entry.getValue()).getAsJsonObject();
                    JsonObject status = info.getAsJsonObject("quick_status");
                    JsonArray buyOrders = info.getAsJsonArray("buy_summary");
                    JsonArray sellOrders = info.getAsJsonArray("sell_summary");
                    double sellOrderValue = buyOrders.isEmpty() ? status.get("buyPrice").getAsDouble() : PriceFetcher.averageTopN(buyOrders, 5);
                    double instaSellValue = sellOrders.isEmpty() ? status.get("sellPrice").getAsDouble() : PriceFetcher.averageTopN(sellOrders, 5);
                    AcDataStore.BzEntry bz = new AcDataStore.BzEntry();
                    bz.sellOrderValue = sellOrderValue;
                    bz.instaSellValue = instaSellValue;
                    newBz.put((String)entry.getKey(), bz);
                }
                AcDataStore.updateBzValues(newBz);
            }
            catch (Exception e) {
                throw new RuntimeException("[Error 302] Failed to process Bazaar data: " + e.getMessage(), e);
            }
            try {
                resp = JsonParser.parseString((String)((String)itemsFuture.join())).getAsJsonObject();
                if (!resp.get("success").getAsBoolean()) {
                    throw new RuntimeException("[Error 303] Items API error: " + resp.get("cause").getAsString());
                }
                ArrayList<AcDataStore.SbItem> newItems = new ArrayList<AcDataStore.SbItem>();
                for (JsonElement el : resp.getAsJsonArray("items")) {
                    JsonObject obj = el.getAsJsonObject();
                    AcDataStore.SbItem item = new AcDataStore.SbItem();
                    item.id = obj.has("id") ? obj.get("id").getAsString() : "";
                    item.name = obj.has("name") ? obj.get("name").getAsString() : "";
                    item.tier = obj.has("tier") ? obj.get("tier").getAsString() : "COMMON";
                    newItems.add(item);
                }
                AcDataStore.updateSbItems(newItems);
            }
            catch (Exception e) {
                throw new RuntimeException("[Error 304] Failed to process Items data: " + e.getMessage(), e);
            }
            try {
                resp = JsonParser.parseString((String)((String)binFuture.join())).getAsJsonObject();
                HashMap<String, Double> newBins = new HashMap<String, Double>();
                for (Map.Entry e : resp.entrySet()) {
                    newBins.put((String)e.getKey(), ((JsonElement)e.getValue()).getAsDouble());
                }
                AcDataStore.updateBinValues(newBins);
            }
            catch (Exception e) {
                throw new RuntimeException("[Error 305] Failed to process BIN data: " + e.getMessage(), e);
            }
        });
    }
}

