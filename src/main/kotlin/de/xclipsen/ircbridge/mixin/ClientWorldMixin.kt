package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ClientTimeChanger
import net.minecraft.client.world.ClientWorld
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyVariable

@Mixin(ClientWorld::class)
abstract class ClientWorldMixin {
	@ModifyVariable(method = ["setTime(JJZ)V"], at = At("HEAD"), argsOnly = true, ordinal = 1)
	private fun overrideTimeOfDay(timeOfDay: Long): Long {
		return ClientTimeChanger.overrideTimeOfDay(timeOfDay)
	}

	@ModifyVariable(method = ["setTime(JJZ)V"], at = At("HEAD"), argsOnly = true, ordinal = 0)
	private fun overrideShouldTickTimeOfDay(shouldTickTimeOfDay: Boolean): Boolean {
		return ClientTimeChanger.shouldTickTimeOfDay(shouldTickTimeOfDay)
	}
}
