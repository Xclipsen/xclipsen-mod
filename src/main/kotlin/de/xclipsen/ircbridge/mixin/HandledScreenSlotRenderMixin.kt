package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ExperimentationTableFeature
import de.xclipsen.ircbridge.PartyFinderFeature
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.ModifyVariable
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(AbstractContainerScreen::class)
abstract class HandledScreenSlotRenderMixin<T : AbstractContainerMenu> {
	@Inject(method = ["extractSlot"], at = [At("HEAD")])
	private fun xclipsenBeforeDrawSlot(context: GuiGraphicsExtractor, slot: Slot, mouseX: Int, mouseY: Int, ci: CallbackInfo) {
		PartyFinderFeature.beforeDrawSlot(context, this as AbstractContainerScreen<*>, slot)
	}

	@ModifyVariable(method = ["extractSlot"], at = At("STORE"), ordinal = 0)
	private fun xclipsenReplaceRememberedSuperpairsItem(original: ItemStack, context: GuiGraphicsExtractor, slot: Slot): ItemStack {
		return ExperimentationTableFeature.replaceSuperpairsItem(this as AbstractContainerScreen<*>, slot, original)
	}

	@Inject(method = ["extractSlots"], at = [At("RETURN")])
	private fun xclipsenAfterDrawSlots(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, ci: CallbackInfo) {
		PartyFinderFeature.afterDrawSlots(context, this as AbstractContainerScreen<*>)
	}
}
