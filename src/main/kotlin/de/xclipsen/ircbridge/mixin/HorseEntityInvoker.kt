package de.xclipsen.ircbridge.mixin

import net.minecraft.world.entity.animal.equine.Variant
import net.minecraft.world.entity.animal.equine.Horse
import net.minecraft.world.entity.animal.equine.Markings
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(Horse::class)
interface HorseEntityInvoker {
	@Invoker("setVariantAndMarkings")
	fun `xclipsen$setHorseVariant`(color: Variant, marking: Markings)
}
