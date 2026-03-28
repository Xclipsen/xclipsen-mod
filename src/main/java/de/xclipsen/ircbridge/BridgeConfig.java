package de.xclipsen.ircbridge;

public final class BridgeConfig {
	public String backendBaseUrl = "http://127.0.0.1:8765";
	public String backendAuthToken = "change-me";
	public long backendPollIntervalMs = 2000;
	public String linkedDiscordDisplayName = "";
	public boolean auctionHouseUnderbidEnabled = true;
	public boolean auctionHouseCreateBinAutoCopyEnabled = true;
	public boolean autoExperimentsEnabled = false;
	public long autoExperimentsClickDelayMs = 200L;
	public long autoExperimentsDelayVarianceMs = 50L;
	public boolean autoExperimentsAutoClose = true;
	public int autoExperimentsSerumCount = 0;
	public boolean autoExperimentsGetMaxXp = false;
	public String discordToMinecraftFormat = "[Discord] <%user%> %message%";
	public String ircCommandFormat = "[IRC] <%player%> %message%";
	public String eventPingFormat = "[Event] %event%: %message%";
}
