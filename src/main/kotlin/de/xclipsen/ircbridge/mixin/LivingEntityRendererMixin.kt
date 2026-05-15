package de.xclipsen.ircbridge.mixin

import de.xclipsen.ircbridge.MobModelFeature
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.LivingEntityRenderer
import net.minecraft.client.render.entity.PlayerEntityRenderer
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LivingEntityRenderer::class)
abstract class LivingEntityRendererMixin {
	@Inject(
		method = ["render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V"],
		at = [At("HEAD")],
		cancellable = true,
	)
	private fun renderMobModelReplacement(
		state: LivingEntityRenderState,
		matrices: MatrixStack,
		queue: OrderedRenderCommandQueue,
		cameraState: CameraRenderState,
		ci: CallbackInfo,
	) {
		if (this !is PlayerEntityRenderer<*>) {
			return
		}
		val playerState = state as? PlayerEntityRenderState ?: return
		if (MobModelFeature.renderReplacement(playerState, matrices, queue, cameraState)) {
			ci.cancel()
		}
	}
}
