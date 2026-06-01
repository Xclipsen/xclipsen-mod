package de.xclipsen.ircbridge.mixin

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.hud.ChatHud
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(ChatHud::class)
interface ChatHudInvoker12111 {
	@Invoker("render")
	fun `xclipsen$render12111`(
		context: DrawContext,
		textRenderer: TextRenderer,
		currentTick: Int,
		mouseX: Int,
		mouseY: Int,
		focused: Boolean,
		refresh: Boolean,
	)
}
