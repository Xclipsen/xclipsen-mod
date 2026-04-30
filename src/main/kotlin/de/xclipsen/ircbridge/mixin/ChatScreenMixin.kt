package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ImagePreviewManager
import de.xclipsen.ircbridge.IrcChatTabManager
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.DrawnTextConsumer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.hud.ChatHud
import net.minecraft.client.gui.hud.InGameHud
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.text.Style
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ChatScreen::class)
abstract class ChatScreenMixin {
	@Invoker("shouldInsert")
	protected abstract fun callShouldInsert(): Boolean

	@Invoker("handleClickEvent")
	protected abstract fun callHandleClickEvent(style: Style, shouldInsert: Boolean): Boolean

	@Redirect(
		method = ["removed", "keyPressed", "mouseScrolled", "mouseClicked", "setChatFromHistory", "render"],
		at = At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;getChatHud()Lnet/minecraft/client/gui/hud/ChatHud;"),
	)
	private fun redirectActiveChatHud(inGameHud: InGameHud): ChatHud {
		return IrcChatTabManager.activeChatHud(MinecraftClient.getInstance())
	}

	@Inject(method = ["render"], at = [At("TAIL")])
	private fun renderImagePreview(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float, ci: CallbackInfo) {
		val client = MinecraftClient.getInstance()
		val clickHandler = DrawnTextConsumer.ClickHandler(client.textRenderer, mouseX, mouseY).insert(callShouldInsert())
		IrcChatTabManager.activeChatHud(client).render(clickHandler, client.window.scaledHeight, client.inGameHud.ticks, true)
		ImagePreviewManager.renderHoverPreview(context, clickHandler.style, mouseX, mouseY)
	}
}
