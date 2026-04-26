package com.autocroesus;

import de.xclipsen.ircbridge.XclipsenIrcBridgeClient;

public final class XclipsenAutoCroesusModule {
    private XclipsenAutoCroesusModule() {
    }

    public static boolean isEnabled() {
        XclipsenIrcBridgeClient mod = XclipsenIrcBridgeClient.getInstance();
        return mod != null && mod.config().autoCroesusModuleEnabled;
    }

    public static String disabledMessage() {
        return "\u00a7cAutoCroesus is disabled. Enable the AutoCroesus module in /xclipsen config.";
    }
}
