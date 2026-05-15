package de.xclipsen.ircbridge.mixin

import net.minecraft.component.ComponentType
import net.minecraft.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(Entity::class)
interface EntityComponentInvoker {
	@Invoker("setApplicableComponent")
	fun `xclipsen$setApplicableComponent`(type: ComponentType<*>, value: Any): Boolean
}
