package de.xclipsen.ircbridge.backend;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class MessageBuffer {
	private final int maxSize;
	private final Deque<MessageEnvelope> messages = new ArrayDeque<>();
	private final AtomicLong ids = new AtomicLong();

	public MessageBuffer(int maxSize) {
		this.maxSize = Math.max(10, maxSize);
	}

	public synchronized void add(String source, String user, String content) {
		messages.addLast(new MessageEnvelope(ids.incrementAndGet(), source, user, content));

		while (messages.size() > maxSize) {
			messages.removeFirst();
		}
	}

	public synchronized List<MessageEnvelope> after(long lastSeenId) {
		List<MessageEnvelope> result = new ArrayList<>();

		for (MessageEnvelope message : messages) {
			if (message.id > lastSeenId) {
				result.add(message);
			}
		}

		return result;
	}
}
