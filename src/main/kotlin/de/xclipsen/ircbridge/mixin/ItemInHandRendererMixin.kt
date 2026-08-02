package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ItemUpdateFixFeature
import net.minecraft.client.renderer.ItemInHandRenderer
import net.minecraft.world.item.ItemStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(ItemInHandRenderer::class)
abstract class ItemInHandRendererMixin {
	@Inject(method = ["shouldInstantlyReplaceVisibleItem"], at = [At("HEAD")], cancellable = true)
	private fun preserveUseAnimation(
		visibleStack: ItemStack,
		updatedStack: ItemStack,
		cir: CallbackInfoReturnable<Boolean>,
	) {
		if (ItemUpdateFixFeature.representsSameItem(visibleStack, updatedStack)) {
			cir.returnValue = true
		}
	}
}
