package de.xclipsen.ircbridge.mixin

import net.minecraft.world.entity.animal.cow.MushroomCow
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(MushroomCow::class)
interface MooshroomEntityInvoker {
	@Invoker("setVariant")
	fun `xclipsen$setVariant`(variant: MushroomCow.Variant)
}
