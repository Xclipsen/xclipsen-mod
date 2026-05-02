package de.xclipsen.ircbridge.mixin

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.hud.ChatHud
import net.minecraft.client.gui.hud.MessageIndicator
import net.minecraft.text.Style
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(ChatHud::class)
interface ChatHudInvoker12110 {
	@Invoker("render")
	fun `xclipsen$renderLegacy`(context: DrawContext, currentTick: Int, mouseX: Int, mouseY: Int, focused: Boolean)

	@Invoker("mouseClicked")
	fun `xclipsen$mouseClickedLegacy`(mouseX: Double, mouseY: Double): Boolean

	@Invoker("getTextStyleAt")
	fun `xclipsen$getTextStyleAtLegacy`(mouseX: Double, mouseY: Double): Style?

	@Invoker("getIndicatorAt")
	fun `xclipsen$getIndicatorAtLegacy`(mouseX: Double, mouseY: Double): MessageIndicator?
}
