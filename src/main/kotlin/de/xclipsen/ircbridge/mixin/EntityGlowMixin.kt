package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.GalateaEntityHighlightFeature
import de.xclipsen.ircbridge.ShulkerGlowFeature
import net.minecraft.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Entity::class)
abstract class EntityGlowMixin {
	@Inject(method = ["isGlowing"], at = [At("HEAD")], cancellable = true)
	private fun forceEntityGlow(cir: CallbackInfoReturnable<Boolean>) {
		val entity = this as Entity
		if (GalateaEntityHighlightFeature.shouldGlow(entity) || ShulkerGlowFeature.shouldGlow(entity)) {
			cir.returnValue = true
		}
	}

	@Inject(method = ["getTeamColorValue"], at = [At("HEAD")], cancellable = true)
	private fun forceEntityGlowColor(cir: CallbackInfoReturnable<Int>) {
		val entity = this as Entity
		// Galatea-specific highlight takes priority over the general shulker glow
		GalateaEntityHighlightFeature.colorValue(entity)?.let {
			cir.returnValue = it
			return
		}
		ShulkerGlowFeature.colorValue(entity)?.let {
			cir.returnValue = it
		}
	}

	@Inject(method = ["shouldRender(D)Z"], at = [At("HEAD")], cancellable = true)
	private fun forceEntityRenderDistance(distance: Double, cir: CallbackInfoReturnable<Boolean>) {
		val entity = this as Entity
		if (GalateaEntityHighlightFeature.shouldGlow(entity) || ShulkerGlowFeature.shouldGlow(entity)) {
			cir.returnValue = true
		}
	}
}
