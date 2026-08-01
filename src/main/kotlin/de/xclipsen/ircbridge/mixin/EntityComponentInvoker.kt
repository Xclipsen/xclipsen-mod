package de.xclipsen.ircbridge.mixin

import net.minecraft.core.component.DataComponentType
import net.minecraft.world.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(Entity::class)
interface EntityComponentInvoker {
	@Invoker("applyImplicitComponent")
	fun `xclipsen$setApplicableComponent`(type: DataComponentType<*>, value: Any): Boolean
}
