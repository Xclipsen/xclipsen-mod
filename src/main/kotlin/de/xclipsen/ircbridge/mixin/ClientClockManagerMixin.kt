package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.ClientTimeChanger
import net.minecraft.client.ClientClockManager
import net.minecraft.core.Holder
import net.minecraft.world.clock.WorldClock
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(ClientClockManager::class)
abstract class ClientClockManagerMixin {
	@Inject(method = ["getTotalTicks"], at = [At("RETURN")], cancellable = true)
	private fun overrideClockTime(clock: Holder<WorldClock>, cir: CallbackInfoReturnable<Long>) {
		cir.returnValue = ClientTimeChanger.overrideTimeOfDay(cir.returnValue)
	}
}
