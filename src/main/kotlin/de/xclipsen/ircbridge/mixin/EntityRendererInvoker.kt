package de.xclipsen.ircbridge.mixin

import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import com.mojang.blaze3d.vertex.PoseStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(EntityRenderer::class)
interface EntityRendererInvoker {
	@Invoker("submitNameDisplay")
	fun invokeRenderLabelIfPresent(
		state: EntityRenderState,
		matrices: PoseStack,
		queue: SubmitNodeCollector,
		cameraState: CameraRenderState,
	)
}
