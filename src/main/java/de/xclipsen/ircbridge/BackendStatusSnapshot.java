package de.xclipsen.ircbridge;

public record BackendStatusSnapshot(
	String state,
	int lastHttpStatus,
	long lastSuccessAt,
	long lastPollAt,
	long lastMessageAt,
	String lastError
) {
}
