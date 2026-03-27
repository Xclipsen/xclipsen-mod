package de.xclipsen.ircbridge.backend;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.List;

public final class DiscordGateway extends ListenerAdapter {
	private final Logger logger;
	private final BackendConfig config;
	private final MessageBuffer buffer;

	private JDA jda;

	public DiscordGateway(Logger logger, BackendConfig config, MessageBuffer buffer) {
		this.logger = logger;
		this.config = config;
		this.buffer = buffer;
	}

	public void start() throws InterruptedException {
		if (config.discordToken.isBlank() || config.channelId.isBlank()) {
			throw new IllegalStateException("discordToken and channelId must be configured");
		}

		jda = JDABuilder.createLight(config.discordToken, EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
			.addEventListeners(this)
			.build()
			.awaitReady();

		logger.info("Discord backend connected.");
	}

	public void stop() {
		if (jda != null) {
			jda.shutdownNow();
			jda = null;
		}
	}

	public void sendMinecraftMessage(OutgoingMinecraftMessage message) {
		if (jda == null) {
			return;
		}

		TextChannel channel = jda.getTextChannelById(config.channelId);

		if (channel == null) {
			logger.warn("Configured Discord channel {} was not found.", config.channelId);
			return;
		}

		String content = "status".equalsIgnoreCase(message.type)
			? sanitize(message.message)
			: TextFormatter.apply(
				config.minecraftToDiscordFormat,
				"%player%", sanitize(message.playerName),
				"%message%", sanitize(message.message)
			);

		channel.sendMessage(content)
			.setAllowedMentions(List.of())
			.queue(
				success -> {
				},
				error -> logger.warn("Failed to send message to Discord", error)
			);
	}

	@Override
	public void onMessageReceived(MessageReceivedEvent event) {
		if (event.getAuthor().isBot() || event.isWebhookMessage()) {
			return;
		}

		if (!event.isFromGuild()) {
			return;
		}

		if (!event.getChannel().getId().equals(config.channelId)) {
			return;
		}

		if (!config.guildId.isBlank() && !event.getGuild().getId().equals(config.guildId)) {
			return;
		}

		String content = extractMessage(event.getMessage());

		if (content.isBlank()) {
			return;
		}

		buffer.add("discord", event.getAuthor().getEffectiveName(), content);
	}

	private static String extractMessage(Message message) {
		String content = message.getContentDisplay().trim();

		if (!content.isEmpty()) {
			return content;
		}

		if (!message.getAttachments().isEmpty()) {
			return "[Attachment] " + message.getAttachments().getFirst().getFileName();
		}

		return "";
	}

	private static String sanitize(String value) {
		return value.replace("@", "@\u200B");
	}
}
