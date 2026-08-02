package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.AutoSprintFeature
import de.xclipsen.ircbridge.minigame.ChestLikeScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.player.Input
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LocalPlayer::class)
abstract class ClientPlayerEntityMixin {
	@Inject(method = ["closeContainer"], at = [At("HEAD")], cancellable = true)
	private fun xclipsenKeepLocalContainerLocal(callback: CallbackInfo) {
		if (Minecraft.getInstance().screen is ChestLikeScreen) {
			callback.cancel()
		}
	}

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
