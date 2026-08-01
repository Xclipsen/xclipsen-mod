package de.xclipsen.ircbridge.mixin

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ChatComponent
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(ChatComponent::class)
interface ChatHudInvoker12111 {
	@Invoker("extractRenderState")
	fun `xclipsen$render12111`(
		context: GuiGraphicsExtractor,
		textRenderer: Font,
		currentTick: Int,
		mouseX: Int,
		mouseY: Int,
		displayMode: ChatComponent.DisplayMode,
		insertionClickMode: Boolean,
	)
}
