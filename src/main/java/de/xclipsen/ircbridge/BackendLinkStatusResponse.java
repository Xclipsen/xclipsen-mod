package de.xclipsen.ircbridge;

import java.util.ArrayList;
import java.util.List;

public final class BackendLinkStatusResponse {
	public boolean linked;
	public String discordUserId = "";
	public String discordDisplayName = "";
	public String playerName = "";
	public String error = "";
	public List<String> minecraftUsernames = new ArrayList<>();
}
