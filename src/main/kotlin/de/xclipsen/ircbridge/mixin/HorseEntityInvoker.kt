package de.xclipsen.ircbridge.mixin

import net.minecraft.entity.passive.HorseColor
import net.minecraft.entity.passive.HorseEntity
import net.minecraft.entity.passive.HorseMarking
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(HorseEntity::class)
interface HorseEntityInvoker {
	@Invoker("setHorseVariant")
	fun `xclipsen$setHorseVariant`(color: HorseColor, marking: HorseMarking)
}
