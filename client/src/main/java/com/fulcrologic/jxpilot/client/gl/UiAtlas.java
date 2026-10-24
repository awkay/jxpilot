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

import com.fulcrologic.jxpilot.client.map.RadarImage;
import com.fulcrologic.jxpilot.client.paint.ItemBitmaps;
import com.fulcrologic.jxpilot.client.render.Font;
import com.fulcrologic.jxpilot.client.render.FontSpec;
import com.fulcrologic.jxpilot.client.render.FontStrips;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The single RGBA texture ({@value #SIZE} square) all UI drawing samples:
 * <ul>
 *   <li>a 4x4 white block at the origin (solid shapes use UV (0,0), which with clamping samples only it);</li>
 *   <li>the radar picture at x {@link #RADAR_X}..767, up to {@link #RADAR_MAX_HEIGHT} rows;</li>
 *   <li>the item symbols at {@link ItemBitmaps#ATLAS_X}..1023, two rows of 16;</li>
 *   <li>the ball texture ({@code texturedBalls}) in the 64 x 64 cell under them ({@link #setBall});</li>
 *   <li>the baked fonts, one strip each ({@link FontStrips}), in x 1024..2047 (all rows), then x 0..1023 below
 *       row 1024, then x 0..511 under the white block.</li>
 * </ul>
 * Fonts are rebaked when the pixel ratio or a font changes. Sampling is nearest-neighbour: glyph quads are
 * pixel-snapped at their baked size, and the radar is magnified by whole pixel ratios, so it stays crisp and
 * never bleeds into neighbouring texels.
 */
public final class UiAtlas {
    public static final int SIZE = 2048;
    public static final int WHITE_BLOCK = 4;
    public static final int RADAR_X = 512;
    /** Tallest radar picture kept; taller ones are cut (as with the earlier 1024 atlas). */
    public static final int RADAR_MAX_HEIGHT = 1024;

    private final int texture;
    private final FontStrips strips = new FontStrips(
        1024, 0, 1024, SIZE,
        0, 1024, 1024, SIZE - 1024,
        0, WHITE_BLOCK, RADAR_X, 1024 - WHITE_BLOCK);
    private Path fontFile;
    private Font font;
    private float fontPixels;
    private int radarHeight;

    /** An atlas with the white block and the item symbols, and no font yet ({@link #bakeFonts}). */
    public UiAtlas() {
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        ByteBuffer zero = MemoryUtil.memCalloc(SIZE * SIZE * 4);
        try {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, zero);
        } finally {
            MemoryUtil.memFree(zero);
        }
        bakeWhiteBlock();
        bakeItems();
    }

    /** An atlas with the bundled font baked at {@code fontPixels} framebuffer pixels. */
    public UiAtlas(float fontPixels) throws IOException {
        this(null, fontPixels);
    }

    /** An atlas with one TrueType {@code fontFile} (null: bundled Fira Code) baked at {@code fontPixels}. */
    public UiAtlas(Path fontFile, float fontPixels) throws IOException {
        this();
        this.fontFile = fontFile;
        bakeFont(fontPixels);
    }

    private void bakeWhiteBlock() {
        IntBuffer b = MemoryUtil.memAllocInt(WHITE_BLOCK * WHITE_BLOCK);
        try {
            for (int i = 0; i < WHITE_BLOCK * WHITE_BLOCK; i++) {
                b.put(i, -1);
            }
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, WHITE_BLOCK, WHITE_BLOCK, GL_RGBA, GL_UNSIGNED_BYTE, b);
        } finally {
            MemoryUtil.memFree(b);
        }
    }

    /** Upload the item symbols ({@link ItemBitmaps}) into their block. */
    private void bakeItems() {
        int w = 16 * ItemBitmaps.SIZE;
        int h = 2 * ItemBitmaps.SIZE;
        int[] full = new int[SIZE * h];
        ItemBitmaps.bake(full, SIZE);
        IntBuffer b = MemoryUtil.memAllocInt(w * h);
        try {
            for (int y = 0; y < h; y++) {
                b.put(full, y * SIZE + ItemBitmaps.ATLAS_X, w);
            }
            b.flip();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexSubImage2D(GL_TEXTURE_2D, 0, ItemBitmaps.ATLAS_X, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, b);
        } finally {
            MemoryUtil.memFree(b);
        }
    }

    /** (Re)bake the single font (the constructor's file) at {@code pixels} framebuffer pixels. */
    public void bakeFont(float pixels) throws IOException {
        FontSpec s = fontFile == null ? FontSpec.of(FontSpec.Face.FIRA_CODE, pixels)
            : new FontSpec(null, fontFile.toString(), pixels);
        font = bakeFonts(new FontSpec[] {s}, 1)[0];
        fontPixels = pixels;
    }

    /**
     * (Re)bake a set of fonts, each at its size times {@code pixelRatio} framebuffer pixels, replacing every
     * font baked before. Equal specs share one bake.
     *
     * @return the font of each spec, in order
     */
    public Font[] bakeFonts(FontSpec[] specs, double pixelRatio) throws IOException {
        strips.reset();
        int[] share = FontSpec.shareIndex(specs);
        Font[] fonts = new Font[specs.length];
        for (int i = 0; i < specs.length; i++) {
            fonts[i] = share[i] != i ? fonts[share[i]] : bakeOne(specs[i], (float) (specs[i].size() * pixelRatio));
        }
        font = fonts.length > 0 ? fonts[0] : null;
        return fonts;
    }

    /** Bake one font into the first region with room for it, and upload its strip. */
    private Font bakeOne(FontSpec spec, float pixels) throws IOException {
        ByteBuffer ttf = FontBaker.load(spec);
        try {
            for (int r = 0; r < strips.regions(); r++) {
                int rows = strips.remaining(r);
                if (rows <= 0) {
                    continue;
                }
                int w = strips.width(r);
                int x = strips.x(r);
                int y = strips.nextY(r);
                ByteBuffer alpha = MemoryUtil.memCalloc(w * rows);
                try {
                    FontBaker.Baked b = FontBaker.bake(ttf, pixels, alpha, w, rows, x, y, SIZE, SIZE);
                    if (b != null) {
                        upload(alpha, x, y, w, b.rows());
                        strips.take(r, b.rows());
                        return b.font();
                    }
                } finally {
                    MemoryUtil.memFree(alpha);
                }
            }
            throw new IllegalStateException("No room in the UI atlas for " + spec + " at " + pixels + "px");
        } finally {
            MemoryUtil.memFree(ttf);
        }
    }

    /** Upload {@code rows} rows of coverage as white RGBA at ({@code x}, {@code y}). */
    private void upload(ByteBuffer alpha, int x, int y, int w, int rows) {
        ByteBuffer rgba = MemoryUtil.memAlloc(w * rows * 4);
        try {
            for (int i = 0; i < w * rows; i++) {
                rgba.put(4 * i, (byte) 255).put(4 * i + 1, (byte) 255).put(4 * i + 2, (byte) 255)
                    .put(4 * i + 3, alpha.get(i));
            }
            glBindTexture(GL_TEXTURE_2D, texture);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, rows, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        } finally {
            MemoryUtil.memFree(rgba);
        }
    }

    public int texture() { return texture; }

    /** The single font ({@link #bakeFont}), or the first of the last {@link #bakeFonts}. */
    public Font font() { return font; }

    /** Size the single font was baked at, in framebuffer pixels. */
    public float fontPixels() { return fontPixels; }

    /** Upload radar pixels (rows top-first, {@link RadarImage#WIDTH} wide); rows past {@link #RADAR_MAX_HEIGHT} are cut. */
    public void setRadar(int[] pixels, int height) {
        radarHeight = Math.min(height, RADAR_MAX_HEIGHT);
        IntBuffer b = MemoryUtil.memAllocInt(RadarImage.WIDTH * radarHeight);
        try {
            b.put(pixels, 0, RadarImage.WIDTH * radarHeight).flip();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexSubImage2D(GL_TEXTURE_2D, 0, RADAR_X, 0, RadarImage.WIDTH, radarHeight, GL_RGBA, GL_UNSIGNED_BYTE, b);
        } finally {
            MemoryUtil.memFree(b);
        }
    }

    public int radarHeight() { return radarHeight; }

    /**
     * Put the ball texture ({@code texturedBalls}) into its cell ({@code ObjectPainter.BALL_TEXTURE_X}, 64 x 64
     * pixels), tiled from the cell's corner as X11 tiles a fill.
     */
    public void setBall(int width, int height, int[] pixels) {
        int n = com.fulcrologic.jxpilot.client.paint.ObjectPainter.BALL_TEXTURE_SIZE;
        IntBuffer b = MemoryUtil.memAllocInt(n * n);
        try {
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    b.put(pixels[(y % height) * width + x % width]);
                }
            }
            b.flip();
            glBindTexture(GL_TEXTURE_2D, texture);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexSubImage2D(GL_TEXTURE_2D, 0, com.fulcrologic.jxpilot.client.paint.ObjectPainter.BALL_TEXTURE_X,
                com.fulcrologic.jxpilot.client.paint.ObjectPainter.BALL_TEXTURE_Y, n, n, GL_RGBA, GL_UNSIGNED_BYTE, b);
        } finally {
            MemoryUtil.memFree(b);
        }
    }

    public void dispose() {
        glDeleteTextures(texture);
    }
}
