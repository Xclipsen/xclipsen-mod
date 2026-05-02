package net.minecraft.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;

public final class XclipsenRenderLayers {
	private static final Map<Double, RenderLayer> XRAY_LINES = new ConcurrentHashMap<>();
	private static final RenderPipeline XRAY_LINE_PIPELINE = RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
		.withLocation(Identifier.of("xclipsen_mod", "xray_lines"))
		.withCull(false)
		.withBlend(BlendFunction.TRANSLUCENT)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withDepthWrite(false)
		.build();

	private XclipsenRenderLayers() {
	}

	public static RenderLayer getXrayLine(double width) {
		return XRAY_LINES.computeIfAbsent(width, XclipsenRenderLayers::createXrayLineLayer);
	}

	public static RenderLayer getXrayFill() {
		return RenderLayer.getDebugFilledBox();
	}

	private static RenderLayer createXrayLineLayer(double width) {
		RenderLayer.MultiPhaseParameters phases = RenderLayer.MultiPhaseParameters.builder()
			.target(RenderPhase.MAIN_TARGET)
			.lineWidth(new RenderPhase.LineWidth(OptionalDouble.of(width)))
			.build(false);
		return RenderLayer.of(
			"xclipsen_xray_line_" + width,
			RenderLayer.DEFAULT_BUFFER_SIZE,
			XRAY_LINE_PIPELINE,
			phases
		);
	}
}
