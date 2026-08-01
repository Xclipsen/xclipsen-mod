/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.network.chat.Component
 */
package com.autocroesus.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ChatUtil {
    public static void msg(String message) {
        ChatUtil.msg((Component)Component.literal((String)message));
    }

    public static void msg(Component component) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(component);
        } else if (mc.gui != null) {
            mc.gui.getChat().addClientSystemMessage(component);
        }
    }
}
