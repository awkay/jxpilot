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
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The single RGBA texture all UI drawing samples: a 4x4 white block at the origin (solid shapes
 * use UV (0,0), which with clamping samples only that block), the baked font (left half, rebaked
 * when the pixel ratio changes), the radar picture (x {@link #RADAR_X} to 767) and the item symbols
 * ({@link ItemBitmaps#ATLAS_X} on, two rows of 16).
 * Sampling is nearest-neighbour: glyph quads are pixel-snapped at their baked size, and the radar is
 * magnified by whole pixel ratios, so it stays crisp and never bleeds into neighbouring texels.
 */
public final class UiAtlas {
    public static final int SIZE = 1024;
    public static final int WHITE_BLOCK = 4;
    public static final int FONT_W = 512;
    public static final int FONT_H = SIZE - WHITE_BLOCK;
    public static final int RADAR_X = 512;

    private final int texture;
    private final Path fontFile;
    private Font font;
    private float fontPixels;
    private int radarHeight;

    /** An atlas with the bundled font baked at {@code fontPixels} framebuffer pixels. */
    public UiAtlas(float fontPixels) throws IOException {
        this(null, fontPixels);
    }

    /** An atlas with the TrueType {@code fontFile} (null: bundled) baked at {@code fontPixels} framebuffer pixels. */
    public UiAtlas(Path fontFile, float fontPixels) throws IOException {
        this.fontFile = fontFile;
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
        bakeFont(fontPixels);
        bakeItems();
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

    /** (Re)bake the font at {@code pixels} framebuffer pixels into the left half, with the white block. */
    public void bakeFont(float pixels) throws IOException {
        ByteBuffer alpha = MemoryUtil.memCalloc(FONT_W * FONT_H);
        ByteBuffer rgba = MemoryUtil.memCalloc(FONT_W * SIZE * 4);
        try {
            font = FontBaker.bake(fontFile, pixels, alpha, FONT_W, FONT_H, WHITE_BLOCK, SIZE, SIZE);
            for (int y = 0; y < FONT_H; y++) {
                for (int x = 0; x < FONT_W; x++) {
                    int o = ((y + WHITE_BLOCK) * FONT_W + x) * 4;
                    rgba.put(o, (byte) 255).put(o + 1, (byte) 255).put(o + 2, (byte) 255)
                        .put(o + 3, alpha.get(y * FONT_W + x));
                }
            }
            for (int y = 0; y < WHITE_BLOCK; y++) {
                for (int x = 0; x < WHITE_BLOCK; x++) {
                    rgba.putInt((y * FONT_W + x) * 4, -1);
                }
            }
            glBindTexture(GL_TEXTURE_2D, texture);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, FONT_W, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
            fontPixels = pixels;
        } finally {
            MemoryUtil.memFree(alpha);
            MemoryUtil.memFree(rgba);
        }
    }

    public int texture() { return texture; }

    public Font font() { return font; }

    /** Size the current font was baked at, in framebuffer pixels. */
    public float fontPixels() { return fontPixels; }

    /** Upload radar pixels (rows top-first, {@link RadarImage#WIDTH} wide); taller pictures are cut. */
    public void setRadar(int[] pixels, int height) {
        radarHeight = Math.min(height, SIZE);
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
