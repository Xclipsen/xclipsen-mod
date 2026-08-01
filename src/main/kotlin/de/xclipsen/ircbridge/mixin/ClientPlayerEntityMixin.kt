package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.AutoSprintFeature
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.player.Input
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

@Mixin(LocalPlayer::class)
abstract class ClientPlayerEntityMixin {
	@Redirect(
		method = ["aiStep"],
		at = At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/player/Input;sprint()Z",
		),
	)
	private fun xclipsenAutoSprint(input: Input): Boolean {
		return AutoSprintFeature.overrideSprintInput(input.sprint())
	}
}
