/*
 * JXPilot, a Java reimplementation of XPilot, a multiplayer gravity war game.
 * Copyright (C) 2026 Tony Kay and the JXPilot contributors.
 *
 * Derived from XPilot 4.5.5, Copyright (C) 1991-2002 by Bjørn Stabell,
 * Ken Ronny Schouten, Bert Gijsbers and Dick Balaska. JXPilot is an
 * independent derivative port: the original XPilot authors were not involved
 * in it and do not endorse it. Ported to Java and modified starting in 2026;
 * see the git history for the changes.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <https://www.gnu.org/licenses/>.
 */
package com.fulcrologic.jxpilot.client.gl;

import com.fulcrologic.jxpilot.client.render.Font;
import com.fulcrologic.jxpilot.client.render.FontSpec;
import org.lwjgl.stb.STBTTBakedChar;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.stb.STBTruetype.*;

/**
 * Bakes TrueType fonts (STB) into the UI atlas and returns their {@link Font} metrics. The font bitmap is
 * single-channel; {@link UiAtlas} expands it to white RGBA with coverage in alpha.
 * <p>
 * The C client draws each kind of text with its own X font ({@code gameFont} Times 18, {@code messageFont}
 * Times 14, {@code scoreListFont} fixed bold 13, {@code talkFont} fixed bold 15, ...; {@code default.c}).
 * This client has no X fonts: it maps each font resource to a bundled Liberation face (metric-compatible
 * with Times, Helvetica and Courier; {@link FontSpec#parse}) and bakes one font per role at that pixel size.
 * Known deviation: the glyphs are Liberation's, not the X server's bitmap fonts, so widths differ slightly
 * from a C client on X. {@code --font FILE|NAME} and {@code --font-size PX} replace every role with one font
 * (bundled Fira Code, 13 logical pixels, unless given). Fonts are baked at size times the display's pixel
 * ratio and rebaked when that ratio or a font resource changes, so text is crisp on HiDPI displays at the
 * same logical size.
 */
public final class FontBaker {
    private FontBaker() {}

    /** Bundled single font of {@code --font-size} without {@code --font}, and of the map viewer. */
    public static final String DEFAULT_FONT = FontSpec.Face.FIRA_CODE.resource();

    /** A baked font and the atlas rows its glyphs use. */
    public record Baked(Font font, int rows) {}

    /**
     * Bake the Latin-1 glyphs ({@link Font#FIRST}..{@link Font#LAST}) of the TrueType file (null: the bundled
     * {@link #DEFAULT_FONT}) at {@code pixelHeight} into {@code alpha} ({@code w x h}, row-major), starting at
     * row {@code rowOffset} of an atlas of {@code atlasW x atlasH}.
     */
    public static Font bake(Path file, float pixelHeight, ByteBuffer alpha, int w, int h,
                            int rowOffset, int atlasW, int atlasH) throws IOException {
        ByteBuffer ttf = file == null ? load(DEFAULT_FONT) : loadFile(file);
        try {
            Baked b = bake(ttf, pixelHeight, alpha, w, h, 0, rowOffset, atlasW, atlasH);
            if (b == null) {
                throw new IllegalStateException("Font atlas too small for " + pixelHeight + "px");
            }
            return b.font();
        } finally {
            MemoryUtil.memFree(ttf);
        }
    }

    /**
     * Bake {@code ttf} at {@code pixelHeight} into {@code alpha} ({@code w x h}); the glyphs' atlas
     * rectangles are offset by ({@code x0}, {@code y0}) in an atlas of {@code atlasW x atlasH}.
     *
     * @return the font and the rows used, or null when the glyphs do not fit in {@code w x h}
     */
    public static Baked bake(ByteBuffer ttf, float pixelHeight, ByteBuffer alpha, int w, int h, int x0, int y0,
                             int atlasW, int atlasH) {
        try (MemoryStack stack = MemoryStack.stackPush();
             STBTTBakedChar.Buffer chars = STBTTBakedChar.malloc(Font.LAST - Font.FIRST + 1)) {
            int r = stbtt_BakeFontBitmap(ttf, pixelHeight, alpha, w, h, Font.FIRST, chars);
            if (r <= 0) {
                return null;
            }
            STBTTFontinfo info = STBTTFontinfo.malloc(stack);
            stbtt_InitFont(info, ttf);
            IntBuffer asc = stack.mallocInt(1);
            IntBuffer desc = stack.mallocInt(1);
            IntBuffer gap = stack.mallocInt(1);
            stbtt_GetFontVMetrics(info, asc, desc, gap);
            float scale = stbtt_ScaleForPixelHeight(info, pixelHeight);
            Font f = new Font(atlasW, atlasH, Math.round(asc.get(0) * scale), Math.round(-desc.get(0) * scale));
            for (char c = Font.FIRST; c <= Font.LAST; c++) {
                STBTTBakedChar b = chars.get(c - Font.FIRST);
                f.setGlyph(c, b.x0() + x0, b.y0() + y0, b.x1() + x0, b.y1() + y0, b.xoff(), b.yoff(), b.xadvance());
            }
            return new Baked(f, r);
        }
    }

    /** The TrueType data of {@code spec} (bundled resource or file) in native memory; free it with memFree. */
    public static ByteBuffer load(FontSpec spec) throws IOException {
        return spec.face() != null ? load(spec.face().resource()) : loadFile(Path.of(spec.file()));
    }

    /** True iff {@code path} is a readable file STB accepts as a TrueType font. */
    public static boolean isFontFile(String path) {
        Path p = Path.of(path);
        if (!Files.isRegularFile(p) || !Files.isReadable(p)) {
            return false;
        }
        ByteBuffer ttf = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (Files.size(p) < 12) {
                return false; // shorter than a TrueType header
            }
            ttf = loadFile(p);
            return stbtt_GetFontOffsetForIndex(ttf, 0) >= 0 && stbtt_InitFont(STBTTFontinfo.malloc(stack), ttf);
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (ttf != null) {
                MemoryUtil.memFree(ttf);
            }
        }
    }

    private static ByteBuffer loadFile(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer b = MemoryUtil.memAlloc(bytes.length);
        b.put(bytes).flip();
        return b;
    }

    private static ByteBuffer load(String resource) throws IOException {
        try (InputStream in = FontBaker.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing font resource " + resource);
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer b = MemoryUtil.memAlloc(bytes.length);
            b.put(bytes).flip();
            return b;
        }
    }
}
