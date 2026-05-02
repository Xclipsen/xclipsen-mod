package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ImagePreviewManager
import de.xclipsen.ircbridge.IrcChatTabManager
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.hud.ChatHud
import net.minecraft.client.gui.hud.ChatHudLine
import net.minecraft.client.gui.hud.MessageIndicator
import net.minecraft.text.Style
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(ChatHud::class)
abstract class ChatHudMixin12110 {
	@Shadow
	protected lateinit var client: MinecraftClient

	@Shadow
	protected abstract fun scroll(scroll: Int)

	@Shadow
	protected abstract fun isChatFocused(): Boolean

	@Shadow
	protected var scrolledLines: Int = 0

	@Accessor("visibleMessages")
	protected abstract fun getVisibleMessages(): MutableList<ChatHudLine.Visible>

	private var frozenBaseVisibleMessageCount = -1
	private var frozenBaseScrolledLines = 0

	@Inject(method = ["render"], at = [At("HEAD")], cancellable = true)
	private fun handleRenderProxy(
		context: DrawContext,
		currentTick: Int,
		mouseX: Int,
		mouseY: Int,
		focused: Boolean,
		ci: CallbackInfo,
	) {
		val self = this as ChatHud
		if (IrcChatTabManager.shouldProxy(self, client)) {
			(IrcChatTabManager.ircChatHud(client) as ChatHudInvoker12110).`xclipsen$renderLegacy`(context, currentTick, mouseX, mouseY, focused)
			ci.cancel()
			return
		}

		if (ImagePreviewManager.isHoverPreviewActive() && isChatFocused()) {
			if (frozenBaseVisibleMessageCount < 0) {
				frozenBaseVisibleMessageCount = getVisibleMessages().size
				frozenBaseScrolledLines = scrolledLines
			}
		} else {
			frozenBaseVisibleMessageCount = -1
		}
	}

	@Inject(method = ["mouseClicked"], at = [At("HEAD")], cancellable = true)
	private fun proxyMouseClicked(mouseX: Double, mouseY: Double, cir: CallbackInfoReturnable<Boolean>) {
		val self = this as ChatHud
		if (IrcChatTabManager.shouldProxy(self, client)) {
			cir.returnValue = (IrcChatTabManager.ircChatHud(client) as ChatHudInvoker12110).`xclipsen$mouseClickedLegacy`(mouseX, mouseY)
		}
	}

	@Inject(method = ["getTextStyleAt"], at = [At("HEAD")], cancellable = true)
	private fun proxyGetTextStyleAt(mouseX: Double, mouseY: Double, cir: CallbackInfoReturnable<Style?>) {
		val self = this as ChatHud
		if (IrcChatTabManager.shouldProxy(self, client)) {
			cir.returnValue = (IrcChatTabManager.ircChatHud(client) as ChatHudInvoker12110).`xclipsen$getTextStyleAtLegacy`(mouseX, mouseY)
		}
	}

	@Inject(method = ["getIndicatorAt"], at = [At("HEAD")], cancellable = true)
	private fun proxyGetIndicatorAt(mouseX: Double, mouseY: Double, cir: CallbackInfoReturnable<MessageIndicator?>) {
		val self = this as ChatHud
		if (IrcChatTabManager.shouldProxy(self, client)) {
			cir.returnValue = (IrcChatTabManager.ircChatHud(client) as ChatHudInvoker12110).`xclipsen$getIndicatorAtLegacy`(mouseX, mouseY)
		}
	}

	@Inject(method = ["scroll"], at = [At("HEAD")], cancellable = true)
	private fun proxyScroll(amount: Int, ci: CallbackInfo) {
		val self = this as ChatHud
		if (IrcChatTabManager.shouldProxy(self, client)) {
			IrcChatTabManager.ircChatHud(client).scroll(amount)
			ci.cancel()
		}
	}

	@Inject(method = ["resetScroll"], at = [At("HEAD")], cancellable = true)
	private fun proxyResetScroll(ci: CallbackInfo) {
		val self = this as ChatHud
		if (IrcChatTabManager.shouldProxy(self, client)) {
			IrcChatTabManager.ircChatHud(client).resetScroll()
			ci.cancel()
		}
	}

	@Inject(method = ["getVisibleLineCount"], at = [At("HEAD")], cancellable = true)
	private fun proxyGetVisibleLineCount(cir: CallbackInfoReturnable<Int>) {
		val self = this as ChatHud
		if (IrcChatTabManager.shouldProxy(self, client)) {
			cir.returnValue = IrcChatTabManager.ircChatHud(client).getVisibleLineCount()
		}
	}

	@Inject(method = ["clear"], at = [At("HEAD")])
	private fun clearIrcChat(clearHistory: Boolean, ci: CallbackInfo) {
		val self = this as ChatHud
		if (!IrcChatTabManager.isSecondaryChatHud(self)) {
			IrcChatTabManager.clearIrcChat(clearHistory)
		}
	}

	@Inject(method = ["reset"], at = [At("HEAD")])
	private fun resetIrcChat(ci: CallbackInfo) {
		val self = this as ChatHud
		if (!IrcChatTabManager.isSecondaryChatHud(self)) {
			IrcChatTabManager.resetIrcChat()
		}
	}

	@Inject(method = ["isChatFocused"], at = [At("HEAD")], cancellable = true)
	private fun syncSecondaryFocus(cir: CallbackInfoReturnable<Boolean>) {
		val self = this as ChatHud
		if (IrcChatTabManager.isSecondaryChatHud(self)) {
			cir.returnValue = client.currentScreen is net.minecraft.client.gui.screen.ChatScreen
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
