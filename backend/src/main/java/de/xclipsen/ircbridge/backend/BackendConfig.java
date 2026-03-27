package de.xclipsen.ircbridge.backend;

public final class BackendConfig {
	public String discordToken = "";
	public String guildId = "";
	public String channelId = "";
	public String authToken = "change-me";
	public String bindHost = "0.0.0.0";
	public int listenPort = 8765;
	public int maxBufferedMessages = 250;
	public String minecraftToDiscordFormat = "**%player%**: %message%";
}
