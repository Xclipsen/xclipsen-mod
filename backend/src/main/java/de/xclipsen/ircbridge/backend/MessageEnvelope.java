package de.xclipsen.ircbridge.backend;

public final class MessageEnvelope {
	public long id;
	public String source = "discord";
	public String user = "";
	public String content = "";

	public MessageEnvelope() {
	}

	public MessageEnvelope(long id, String source, String user, String content) {
		this.id = id;
		this.source = source;
		this.user = user;
		this.content = content;
	}
}
