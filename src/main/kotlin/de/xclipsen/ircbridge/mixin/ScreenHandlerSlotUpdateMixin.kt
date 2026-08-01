package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ExperimentationTableFeature
import de.xclipsen.ircbridge.SlayerFeature
import net.minecraft.world.item.ItemStack
import net.minecraft.world.inventory.AbstractContainerMenu
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(AbstractContainerMenu::class)
abstract class ScreenHandlerSlotUpdateMixin {
	@Inject(method = ["setItem"], at = [At("RETURN")])
	private fun xclipsenOnSetStackInSlot(slot: Int, revision: Int, stack: ItemStack, ci: CallbackInfo) {
		ExperimentationTableFeature.onSlotStackChanged(this as AbstractContainerMenu, slot, stack)
		ExperimentationTableFeature.onSlotUpdate(this as AbstractContainerMenu)
		SlayerFeature.onSlotUpdate(this as AbstractContainerMenu)
	}

	@Inject(method = ["initializeContents"], at = [At("RETURN")])
	private fun xclipsenOnUpdateSlotStacks(revision: Int, stacks: List<ItemStack>, cursorStack: ItemStack, ci: CallbackInfo) {
		ExperimentationTableFeature.onSlotUpdate(this as AbstractContainerMenu)
		SlayerFeature.onSlotUpdate(this as AbstractContainerMenu)
	}
}
