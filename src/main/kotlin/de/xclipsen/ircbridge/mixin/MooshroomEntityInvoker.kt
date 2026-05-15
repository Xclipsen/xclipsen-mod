package de.xclipsen.ircbridge.mixin

import net.minecraft.entity.passive.MooshroomEntity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(MooshroomEntity::class)
interface MooshroomEntityInvoker {
	@Invoker("setVariant")
	fun `xclipsen$setVariant`(variant: MooshroomEntity.Variant)
}
