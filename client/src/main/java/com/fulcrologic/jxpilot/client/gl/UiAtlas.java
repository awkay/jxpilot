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
import com.fulcrologic.jxpilot.client.map.RadarImage;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The single RGBA texture all UI drawing samples: a 4x4 white block at the origin (solid shapes
 * use UV (0,0), which with clamping samples only that block),
 * the baked font (left half), and the radar picture (from x = {@link #RADAR_X}).
 */
public final class UiAtlas {
    public static final int SIZE = 1024;
    public static final int WHITE_BLOCK = 4;
    public static final int FONT_W = 512;
    public static final int FONT_H = SIZE - WHITE_BLOCK;
    public static final int RADAR_X = 512;

    private final int texture;
    private final Font font;
    private int radarHeight;

    public UiAtlas(float fontPixels) throws IOException {
        ByteBuffer alpha = MemoryUtil.memCalloc(FONT_W * FONT_H);
        ByteBuffer rgba = MemoryUtil.memCalloc(SIZE * SIZE * 4);
        try {
            font = FontBaker.bake(FontBaker.DEFAULT_FONT, fontPixels, alpha, FONT_W, FONT_H, WHITE_BLOCK, SIZE, SIZE);
            for (int y = 0; y < FONT_H; y++) {
                for (int x = 0; x < FONT_W; x++) {
                    int o = ((y + WHITE_BLOCK) * SIZE + x) * 4;
                    rgba.put(o, (byte) 255).put(o + 1, (byte) 255).put(o + 2, (byte) 255)
                        .put(o + 3, alpha.get(y * FONT_W + x));
                }
            }
            for (int y = 0; y < WHITE_BLOCK; y++) {
                for (int x = 0; x < WHITE_BLOCK; x++) {
                    rgba.putInt((y * SIZE + x) * 4, -1);
                }
            }
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        } finally {
            MemoryUtil.memFree(alpha);
            MemoryUtil.memFree(rgba);
        }
    }

    public int texture() { return texture; }

    public Font font() { return font; }

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

    public void dispose() {
        glDeleteTextures(texture);
    }
}
