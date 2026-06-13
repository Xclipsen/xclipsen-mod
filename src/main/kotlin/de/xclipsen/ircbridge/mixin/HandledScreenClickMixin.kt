package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.PartyFinderFeature
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(HandledScreen::class)
abstract class HandledScreenClickMixin<T : ScreenHandler> {
	@Inject(
		method = ["onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V"],
		at = [At("HEAD")],
		cancellable = true,
	)
	private fun xclipsenOnMouseClick(slot: Slot?, slotId: Int, button: Int, actionType: SlotActionType, ci: CallbackInfo) {
		if (PartyFinderFeature.onSlotClick(this as HandledScreen<*>, slot, button, actionType)) {
			ci.cancel()
		}
	}
}
