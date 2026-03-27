package de.xclipsen.ircbridge.backend;

import java.util.List;

public record MessagesResponse(List<MessageEnvelope> messages) {
}
