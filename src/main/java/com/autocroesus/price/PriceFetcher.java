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

    private static CompletableFuture<HttpResponse<String>> fetchUrl(String url) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")
            .header("User-Agent", "xclipsen-mod/0.5.7")
            .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject parseJsonObject(HttpResponse<String> response, String source) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(source + " returned HTTP " + response.statusCode() + ": " + PriceFetcher.preview(response.body()));
        }
        String body = response.body() == null ? "" : response.body().trim();
        if (body.startsWith(")]}'")) {
            int newlineIndex = body.indexOf('\n');
            body = newlineIndex >= 0 ? body.substring(newlineIndex + 1).trim() : "";
        }
        if (!body.startsWith("{")) {
            throw new IllegalStateException(source + " returned non-JSON content: " + PriceFetcher.preview(body));
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static String preview(String body) {
        if (body == null) {
            return "<empty>";
        }
        String normalized = body
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim();
        if (normalized.isEmpty()) {
            return "<empty>";
        }
        if (normalized.length() > 160) {
            normalized = normalized.substring(0, 160) + "...";
        }
        return normalized;
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
        CompletableFuture<HttpResponse<String>> bzFuture = PriceFetcher.fetchUrl("https://api.hypixel.net/skyblock/bazaar");
        CompletableFuture<HttpResponse<String>> itemsFuture = PriceFetcher.fetchUrl("https://api.hypixel.net/v2/resources/skyblock/items");
        CompletableFuture<HttpResponse<String>> binFuture = PriceFetcher.fetchUrl("https://moulberry.codes/lowestbin.json");
        return CompletableFuture.allOf(bzFuture, itemsFuture, binFuture).thenAccept(v -> {
            JsonObject resp;
            try {
                resp = PriceFetcher.parseJsonObject((HttpResponse<String>)bzFuture.join(), "Bazaar API");
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
                resp = PriceFetcher.parseJsonObject((HttpResponse<String>)itemsFuture.join(), "Items API");
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
                resp = PriceFetcher.parseJsonObject((HttpResponse<String>)binFuture.join(), "Lowest BIN API");
                HashMap<String, Double> newBins = new HashMap<String, Double>();
                for (Map.Entry e : resp.entrySet()) {
                    newBins.put((String)e.getKey(), ((JsonElement)e.getValue()).getAsDouble());
                }
                AcDataStore.updateBinValues(newBins);
            }
            catch (Exception e) {
                if (!AcDataStore.binValues.isEmpty()) {
                    return;
                }
                throw new RuntimeException("[Error 305] Failed to process BIN data: " + e.getMessage(), e);
            }
        });
    }
}
