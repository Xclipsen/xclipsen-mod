package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ImagePreviewManager
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.text.Style
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ChatScreen::class)
abstract class ChatScreenMixin {
	@Invoker("getTextStyleAt")
	protected abstract fun callGetTextStyleAt(mouseX: Double, mouseY: Double): Style?

	@Inject(method = ["render"], at = [At("TAIL")])
	private fun renderImagePreview(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float, ci: CallbackInfo) {
		ImagePreviewManager.renderHoverPreview(context, callGetTextStyleAt(mouseX.toDouble(), mouseY.toDouble()), mouseX, mouseY)
	}
}
