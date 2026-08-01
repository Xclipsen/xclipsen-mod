package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.MobModelFeature
import de.xclipsen.ircbridge.FireFreezeFeature
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import com.mojang.blaze3d.vertex.PoseStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LivingEntityRenderer::class)
abstract class LivingEntityRendererMixin {
	@Inject(
		method = ["submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"],
		at = [At("HEAD")],
		cancellable = true,
	)
	private fun renderMobModelReplacement(
		state: LivingEntityRenderState,
		matrices: PoseStack,
		queue: SubmitNodeCollector,
		cameraState: CameraRenderState,
		ci: CallbackInfo,
	) {
		if (FireFreezeFeature.shouldSuppressEntityRender(state)) {
			ci.cancel()
			return
		}

		if ((this as Any) !is AvatarRenderer<*>) {
			return
		}
		val playerState = state as? AvatarRenderState ?: return
		if (MobModelFeature.renderReplacement(playerState, matrices, queue, cameraState)) {
			ci.cancel()
		}
	}
}
