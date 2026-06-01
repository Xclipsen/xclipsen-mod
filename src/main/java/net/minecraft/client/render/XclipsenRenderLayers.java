package net.minecraft.client.render;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gl.RenderPipelines;

public final class XclipsenRenderLayers {
	private static final Map<Double, RenderLayer> XRAY_LINES = new ConcurrentHashMap<>();
	private static final RenderLayer XRAY_FILL = RenderLayer.of(
		"xclipsen_xray_fill",
		RenderSetup.builder(RenderPipelines.DEBUG_FILLED_BOX)
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
			RenderSetup.builder(RenderPipelines.LINES_TRANSLUCENT)
				.translucent()
				.expectedBufferSize(1536)
				.build()
		);
	}
}
