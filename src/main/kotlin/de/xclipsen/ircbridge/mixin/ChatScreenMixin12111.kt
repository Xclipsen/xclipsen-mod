package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.IrcChatTabManager
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.hud.ChatHud
import net.minecraft.client.gui.hud.InGameHud
import net.minecraft.client.gui.screen.ChatScreen
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

@Mixin(ChatScreen::class)
abstract class ChatScreenMixin12111 {
	@Redirect(
		method = ["removed", "keyPressed", "mouseScrolled", "mouseClicked", "setChatFromHistory", "render"],
		at = At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;getChatHud()Lnet/minecraft/client/gui/hud/ChatHud;"),
	)
	private fun redirectActiveChatHud(inGameHud: InGameHud): ChatHud {
		return IrcChatTabManager.activeChatHud(MinecraftClient.getInstance())
	}
}
