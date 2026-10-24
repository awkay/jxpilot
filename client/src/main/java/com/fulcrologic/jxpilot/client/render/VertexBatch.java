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
package com.fulcrologic.jxpilot.client.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Geometry for one frame (or one static mesh) in three streams, drawn in submission order:
 * <ul>
 *   <li><b>triangles</b> ({@link #TRIANGLES}): per vertex {@code x,y} (float32), {@code u,v} (uint16
 *   normalized) and an RGBA colour (4 x uint8 normalized), {@link #STRIDE} bytes. Solid shapes use
 *   texture coordinate (0,0), which must sample white (the UI atlas keeps a white block at its
 *   origin); text and the radar use real texture coordinates.</li>
 *   <li><b>segments</b> ({@link #LINES}): one instance per line, {@code x1,y1,x2,y2} (float32), RGBA,
 *   width (float32, logical pixels; its sign bit set for butt caps), {@link #SEGMENT_STRIDE} bytes. The
 *   GL layer expands each into a quad of that width, with square caps (X11 {@code CapProjecting}, which
 *   is how X11's zero-width lines cover their end pixels) or butt caps ({@code CapButt}, C's 4-pixel
 *   missiles and 3-pixel lasers), so lines keep XPilot's X11 look on HiDPI at any zoom.</li>
 *   <li><b>points</b> ({@link #POINTS}): one instance per axis-aligned square, centre {@code x,y}
 *   (float32), side (float32, logical pixels), RGBA, {@link #POINT_STRIDE} bytes; for map dots, sparks
 *   and debris, which XPilot draws a fixed number of pixels big.</li>
 * </ul>
 * Draw order is preserved: items are grouped into commands, a new command starting whenever the
 * primitive kind changes; a command's first/length index into its kind's stream.
 * <p>
 * Positions pass through an affine transform ({@code out = in * scale + translate}) so callers can
 * write world coordinates and get screen pixels (see {@link Camera#applyTo}); widths and point sizes
 * are not transformed.
 * <p>
 * A fixed-capacity batch hands itself to its {@link Sink} when any stream or the command list is
 * full and continues empty; a growable batch (build-time only) reallocates instead. Steady-state use
 * of a fixed batch allocates nothing.
 */
public final class VertexBatch {
    /** Bytes per triangle vertex. */
    public static final int STRIDE = 16;
    /** Bytes per segment instance. */
    public static final int SEGMENT_STRIDE = 24;
    /** Bytes per point instance. */
    public static final int POINT_STRIDE = 16;
    /** Primitive kind: point instances. */
    public static final int POINTS = 0;
    /** Primitive kind: segment instances. */
    public static final int LINES = 1;
    /** Primitive kind: triangle list. */
    public static final int TRIANGLES = 4;
    /** Line width in logical pixels until {@link #setLineWidth} (XPilot's X11 lines are 1 pixel). */
    public static final float DEFAULT_LINE_WIDTH = 1;
    /** Line cap: the segment extends half its width past each end (the default). */
    public static final int CAP_SQUARE = 0;
    /** Line cap: the segment ends exactly at its end points (X11 {@code CapButt}). */
    public static final int CAP_BUTT = 1;

    /** Receives a full batch (e.g. uploads and draws it). The batch is cleared afterwards. */
    public interface Sink {
        void flush(VertexBatch batch);
    }

    /** One stream: a buffer of fixed-stride records. */
    private static final class Stream {
        ByteBuffer buf;
        final int stride;
        int count;
        int capacity;

        Stream(ByteBuffer buf, int stride) {
            this.buf = buf.order(ByteOrder.LITTLE_ENDIAN);
            this.stride = stride;
            this.capacity = buf.capacity() / stride;
        }

        void grow(int n) {
            if (count + n > capacity) {
                int cap = Math.max(capacity * 2, count + n);
                ByteBuffer nb = ByteBuffer.allocate(cap * stride).order(ByteOrder.LITTLE_ENDIAN);
                nb.put(0, buf, 0, count * stride);
                buf = nb;
                capacity = cap;
            }
        }
    }

    private final Stream tri;
    private final Stream seg;
    private final Stream pt;
    private final boolean growable;
    private final Sink sink;

    private int[] cmdMode;
    private int[] cmdFirst;
    private int[] cmdLength;
    private int cmdN;

    private float sx = 1;
    private float sy = 1;
    private float tx;
    private float ty;
    private float lineWidth = DEFAULT_LINE_WIDTH;
    private int lineCap = CAP_SQUARE;
    private static final short SOLID_U = 0;
    private static final short SOLID_V = 0;

    private VertexBatch(ByteBuffer tri, ByteBuffer seg, ByteBuffer pt, boolean growable, Sink sink, int maxCommands) {
        this.tri = new Stream(tri, STRIDE);
        this.seg = new Stream(seg, SEGMENT_STRIDE);
        this.pt = new Stream(pt, POINT_STRIDE);
        this.growable = growable;
        this.sink = sink;
        this.cmdMode = new int[maxCommands];
        this.cmdFirst = new int[maxCommands];
        this.cmdLength = new int[maxCommands];
    }

    /**
     * A fixed-capacity batch over three buffers (typically {@code MemoryUtil.memAlloc}); each stream's
     * capacity is its buffer's capacity divided by its stride. The sink is called when a stream or the
     * commands run out.
     */
    public static VertexBatch fixed(ByteBuffer triangles, ByteBuffer segments, ByteBuffer points, int maxCommands,
                                    Sink sink) {
        return new VertexBatch(triangles, segments, points, false, sink, maxCommands);
    }

    /** A heap batch whose streams start with room for {@code initial} items each and grow, for building meshes once. */
    public static VertexBatch growable(int initial) {
        return new VertexBatch(ByteBuffer.allocate(initial * STRIDE), ByteBuffer.allocate(initial * SEGMENT_STRIDE),
            ByteBuffer.allocate(initial * POINT_STRIDE), true, null, 64);
    }

    // ------------------------------------------------------------------ state

    /** Set the position transform {@code out = in * (sx,sy) + (tx,ty)}. */
    public void setTransform(float sx, float sy, float tx, float ty) {
        this.sx = sx;
        this.sy = sy;
        this.tx = tx;
        this.ty = ty;
    }

    /** Positions are written unchanged. */
    public void identity() {
        setTransform(1, 1, 0, 0);
    }

    /** Transform scale x. */
    public float scaleX() { return sx; }

    /** Transform scale y. */
    public float scaleY() { return sy; }

    /** Transform translation x. */
    public float translateX() { return tx; }

    /** Transform translation y. */
    public float translateY() { return ty; }

    /** Width in logical pixels of subsequent {@link #line} calls. */
    public void setLineWidth(float w) { this.lineWidth = w; }

    /** Current line width in logical pixels. */
    public float lineWidth() { return lineWidth; }

    /** Cap ({@link #CAP_SQUARE} or {@link #CAP_BUTT}) of subsequent {@link #line} calls. */
    public void setLineCap(int cap) { this.lineCap = cap; }

    /** Current line cap. */
    public int lineCap() { return lineCap; }

    /** Drop all items and commands (the transform, line width and cap are kept). */
    public void clear() {
        tri.count = 0;
        seg.count = 0;
        pt.count = 0;
        cmdN = 0;
    }

    /** Triangle vertices written since the last clear. */
    public int vertexCount() { return tri.count; }

    /** Segment instances written since the last clear. */
    public int segmentCount() { return seg.count; }

    /** Point instances written since the last clear. */
    public int pointCount() { return pt.count; }

    /** Number of draw commands. */
    public int commandCount() { return cmdN; }

    /** Primitive kind of command {@code i} ({@link #TRIANGLES}, {@link #LINES} or {@link #POINTS}). */
    public int commandMode(int i) { return cmdMode[i]; }

    /** First item of command {@code i} in its kind's stream. */
    public int commandFirst(int i) { return cmdFirst[i]; }

    /** Items (vertices, segments or points) in command {@code i}. */
    public int commandLength(int i) { return cmdLength[i]; }

    /** Triangle vertex buffer; vertex {@code i} starts at byte {@code i * STRIDE}. */
    public ByteBuffer triangleBuffer() { return tri.buf; }

    /** Segment buffer; segment {@code i} starts at byte {@code i * SEGMENT_STRIDE}. */
    public ByteBuffer segmentBuffer() { return seg.buf; }

    /** Point buffer; point {@code i} starts at byte {@code i * POINT_STRIDE}. */
    public ByteBuffer pointBuffer() { return pt.buf; }

    /** Triangle vertex capacity (current, for growable batches). */
    public int triangleCapacity() { return tri.capacity; }

    /** Segment capacity. */
    public int segmentCapacity() { return seg.capacity; }

    /** Point capacity. */
    public int pointCapacity() { return pt.capacity; }

    /** Triangle vertex x as stored. */
    public float x(int i) { return tri.buf.getFloat(i * STRIDE); }

    /** Triangle vertex y as stored. */
    public float y(int i) { return tri.buf.getFloat(i * STRIDE + 4); }

    /** Triangle vertex u as stored (normalized). */
    public float u(int i) { return (tri.buf.getShort(i * STRIDE + 8) & 0xFFFF) / 65535f; }

    /** Triangle vertex v as stored (normalized). */
    public float v(int i) { return (tri.buf.getShort(i * STRIDE + 10) & 0xFFFF) / 65535f; }

    /** Triangle vertex colour as stored (packed like {@link Palette}). */
    public int color(int i) { return tri.buf.getInt(i * STRIDE + 12); }

    /** Segment start x as stored. */
    public float segX1(int i) { return seg.buf.getFloat(i * SEGMENT_STRIDE); }

    /** Segment start y as stored. */
    public float segY1(int i) { return seg.buf.getFloat(i * SEGMENT_STRIDE + 4); }

    /** Segment end x as stored. */
    public float segX2(int i) { return seg.buf.getFloat(i * SEGMENT_STRIDE + 8); }

    /** Segment end y as stored. */
    public float segY2(int i) { return seg.buf.getFloat(i * SEGMENT_STRIDE + 12); }

    /** Segment colour. */
    public int segColor(int i) { return seg.buf.getInt(i * SEGMENT_STRIDE + 16); }

    /** Segment width in logical pixels. */
    public float segWidth(int i) { return Math.abs(seg.buf.getFloat(i * SEGMENT_STRIDE + 20)); }

    /** Segment cap: {@link #CAP_BUTT} when the stored width's sign bit is set, else {@link #CAP_SQUARE}. */
    public int segCap(int i) { return seg.buf.getInt(i * SEGMENT_STRIDE + 20) < 0 ? CAP_BUTT : CAP_SQUARE; }

    /** Point centre x as stored. */
    public float pointX(int i) { return pt.buf.getFloat(i * POINT_STRIDE); }

    /** Point centre y as stored. */
    public float pointY(int i) { return pt.buf.getFloat(i * POINT_STRIDE + 4); }

    /** Point side in logical pixels. */
    public float pointSize(int i) { return pt.buf.getFloat(i * POINT_STRIDE + 8); }

    /** Point colour. */
    public int pointColor(int i) { return pt.buf.getInt(i * POINT_STRIDE + 12); }

    // ------------------------------------------------------------------ primitives

    /**
     * One line segment of the current {@link #lineWidth} and {@link #lineCap}.
     * <p>
     * BEHAVIOR: the width is stored as is for square caps and negated (sign bit set) for butt caps.
     */
    public void line(float x1, float y1, float x2, float y2, int rgba) {
        begin(LINES, 1);
        int o = seg.count * SEGMENT_STRIDE;
        ByteBuffer b = seg.buf;
        b.putFloat(o, x1 * sx + tx);
        b.putFloat(o + 4, y1 * sy + ty);
        b.putFloat(o + 8, x2 * sx + tx);
        b.putFloat(o + 12, y2 * sy + ty);
        b.putInt(o + 16, rgba);
        b.putInt(o + 20, Float.floatToRawIntBits(lineWidth) | (lineCap == CAP_BUTT ? 0x80000000 : 0));
        seg.count++;
    }

    /** A filled square of side {@code size} logical pixels centred on (x, y). */
    public void point(float x, float y, float size, int rgba) {
        begin(POINTS, 1);
        int o = pt.count * POINT_STRIDE;
        ByteBuffer b = pt.buf;
        b.putFloat(o, x * sx + tx);
        b.putFloat(o + 4, y * sy + ty);
        b.putFloat(o + 8, size);
        b.putInt(o + 12, rgba);
        pt.count++;
    }

    /** One filled triangle. */
    public void triangle(float x1, float y1, float x2, float y2, float x3, float y3, int rgba) {
        begin(TRIANGLES, 3);
        put(x1, y1, SOLID_U, SOLID_V, rgba);
        put(x2, y2, SOLID_U, SOLID_V, rgba);
        put(x3, y3, SOLID_U, SOLID_V, rgba);
    }

    /** One textured triangle: texture coordinates (u, v) per corner. */
    public void texTriangle(float x1, float y1, float u1, float v1, float x2, float y2, float u2, float v2,
                            float x3, float y3, float u3, float v3, int rgba) {
        begin(TRIANGLES, 3);
        put(x1, y1, unorm16(u1), unorm16(v1), rgba);
        put(x2, y2, unorm16(u2), unorm16(v2), rgba);
        put(x3, y3, unorm16(u3), unorm16(v3), rgba);
    }

    /** Filled axis-aligned rectangle with corners (x0,y0) and (x1,y1), as two triangles. */
    public void fillRect(float x0, float y0, float x1, float y1, int rgba) {
        quad(x0, y0, x1, y1, SOLID_U, SOLID_V, SOLID_U, SOLID_V, rgba);
    }

    /** Rectangle outline: bottom, right, top, left. */
    public void strokeRect(float x0, float y0, float x1, float y1, int rgba) {
        line(x0, y0, x1, y0, rgba);
        line(x1, y0, x1, y1, rgba);
        line(x1, y1, x0, y1, rgba);
        line(x0, y1, x0, y0, rgba);
    }

    /**
     * Textured rectangle (two triangles) with texture coordinates (u0,v0) at (x0,y0) and (u1,v1)
     * at (x1,y1).
     */
    public void texRect(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int rgba) {
        quad(x0, y0, x1, y1, unorm16(u0), unorm16(v0), unorm16(u1), unorm16(v1), rgba);
    }

    private void quad(float x0, float y0, float x1, float y1, short u0, short v0, short u1, short v1, int rgba) {
        begin(TRIANGLES, 6);
        put(x0, y0, u0, v0, rgba);
        put(x1, y0, u1, v0, rgba);
        put(x1, y1, u1, v1, rgba);
        put(x0, y0, u0, v0, rgba);
        put(x1, y1, u1, v1, rgba);
        put(x0, y1, u0, v1, rgba);
    }

    // ------------------------------------------------------------------ internals

    private Stream stream(int mode) {
        return mode == TRIANGLES ? tri : (mode == LINES ? seg : pt);
    }

    /**
     * Reserve {@code n} items of {@code mode}.
     * <p>
     * BEHAVIOR: extends the last command if it has the same kind, else starts a new one at the
     * kind's current stream position.
     * BEHAVIOR: when the stream or the command list would overflow, a fixed batch flushes to its sink
     * and clears; a growable batch doubles that storage.
     */
    private void begin(int mode, int n) {
        Stream s = stream(mode);
        boolean newCmd = cmdN == 0 || cmdMode[cmdN - 1] != mode;
        if (s.count + n > s.capacity || (newCmd && cmdN == cmdMode.length)) {
            if (growable) {
                s.grow(n);
                growCommands();
            } else {
                sink.flush(this);
                clear();
                newCmd = true;
            }
        }
        if (newCmd) {
            cmdMode[cmdN] = mode;
            cmdFirst[cmdN] = s.count;
            cmdLength[cmdN] = 0;
            cmdN++;
        }
        cmdLength[cmdN - 1] += n;
    }

    private void growCommands() {
        if (cmdN == cmdMode.length) {
            cmdMode = Arrays.copyOf(cmdMode, cmdN * 2);
            cmdFirst = Arrays.copyOf(cmdFirst, cmdN * 2);
            cmdLength = Arrays.copyOf(cmdLength, cmdN * 2);
        }
    }

    private void put(float x, float y, short u, short v, int rgba) {
        int o = tri.count * STRIDE;
        ByteBuffer b = tri.buf;
        b.putFloat(o, x * sx + tx);
        b.putFloat(o + 4, y * sy + ty);
        b.putShort(o + 8, u);
        b.putShort(o + 10, v);
        b.putInt(o + 12, rgba);
        tri.count++;
    }

    /** Clamp to 0..1 and scale to an unsigned 16-bit normalized value. */
    static short unorm16(float f) {
        float c = f < 0 ? 0 : (f > 1 ? 1 : f);
        return (short) Math.round(c * 65535f);
    }
}
