package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.CustomCrosshairFeature
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.hud.InGameHud
import net.minecraft.client.render.RenderTickCounter
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(InGameHud::class)
abstract class InGameHudMixin {
	@Inject(
		method = ["renderCrosshair(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V"],
		at = [At("HEAD")],
		cancellable = true,
	)
	private fun xclipsenCancelVanillaCrosshair(
		context: DrawContext,
		tickCounter: RenderTickCounter,
		ci: CallbackInfo,
	) {
		if (CustomCrosshairFeature.shouldOverrideVanilla()) {
			ci.cancel()
		}
	}
}
