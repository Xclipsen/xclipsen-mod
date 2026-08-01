package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.DungeonRedVignetteFeature
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.core.BlockPos
import net.minecraft.world.level.border.WorldBorder
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

@Mixin(MultiPlayerGameMode::class)
abstract class ClientPlayerInteractionManagerMixin {
	@Redirect(
		method = ["useItemOn"],
		at = At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/border/WorldBorder;isWithinBounds(Lnet/minecraft/core/BlockPos;)Z",
		),
	)
	private fun fixRedVignette(worldBorder: WorldBorder, pos: BlockPos): Boolean {
		if (!DungeonRedVignetteFeature.isEnabled()) {
			return worldBorder.isWithinBounds(pos)
		}

		return true
	}
}
