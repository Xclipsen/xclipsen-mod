package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ImagePreviewManager
import de.xclipsen.ircbridge.IrcChatTabManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.multiplayer.chat.GuiMessage
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(ChatComponent::class)
abstract class ChatHudMixin12111 {
	@Shadow
	protected lateinit var minecraft: Minecraft

	@Shadow
	protected abstract fun scrollChat(scroll: Int)

	@Shadow
	protected abstract fun isChatFocused(): Boolean

	@Shadow
	protected var chatScrollbarPos: Int = 0

	@Accessor("trimmedMessages")
	protected abstract fun getTrimmedMessages(): MutableList<GuiMessage.Line>

	private var frozenBaseVisibleMessageCount = -1
	private var frozenBaseScrolledLines = 0

	@Inject(method = ["extractRenderState"], at = [At("HEAD")], cancellable = true)
	private fun handleRenderProxy(
		context: GuiGraphicsExtractor,
		textRenderer: Font,
		currentTick: Int,
		mouseX: Int,
		mouseY: Int,
		displayMode: ChatComponent.DisplayMode,
		insertionClickMode: Boolean,
		ci: CallbackInfo,
	) {
		val self = this as ChatComponent
		if (IrcChatTabManager.shouldProxy(self, minecraft)) {
			(IrcChatTabManager.ircChatHud(minecraft) as ChatHudInvoker12111).`xclipsen$render12111`(
				context,
				textRenderer,
				currentTick,
				mouseX,
				mouseY,
				displayMode,
				insertionClickMode,
			)
			ci.cancel()
			return
		}

		if (ImagePreviewManager.isHoverPreviewActive() && isChatFocused()) {
			if (frozenBaseVisibleMessageCount < 0) {
				frozenBaseVisibleMessageCount = getTrimmedMessages().size
				frozenBaseScrolledLines = chatScrollbarPos
			}
		} else {
			frozenBaseVisibleMessageCount = -1
		}
	}

	@Inject(method = ["scrollChat"], at = [At("HEAD")], cancellable = true)
	private fun proxyScroll(amount: Int, ci: CallbackInfo) {
		val self = this as ChatComponent
		if (IrcChatTabManager.shouldProxy(self, minecraft)) {
			IrcChatTabManager.ircChatHud(minecraft).scrollChat(amount)
			ci.cancel()
		}
	}

	@Inject(method = ["resetChatScroll"], at = [At("HEAD")], cancellable = true)
	private fun proxyResetScroll(ci: CallbackInfo) {
		val self = this as ChatComponent
		if (IrcChatTabManager.shouldProxy(self, minecraft)) {
			IrcChatTabManager.ircChatHud(minecraft).resetChatScroll()
			ci.cancel()
		}
	}

	@Inject(method = ["getLinesPerPage"], at = [At("HEAD")], cancellable = true)
	private fun proxyGetVisibleLineCount(cir: CallbackInfoReturnable<Int>) {
		val self = this as ChatComponent
		if (IrcChatTabManager.shouldProxy(self, minecraft)) {
			cir.returnValue = IrcChatTabManager.ircChatHud(minecraft).linesPerPage
		}
	}

	@Inject(method = ["clearMessages"], at = [At("HEAD")])
	private fun clearIrcChat(clearHistory: Boolean, ci: CallbackInfo) {
		val self = this as ChatComponent
		if (!IrcChatTabManager.isSecondaryChatHud(self)) {
			IrcChatTabManager.clearIrcChat(clearHistory)
		}
	}

	@Inject(method = ["rescaleChat"], at = [At("HEAD")])
	private fun resetIrcChat(ci: CallbackInfo) {
		val self = this as ChatComponent
		if (!IrcChatTabManager.isSecondaryChatHud(self)) {
			IrcChatTabManager.resetIrcChat()
		}
	}

	@Inject(method = ["isChatFocused"], at = [At("HEAD")], cancellable = true)
	private fun syncSecondaryFocus(cir: CallbackInfoReturnable<Boolean>) {
		val self = this as ChatComponent
		if (IrcChatTabManager.isSecondaryChatHud(self)) {
			cir.returnValue = minecraft.screen is net.minecraft.client.gui.screens.ChatScreen
		}
	}

	@Inject(method = ["addMessageToDisplayQueue"], at = [At("TAIL")])
	private fun keepViewportStableWhilePreviewing(message: GuiMessage, ci: CallbackInfo) {
		if (!ImagePreviewManager.isHoverPreviewActive() || !isChatFocused() || frozenBaseVisibleMessageCount < 0) {
			return
		}

		val addedLineCount = getTrimmedMessages().size - frozenBaseVisibleMessageCount
		if (addedLineCount > 0) {
			val targetScrolledLines = frozenBaseScrolledLines + addedLineCount
			val delta = targetScrolledLines - chatScrollbarPos
			if (delta != 0) {
				scrollChat(delta)
			}
		}
	}
}
