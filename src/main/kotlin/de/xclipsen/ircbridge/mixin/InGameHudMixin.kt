package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.CustomCrosshairFeature
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.Gui
import net.minecraft.client.DeltaTracker
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Gui::class)
abstract class InGameHudMixin {
	@Inject(
		method = ["extractCrosshair(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"],
		at = [At("HEAD")],
		cancellable = true,
	)
	private fun xclipsenCancelVanillaCrosshair(
		context: GuiGraphicsExtractor,
		tickCounter: DeltaTracker,
		ci: CallbackInfo,
	) {
		if (CustomCrosshairFeature.shouldOverrideVanilla()) {
			ci.cancel()
		}
	}
}
