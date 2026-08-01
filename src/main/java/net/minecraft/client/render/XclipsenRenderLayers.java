package net.minecraft.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

public final class XclipsenRenderLayers {
	private static final Map<Double, RenderType> XRAY_LINES = new ConcurrentHashMap<>();
	private static final RenderPipeline XRAY_LINE_PIPELINE = registerPipeline(
		RenderPipeline.builder(getSnippet("LINES_SNIPPET"))
			.withLocation("pipeline/xclipsen_xray_line")
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.build()
	);
	private static final RenderPipeline XRAY_FILL_PIPELINE = registerPipeline(
		RenderPipeline.builder(getSnippet("DEBUG_FILLED_SNIPPET"))
			.withLocation("pipeline/xclipsen_xray_fill")
			.withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.build()
	);
	private static final RenderType XRAY_FILL = createRenderType(
		"xclipsen_xray_fill",
		RenderSetup.builder(XRAY_FILL_PIPELINE)
			.sortOnUpload()
			.bufferSize(1536)
			.createRenderSetup()
	);

	private XclipsenRenderLayers() {
	}

	public static RenderType getXrayLine(double width) {
		return XRAY_LINES.computeIfAbsent(width, XclipsenRenderLayers::createXrayLineLayer);
	}

	public static RenderType getXrayFill() {
		return XRAY_FILL;
	}

	private static RenderType createXrayLineLayer(double width) {
		return createRenderType(
			"xclipsen_xray_line_" + width,
			RenderSetup.builder(XRAY_LINE_PIPELINE)
				.sortOnUpload()
				.bufferSize(1536)
				.createRenderSetup()
		);
	}

	private static RenderPipeline.Snippet getSnippet(String name) {
		try {
			Field field = RenderPipelines.class.getDeclaredField(name);
			field.setAccessible(true);
			return (RenderPipeline.Snippet)field.get(null);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static RenderPipeline registerPipeline(RenderPipeline pipeline) {
		try {
			Method method = RenderPipelines.class.getDeclaredMethod("register", RenderPipeline.class);
			method.setAccessible(true);
			return (RenderPipeline)method.invoke(null, pipeline);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static RenderType createRenderType(String name, RenderSetup setup) {
		try {
			Method method = RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
			method.setAccessible(true);
			return (RenderType)method.invoke(null, name, setup);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
