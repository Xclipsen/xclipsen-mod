package de.xclipsen.ircbridge.mixin

import net.minecraft.entity.passive.FoxEntity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(FoxEntity::class)
interface FoxEntityInvoker {
	@Invoker("setVariant")
	fun `xclipsen$setVariant`(variant: FoxEntity.Variant)
}
