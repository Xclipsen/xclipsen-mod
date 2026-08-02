package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.DungeonRedVignetteFeature
import de.xclipsen.ircbridge.ItemUpdateFixFeature
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.border.WorldBorder
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.Redirect
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(MultiPlayerGameMode::class)
abstract class ClientPlayerInteractionManagerMixin {
	@Shadow
	private lateinit var minecraft: Minecraft

	@Shadow
	private lateinit var destroyBlockPos: BlockPos

	@Shadow
	private lateinit var destroyingItem: ItemStack

	@Inject(method = ["sameDestroyTarget"], at = [At("HEAD")], cancellable = true)
	private fun preserveDestroyProgress(pos: BlockPos, cir: CallbackInfoReturnable<Boolean>) {
		if (pos != destroyBlockPos) return
		val heldItem = minecraft.player?.mainHandItem ?: return
		if (ItemUpdateFixFeature.representsSameItem(heldItem, destroyingItem)) {
			destroyingItem = heldItem
			cir.returnValue = true
		}
	}

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
