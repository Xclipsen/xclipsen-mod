package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.PartyFinderFeature
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.inventory.ContainerInput
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(AbstractContainerScreen::class)
abstract class HandledScreenClickMixin<T : AbstractContainerMenu> {
	@Inject(
		method = ["slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V"],
		at = [At("HEAD")],
		cancellable = true,
	)
	private fun xclipsenOnMouseClick(slot: Slot?, slotId: Int, button: Int, actionType: ContainerInput, ci: CallbackInfo) {
		if (PartyFinderFeature.onSlotClick(this as AbstractContainerScreen<*>, slot, button, actionType)) {
			ci.cancel()
		}
	}
}
