package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.DungeonRedVignetteFeature
import net.minecraft.client.network.ClientPlayerInteractionManager
import net.minecraft.util.math.BlockPos
import net.minecraft.world.border.WorldBorder
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

@Mixin(ClientPlayerInteractionManager::class)
abstract class ClientPlayerInteractionManagerMixin {
	@Redirect(
		method = ["interactBlock"],
		at = At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/border/WorldBorder;contains(Lnet/minecraft/util/math/BlockPos;)Z",
		),
	)
	private fun fixRedVignette(worldBorder: WorldBorder, pos: BlockPos): Boolean {
		if (!DungeonRedVignetteFeature.isEnabled()) {
			return worldBorder.contains(pos)
		}

		return true
	}
}
