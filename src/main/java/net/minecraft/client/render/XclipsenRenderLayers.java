package net.minecraft.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.Identifier;

public final class XclipsenRenderLayers {
	private static final RenderPipeline XRAY_LINE_PIPELINE = RenderPipeline.builder()
		.withLocation(Identifier.of("xclipsen", "shulker_xray_lines"))
		.withVertexShader("core/position_color")
		.withFragmentShader("core/position_color")
		.withCull(false)
		.withBlend(BlendFunction.TRANSLUCENT)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withDepthWrite(false)
		.withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.DEBUG_LINE_STRIP)
		.build();
	private static final Map<Double, RenderLayer> XRAY_LINES = new ConcurrentHashMap<>();

	private XclipsenRenderLayers() {
	}

	public static RenderLayer getXrayLine(double width) {
		return XRAY_LINES.computeIfAbsent(width, lineWidth -> RenderLayer.of(
			"xclipsen_shulker_xray_line_" + lineWidth,
			RenderLayer.DEFAULT_BUFFER_SIZE,
			XRAY_LINE_PIPELINE,
			RenderLayer.MultiPhaseParameters.builder()
				.lineWidth(new RenderPhase.LineWidth(OptionalDouble.of(lineWidth)))
				.build(false)
		));
	}
}
