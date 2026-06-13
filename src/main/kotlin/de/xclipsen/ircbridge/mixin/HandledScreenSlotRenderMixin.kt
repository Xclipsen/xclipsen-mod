package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ExperimentationTableFeature
import de.xclipsen.ircbridge.PartyFinderFeature
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.ItemStack
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.Slot
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.ModifyVariable
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(HandledScreen::class)
abstract class HandledScreenSlotRenderMixin<T : ScreenHandler> {
	@Inject(method = ["drawSlot"], at = [At("HEAD")])
	private fun xclipsenBeforeDrawSlot(context: DrawContext, slot: Slot, mouseX: Int, mouseY: Int, ci: CallbackInfo) {
		PartyFinderFeature.beforeDrawSlot(context, this as HandledScreen<*>, slot)
	}

	@ModifyVariable(method = ["drawSlot"], at = At("STORE"), ordinal = 0)
	private fun xclipsenReplaceRememberedSuperpairsItem(original: ItemStack, context: DrawContext, slot: Slot): ItemStack {
		return ExperimentationTableFeature.replaceSuperpairsItem(this as HandledScreen<*>, slot, original)
	}

	@Inject(method = ["drawSlots"], at = [At("RETURN")])
	private fun xclipsenAfterDrawSlots(context: DrawContext, mouseX: Int, mouseY: Int, ci: CallbackInfo) {
		PartyFinderFeature.afterDrawSlots(context, this as HandledScreen<*>)
	}
}
