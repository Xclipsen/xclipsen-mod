package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.CorpseEspFeature
import de.xclipsen.ircbridge.M5Feature
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
		val entity = this as Entity
		if (ShulkerGlowFeature.shouldGlow(entity) || CorpseEspFeature.shouldGlow(entity) || M5Feature.shouldGlow(entity)) {
			cir.returnValue = true
		}
	}

	@Inject(method = ["getTeamColorValue"], at = [At("HEAD")], cancellable = true)
	private fun forceShulkerGlowColor(cir: CallbackInfoReturnable<Int>) {
		val entity = this as Entity
		ShulkerGlowFeature.colorValue(entity)?.let {
			cir.returnValue = it
			return
		}
		CorpseEspFeature.colorValue(entity)?.let {
			cir.returnValue = it
			return
		}
		M5Feature.colorValue(entity)?.let {
			cir.returnValue = it
		}
	}

	@Inject(method = ["shouldRender(D)Z"], at = [At("HEAD")], cancellable = true)
	private fun forceShulkerGlowRenderDistance(distance: Double, cir: CallbackInfoReturnable<Boolean>) {
		val entity = this as Entity
		if (ShulkerGlowFeature.shouldGlow(entity) || CorpseEspFeature.shouldGlow(entity) || M5Feature.shouldGlow(entity)) {
			cir.returnValue = true
		}
	}
}
