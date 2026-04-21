package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ShulkerGlowFeature
import net.minecraft.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Entity::class)
abstract class EntityGlowMixin {
	@Inject(method = ["isGlowing"], at = [At("HEAD")], cancellable = true)
	private fun forceShulkerGlow(cir: CallbackInfoReturnable<Boolean>) {
		if (ShulkerGlowFeature.shouldGlow(this as Entity)) {
			cir.returnValue = true
		}
	}
}
