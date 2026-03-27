package de.xclipsen.ircbridge;

public final class BridgeConfig {
	public String bridgeMode = "direct";
	public String discordToken = "";
	public String guildId = "";
	public String channelId = "";
	public String backendBaseUrl = "http://127.0.0.1:8765";
	public String backendAuthToken = "change-me";
	public long backendPollIntervalMs = 2000;
	public boolean mirrorMinecraftChat = true;
	public boolean mirrorJoinLeave = true;
	public String minecraftToDiscordFormat = "**%player%**: %message%";
	public String discordToMinecraftFormat = "[Discord] <%user%> %message%";
	public String ircCommandFormat = "[IRC] <%player%> %message%";
}
