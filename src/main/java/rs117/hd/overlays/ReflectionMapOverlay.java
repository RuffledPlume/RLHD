package rs117.hd.overlays;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Singleton;
import net.runelite.client.ui.FontManager;
import rs117.hd.renderer.zone.passes.ReflectionPass;
import rs117.hd.utils.RectAtlasPacker;
import rs117.hd.utils.HDUtils;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_WATER_REFLECTION_MAP;
import static rs117.hd.utils.MathUtils.*;

@Singleton
public class ReflectionMapOverlay extends ShaderOverlay<ReflectionMapOverlay.Shader> {
	static class Shader extends ShaderOverlay.Shader {
		private final UniformTexture uniColorMap = addUniformTexture("colorMap");

		public Shader() {
			super(t -> t.add(GL_FRAGMENT_SHADER, "overlays/reflection_map_frag.glsl"));
		}

		@Override
		protected void initialize() {
			uniColorMap.set(TEXTURE_UNIT_WATER_REFLECTION_MAP);
		}
	}

	public int activePlanes;
	private int textureWidth;
	private int textureHeight;
	private int atlasRectCount;
	private int atlasSize;
	private final int[] atlasRects = new int[ReflectionPass.MAX_REFLECTION_RENDERS * 3];

	public void setAtlasRects(RectAtlasPacker.Rect[] rects, int count, int atlasSize) {
		this.atlasRectCount = count;
		this.atlasSize = atlasSize;
		for (int i = 0; i < count; i++) {
			atlasRects[i * 3] = rects[i].x;
			atlasRects[i * 3 + 1] = rects[i].y;
			atlasRects[i * 3 + 2] = rects[i].size;
		}
	}

	@Override
	protected void renderShader() {
		glActiveTexture(TEXTURE_UNIT_WATER_REFLECTION_MAP);
		if (glGetInteger(GL_TEXTURE_BINDING_2D) == 0) {
			textureWidth = textureHeight = 0;
			return;
		}

		textureWidth = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
		textureHeight = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
		super.renderShader();
	}

	@Override
	public Dimension render(Graphics2D g) {
		var dims = super.render(g);

		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(Color.YELLOW);
		HDUtils.drawStringShadowed(g, String.format("Reflection atlas %d x %d", textureWidth, textureHeight), 4, 18);
		HDUtils.drawStringShadowed(g, String.format("ActivePlanes %d", activePlanes), 4, 32);

		if (textureWidth > 0 && textureHeight > 0 && atlasSize > 0) {
			Rectangle bounds = getBounds();
			for (int i = 0; i < atlasRectCount; i++) {
				int offset = i * 3;
				int x = round((float) atlasRects[offset] / atlasSize * bounds.width);
				int y = round((float) (atlasSize - atlasRects[offset + 1] - atlasRects[offset + 2]) / atlasSize * bounds.height);
				int width = round((float) atlasRects[offset + 2] / atlasSize * bounds.width);
				int height = round((float) atlasRects[offset + 2] / atlasSize * bounds.height);
				g.setColor(ReflectionPass.getDebugPlaneColor(i));
				g.drawRect(x, y, width, height);
			}
		}

		return dims;
	}
}
