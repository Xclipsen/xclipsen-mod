package de.xclipsen.ircbridge.mixin

import net.minecraft.world.entity.animal.fox.Fox
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(Fox::class)
interface FoxEntityInvoker {
	@Invoker("setVariant")
	fun `xclipsen$setVariant`(variant: Fox.Variant)
}
