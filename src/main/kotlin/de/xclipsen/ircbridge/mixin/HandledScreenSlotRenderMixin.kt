package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ExperimentationTableFeature
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.ItemStack
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.Slot
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyVariable

@Mixin(HandledScreen::class)
abstract class HandledScreenSlotRenderMixin<T : ScreenHandler> {
	@ModifyVariable(method = ["drawSlot"], at = At("STORE"), ordinal = 0)
	private fun xclipsenReplaceRememberedSuperpairsItem(original: ItemStack, context: DrawContext, slot: Slot): ItemStack {
		return ExperimentationTableFeature.replaceSuperpairsItem(this as HandledScreen<*>, slot, original)
	}
}
