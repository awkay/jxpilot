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
package com.fulcrologic.jxpilot.client.paint;

import com.fulcrologic.jxpilot.client.render.PpmImage;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.client.render.Trig;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where the block bitmaps ({@link BlockBitmap}, {@code texturedObjects}) sit in the UI atlas, how they are
 * loaded into it, and how a picture is drawn.
 * <p>
 * Every picture of every atlas bitmap is packed on shelves into the free atlas block {@link #X0}, {@link #Y0}
 * ({@link #W} x {@link #H} texels), in {@link BlockBitmap} order, a texel apart. A rotating bitmap stores only
 * its one picture: JXPilot turns the quad (C precomputes the turned pictures). Pictures are drawn as textured
 * quads in world or framebuffer coordinates, scaled to the size asked for (C scales them by area averaging; here
 * the GPU samples the nearest texel). Allocation free when drawing.
 */
public final class BlockBitmaps {
    /** The free atlas block the pictures are packed into (under the item symbols and the ball texture). */
    public static final int X0 = 768;
    public static final int Y0 = 96;
    public static final int W = 256;
    public static final int H = 928;
    /** Classpath folder of the bitmaps. */
    public static final String DIR = "/textures/";

    private static final BlockBitmap[] ALL = BlockBitmap.values();
    /** First picture index of each bitmap in {@link #px}/{@link #py}. */
    private static final int[] FIRST = new int[ALL.length + 1];
    private static final int[] px;
    private static final int[] py;

    static {
        int n = 0;
        for (int i = 0; i < ALL.length; i++) {
            FIRST[i] = n;
            n += ALL[i].inAtlas() ? ALL[i].pictures() : 0;
        }
        FIRST[ALL.length] = n;
        px = new int[n];
        py = new int[n];
        int x = 0;
        int y = 0;
        int shelf = 0;
        for (BlockBitmap b : ALL) {
            if (!b.inAtlas()) {
                continue;
            }
            for (int p = 0; p < b.pictures(); p++) {
                if (x + b.width > W) {
                    x = 0;
                    y += shelf + 1;
                    shelf = 0;
                }
                px[FIRST[b.ordinal()] + p] = x;
                py[FIRST[b.ordinal()] + p] = y;
                x += b.width + 1;
                shelf = Math.max(shelf, b.height);
            }
        }
        if (y + shelf > H) {
            throw new IllegalStateException("block bitmaps need " + (y + shelf) + " atlas rows, have " + H);
        }
    }

    private BlockBitmaps() {}

    /**
     * BEHAVIOR: the atlas texel of picture {@code picture}'s top-left corner (absolute atlas x); a picture number
     * outside the bitmap's pictures is taken modulo their count.
     */
    public static int atlasX(BlockBitmap b, int picture) {
        return X0 + px[FIRST[b.ordinal()] + Math.floorMod(picture, b.pictures())];
    }

    /** The atlas row of the picture's top edge (see {@link #atlasX}). */
    public static int atlasY(BlockBitmap b, int picture) {
        return Y0 + py[FIRST[b.ordinal()] + Math.floorMod(picture, b.pictures())];
    }

    /** Rows of the packing block in use (for tests and capacity checks). */
    static int usedRows() {
        int rows = 0;
        for (BlockBitmap b : ALL) {
            if (b.inAtlas()) {
                for (int p = 0; p < b.pictures(); p++) {
                    rows = Math.max(rows, py[FIRST[b.ordinal()] + p] + b.height);
                }
            }
        }
        return rows;
    }

    /** Reads a resource's bytes ({@link #bake}); a test can feed its own. */
    @FunctionalInterface
    public interface Source {
        byte[] read(String file) throws IOException;
    }

    /** The bundled bitmaps from the classpath ({@link #DIR}). */
    public static final Source CLASSPATH = file -> {
        try (InputStream in = BlockBitmaps.class.getResourceAsStream(DIR + file)) {
            if (in == null) {
                throw new IOException("missing resource " + DIR + file);
            }
            return in.readAllBytes();
        }
    };

    /**
     * Load every atlas bitmap into the packing block's pixels ({@link #W} x {@link #H}, row-major, top row first).
     * <p>
     * BEHAVIOR: each picture of a file goes to its packed place; the file must be exactly the bitmap's width
     * times its pictures wide (or, for a rotating bitmap, one picture) and its height high, else
     * IllegalArgumentException naming the file (C: "image size is wrong").
     */
    public static int[] bake(Source src) throws IOException {
        int[] region = new int[W * H];
        int[] size = new int[2];
        for (BlockBitmap b : ALL) {
            if (!b.inAtlas()) {
                continue;
            }
            int[] img = PpmImage.parse(src.read(b.file), size);
            if (size[0] != b.width * b.pictures() || size[1] != b.height) {
                throw new IllegalArgumentException(b.file + ": image size is wrong (" + size[0] + "x" + size[1]
                    + ", expected " + b.width * b.pictures() + "x" + b.height + ")");
            }
            for (int p = 0; p < b.pictures(); p++) {
                int ox = px[FIRST[b.ordinal()] + p];
                int oy = py[FIRST[b.ordinal()] + p];
                for (int y = 0; y < b.height; y++) {
                    System.arraycopy(img, y * size[0] + p * b.width, region, (oy + y) * W + ox, b.width);
                }
            }
        }
        return region;
    }

    /**
     * A score window picture ({@link BlockBitmap#SCORE_BG} or {@link BlockBitmap#LOGO}), drawn from its own texture.
     * <p>
     * BEHAVIOR: the file's pixels; IllegalArgumentException when its size is not the bitmap's.
     */
    public static int[] loadSeparate(BlockBitmap b, Source src) throws IOException {
        int[] size = new int[2];
        int[] img = PpmImage.parse(src.read(b.file), size);
        if (size[0] != b.width || size[1] != b.height) {
            throw new IllegalArgumentException(b.file + ": image size is wrong (" + size[0] + "x" + size[1] + ")");
        }
        return img;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Picture {@code picture} of {@code b} over the rectangle with top-left (left, top) and size w x h, y up (it
     * extends down to {@code top - h}), untinted.
     * <p>
     * BEHAVIOR: one textured quad; the picture's top row at the top edge.
     */
    public static void draw(VertexBatch v, BlockBitmap b, int picture, float left, float top, float w, float h) {
        drawRows(v, b, picture, left, top, w, h, b.height);
    }

    /**
     * The top {@code rows} rows of a picture over the rectangle (C {@code PaintFuelSlice}: the fuel cell's level).
     * <p>
     * BEHAVIOR: rows are clamped to 0..height; 0 rows draw nothing.
     */
    public static void drawRows(VertexBatch v, BlockBitmap b, int picture, float left, float top, float w, float h,
                                int rows) {
        int r = Math.max(0, Math.min(b.height, rows));
        if (r == 0) {
            return;
        }
        float s = ObjectPainter.ATLAS_SIZE;
        float u0 = atlasX(b, picture) / s;
        float u1 = (atlasX(b, picture) + b.width) / s;
        float v0 = atlasY(b, picture) / s;
        float v1 = (atlasY(b, picture) + r) / s;
        v.texRect(left, top - h, left + w, top, u0, v1, u1, v0, WHITE);
    }

    /**
     * A rotating bitmap's picture turned by {@code dir} RES units (128 per turn, counter-clockwise, as C's
     * precomputed images: image {@code dir} of {@code images} is the picture turned by {@code dir * 128 / images}),
     * centred on (cx, cy), {@code side} wide and high.
     * <p>
     * BEHAVIOR: two triangles whose corners are the square's corners rotated about the centre.
     */
    public static void drawTurned(VertexBatch v, BlockBitmap b, float cx, float cy, float side, int dir) {
        double c = Trig.cosRes(dir);
        double sn = Trig.sinRes(dir);
        float h = side / 2;
        float s = ObjectPainter.ATLAS_SIZE;
        float u0 = atlasX(b, 0) / s;
        float u1 = (atlasX(b, 0) + b.width) / s;
        float vt = atlasY(b, 0) / s;
        float vb = (atlasY(b, 0) + b.height) / s;
        // corners: bottom-left, bottom-right, top-right, top-left (y up)
        float x0 = cx + (float) (-h * c + h * sn);
        float y0 = cy + (float) (-h * sn - h * c);
        float x1 = cx + (float) (h * c + h * sn);
        float y1 = cy + (float) (h * sn - h * c);
        float x2 = cx + (float) (h * c - h * sn);
        float y2 = cy + (float) (h * sn + h * c);
        float x3 = cx + (float) (-h * c - h * sn);
        float y3 = cy + (float) (-h * sn + h * c);
        v.texTriangle(x0, y0, u0, vb, x1, y1, u1, vb, x2, y2, u1, vt, WHITE);
        v.texTriangle(x0, y0, u0, vb, x2, y2, u1, vt, x3, y3, u0, vt, WHITE);
    }

    static final int WHITE = -1;
}
