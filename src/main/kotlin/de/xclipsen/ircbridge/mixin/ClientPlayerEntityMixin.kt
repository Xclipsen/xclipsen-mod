package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.AutoSprintFeature
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.util.PlayerInput
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

@Mixin(ClientPlayerEntity::class)
abstract class ClientPlayerEntityMixin {
	@Redirect(
		method = ["tickMovement"],
		at = At(
			value = "INVOKE",
			target = "Lnet/minecraft/util/PlayerInput;sprint()Z",
		),
	)
	private fun xclipsenAutoSprint(input: PlayerInput): Boolean {
		return AutoSprintFeature.overrideSprintInput(input.sprint())
	}
}
