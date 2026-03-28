package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ImagePreviewManager
import net.minecraft.client.gui.hud.ChatHud
import net.minecraft.client.gui.hud.ChatHudLine
import net.minecraft.client.gui.DrawContext
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ChatHud::class)
abstract class ChatHudMixin {
	@Shadow
	protected abstract fun scroll(scroll: Int)

	@Shadow
	protected abstract fun isChatFocused(): Boolean

	@Shadow
	protected var scrolledLines: Int = 0

	@Accessor("visibleMessages")
	protected abstract fun getVisibleMessages(): MutableList<ChatHudLine>

	private var frozenBaseVisibleMessageCount = -1
	private var frozenBaseScrolledLines = 0

	@Inject(method = ["render"], at = [At("HEAD")])
	private fun trackFreezeState(context: DrawContext, currentTick: Int, mouseX: Int, mouseY: Int, focused: Boolean, ci: CallbackInfo) {
		if (ImagePreviewManager.isHoverPreviewActive() && isChatFocused()) {
			if (frozenBaseVisibleMessageCount < 0) {
				frozenBaseVisibleMessageCount = getVisibleMessages().size
				frozenBaseScrolledLines = scrolledLines
			}
		} else {
			frozenBaseVisibleMessageCount = -1
		}
	}

	@Inject(method = ["addVisibleMessage"], at = [At("TAIL")])
	private fun keepViewportStableWhilePreviewing(message: ChatHudLine, ci: CallbackInfo) {
		if (!ImagePreviewManager.isHoverPreviewActive() || !isChatFocused() || frozenBaseVisibleMessageCount < 0) {
			return
		}

		val addedLineCount = getVisibleMessages().size - frozenBaseVisibleMessageCount
		if (addedLineCount > 0) {
			val targetScrolledLines = frozenBaseScrolledLines + addedLineCount
			val delta = targetScrolledLines - scrolledLines
			if (delta != 0) {
				scroll(delta)
			}
		}
	}
}
