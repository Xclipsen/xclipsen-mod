/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.text.Text
 */
package com.autocroesus.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public class ChatUtil {
    public static void msg(String message) {
        ChatUtil.msg((Text)Text.literal((String)message));
    }

    public static void msg(Text component) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(component, false);
        } else if (mc.inGameHud != null) {
            mc.inGameHud.getChatHud().addMessage(component);
        }
    }
}

