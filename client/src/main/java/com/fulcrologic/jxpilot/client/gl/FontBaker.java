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
import org.lwjgl.stb.STBTTBakedChar;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.stb.STBTruetype.*;

/**
 * Bakes a TrueType font (STB) into the UI atlas and returns its {@link Font} metrics. The font
 * bitmap is single-channel; {@link UiAtlas} expands it to white RGBA with coverage in alpha.
 */
public final class FontBaker {
    private FontBaker() {}

    /** Bundled font resource. */
    public static final String DEFAULT_FONT = "/fonts/FiraCode-Regular.ttf";

    /**
     * Bake printable ASCII at {@code pixelHeight} into {@code alpha} ({@code w x h}, row-major),
     * starting at row {@code rowOffset} of an atlas of {@code atlasW x atlasH}.
     */
    public static Font bake(String resource, float pixelHeight, ByteBuffer alpha, int w, int h,
                            int rowOffset, int atlasW, int atlasH) throws IOException {
        ByteBuffer ttf = load(resource);
        try (MemoryStack stack = MemoryStack.stackPush();
             STBTTBakedChar.Buffer chars = STBTTBakedChar.malloc(Font.LAST - Font.FIRST + 1)) {
            int r = stbtt_BakeFontBitmap(ttf, pixelHeight, alpha, w, h, Font.FIRST, chars);
            if (r == 0) {
                throw new IllegalStateException("Font atlas too small for " + pixelHeight + "px");
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
                f.setGlyph(c, b.x0(), b.y0() + rowOffset, b.x1(), b.y1() + rowOffset, b.xoff(), b.yoff(), b.xadvance());
            }
            return f;
        } finally {
            MemoryUtil.memFree(ttf);
        }
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
