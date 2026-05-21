package de.xclipsen.ircbridge.mixin

import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRenderer
import net.minecraft.client.render.entity.state.EntityRenderState
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(EntityRenderer::class)
interface EntityRendererInvoker {
	@Invoker("renderLabelIfPresent")
	fun invokeRenderLabelIfPresent(
		state: EntityRenderState,
		matrices: MatrixStack,
		queue: OrderedRenderCommandQueue,
		cameraState: CameraRenderState,
	)
}
