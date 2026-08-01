package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.IrcChatTabManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.screens.ChatScreen
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

@Mixin(ChatScreen::class)
abstract class ChatScreenMixin12111 {
	@Redirect(
		method = ["init", "removed", "keyPressed", "mouseScrolled", "mouseClicked", "moveInHistory", "extractRenderState", "handleChatInput"],
		at = At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;getChat()Lnet/minecraft/client/gui/components/ChatComponent;"),
	)
	private fun redirectActiveChatHud(inGameHud: Gui): ChatComponent {
		return IrcChatTabManager.activeChatHud(Minecraft.getInstance())
	}
}
