package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ExperimentationTableFeature
import net.minecraft.item.ItemStack
import net.minecraft.screen.ScreenHandler
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ScreenHandler::class)
abstract class ScreenHandlerSlotUpdateMixin {
	@Inject(method = ["setStackInSlot"], at = [At("RETURN")])
	private fun xclipsenOnSetStackInSlot(slot: Int, revision: Int, stack: ItemStack, ci: CallbackInfo) {
		ExperimentationTableFeature.onSlotStackChanged(this as ScreenHandler, slot, stack)
		ExperimentationTableFeature.onSlotUpdate(this as ScreenHandler)
	}

	@Inject(method = ["updateSlotStacks"], at = [At("RETURN")])
	private fun xclipsenOnUpdateSlotStacks(revision: Int, stacks: List<ItemStack>, cursorStack: ItemStack, ci: CallbackInfo) {
		ExperimentationTableFeature.onSlotUpdate(this as ScreenHandler)
	}
}
