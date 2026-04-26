/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.fabricmc.api.ClientModInitializer
 */
package com.autocroesus;

import com.autocroesus.command.AcCommand;
import com.autocroesus.config.AcDataStore;
import com.autocroesus.feature.CroesusClaimer;
import net.fabricmc.api.ClientModInitializer;

public class AutoCroesus
implements ClientModInitializer {
    private static boolean initialized = false;

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        AcDataStore.load();
        CroesusClaimer.register();
        AcCommand.register(null);
    }

    public void onInitializeClient() {
        AutoCroesus.initialize();
    }
}
