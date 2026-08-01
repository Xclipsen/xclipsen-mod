package net.minecraft.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gl.RenderPipelines;

public final class XclipsenRenderLayers {
	private static final Map<Double, RenderLayer> XRAY_LINES = new ConcurrentHashMap<>();
	private static final RenderPipeline XRAY_LINE_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
			.withLocation("pipeline/xclipsen_xray_line")
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.withDepthWrite(false)
			.withBlend(BlendFunction.TRANSLUCENT)
			.build()
	);
	private static final RenderPipeline XRAY_FILL_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
			.withLocation("pipeline/xclipsen_xray_fill")
			.withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS)
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.withDepthWrite(false)
			.withBlend(BlendFunction.TRANSLUCENT)
			.build()
	);
	private static final RenderLayer XRAY_FILL = RenderLayer.of(
		"xclipsen_xray_fill",
		RenderSetup.builder(XRAY_FILL_PIPELINE)
			.translucent()
			.expectedBufferSize(1536)
			.build()
	);

	private XclipsenRenderLayers() {
	}

	public static RenderLayer getXrayLine(double width) {
		return XRAY_LINES.computeIfAbsent(width, XclipsenRenderLayers::createXrayLineLayer);
	}

	public static RenderLayer getXrayFill() {
		return XRAY_FILL;
	}

	private static RenderLayer createXrayLineLayer(double width) {
		return RenderLayer.of(
			"xclipsen_xray_line_" + width,
			RenderSetup.builder(XRAY_LINE_PIPELINE)
				.translucent()
				.expectedBufferSize(1536)
				.build()
		);
	}
}
