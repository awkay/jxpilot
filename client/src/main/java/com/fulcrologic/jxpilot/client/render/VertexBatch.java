package com.fulcrologic.jxpilot.client.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A vertex stream of lines and triangles for one shader: per vertex {@code x,y} (float32),
 * {@code u,v} (uint16 normalized) and an RGBA colour (4 x uint8 normalized), {@link #STRIDE} bytes.
 * Solid shapes use texture coordinate (0,0), which must sample white (the UI atlas keeps a white
 * block at its origin); text and the radar use real texture coordinates.
 * <p>
 * Draw order is preserved: vertices are grouped into commands, a new command starting whenever the
 * primitive mode changes, so emitting all fills and then all lines costs two draw calls.
 * <p>
 * Positions pass through an affine transform ({@code out = in * scale + translate}) so callers can
 * write world coordinates and get screen pixels (see {@link Camera#applyTo}).
 * <p>
 * A fixed-capacity batch hands itself to its {@link Sink} when full and continues empty; a growable
 * batch (build-time only) reallocates instead. Steady-state use of a fixed batch allocates nothing.
 */
public final class VertexBatch {
    /** Bytes per vertex. */
    public static final int STRIDE = 16;
    /** Primitive mode: line list. */
    public static final int LINES = 1;
    /** Primitive mode: triangle list. */
    public static final int TRIANGLES = 4;

    /** Receives a full batch (e.g. uploads and draws it). The batch is cleared afterwards. */
    public interface Sink {
        void flush(VertexBatch batch);
    }

    private ByteBuffer buf;
    private int capacity;
    private final boolean growable;
    private final Sink sink;
    private int count;

    private int[] cmdMode;
    private int[] cmdFirst;
    private int[] cmdCount;
    private int cmdN;

    private float sx = 1;
    private float sy = 1;
    private float tx;
    private float ty;
    private static final short SOLID_U = 0;
    private static final short SOLID_V = 0;

    private VertexBatch(ByteBuffer buf, int capacity, boolean growable, Sink sink, int maxCommands) {
        this.buf = buf.order(ByteOrder.LITTLE_ENDIAN);
        this.capacity = capacity;
        this.growable = growable;
        this.sink = sink;
        this.cmdMode = new int[maxCommands];
        this.cmdFirst = new int[maxCommands];
        this.cmdCount = new int[maxCommands];
    }

    /**
     * A fixed-capacity batch over {@code buf} (typically {@code MemoryUtil.memAlloc}); capacity is
     * {@code buf.capacity() / STRIDE} vertices. The sink is called when vertices or commands run out.
     */
    public static VertexBatch fixed(ByteBuffer buf, int maxCommands, Sink sink) {
        return new VertexBatch(buf, buf.capacity() / STRIDE, false, sink, maxCommands);
    }

    /** A heap batch that grows as needed, for building static meshes once. */
    public static VertexBatch growable(int initialVertices) {
        return new VertexBatch(ByteBuffer.allocate(initialVertices * STRIDE), initialVertices, true, null, 64);
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

    /** Drop all vertices and commands (the transform is kept). */
    public void clear() {
        count = 0;
        cmdN = 0;
    }

    /** Vertices written since the last clear. */
    public int vertexCount() { return count; }

    /** Number of draw commands. */
    public int commandCount() { return cmdN; }

    /** Primitive mode of command {@code i} ({@link #LINES} or {@link #TRIANGLES}). */
    public int commandMode(int i) { return cmdMode[i]; }

    /** First vertex of command {@code i}. */
    public int commandFirst(int i) { return cmdFirst[i]; }

    /** Vertex count of command {@code i}. */
    public int commandVertices(int i) { return cmdCount[i]; }

    /** Backing buffer; vertex {@code i} starts at byte {@code i * STRIDE}. */
    public ByteBuffer buffer() { return buf; }

    /** Vertex capacity (current, for growable batches). */
    public int capacity() { return capacity; }

    /** Vertex x as stored. */
    public float x(int i) { return buf.getFloat(i * STRIDE); }

    /** Vertex y as stored. */
    public float y(int i) { return buf.getFloat(i * STRIDE + 4); }

    /** Vertex u as stored (normalized). */
    public float u(int i) { return (buf.getShort(i * STRIDE + 8) & 0xFFFF) / 65535f; }

    /** Vertex v as stored (normalized). */
    public float v(int i) { return (buf.getShort(i * STRIDE + 10) & 0xFFFF) / 65535f; }

    /** Vertex colour as stored (packed like {@link Palette}). */
    public int color(int i) { return buf.getInt(i * STRIDE + 12); }

    // ------------------------------------------------------------------ primitives

    /** One line segment. */
    public void line(float x1, float y1, float x2, float y2, int rgba) {
        begin(LINES, 2);
        put(x1, y1, SOLID_U, SOLID_V, rgba);
        put(x2, y2, SOLID_U, SOLID_V, rgba);
    }

    /** One filled triangle. */
    public void triangle(float x1, float y1, float x2, float y2, float x3, float y3, int rgba) {
        begin(TRIANGLES, 3);
        put(x1, y1, SOLID_U, SOLID_V, rgba);
        put(x2, y2, SOLID_U, SOLID_V, rgba);
        put(x3, y3, SOLID_U, SOLID_V, rgba);
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

    /**
     * Reserve {@code n} vertices of {@code mode}.
     * <p>
     * BEHAVIOR: extends the last command if it has the same mode, else starts a new one.
     * BEHAVIOR: when vertices or commands would overflow, a fixed batch flushes to its sink and
     * clears; a growable batch doubles its storage.
     */
    private void begin(int mode, int n) {
        boolean newCmd = cmdN == 0 || cmdMode[cmdN - 1] != mode;
        if (count + n > capacity || (newCmd && cmdN == cmdMode.length)) {
            if (growable) {
                grow(n);
            } else {
                sink.flush(this);
                clear();
                newCmd = true;
            }
        }
        if (newCmd) {
            cmdMode[cmdN] = mode;
            cmdFirst[cmdN] = count;
            cmdCount[cmdN] = 0;
            cmdN++;
        }
        cmdCount[cmdN - 1] += n;
    }

    private void grow(int n) {
        if (count + n > capacity) {
            int cap = Math.max(capacity * 2, count + n);
            ByteBuffer nb = ByteBuffer.allocate(cap * STRIDE).order(ByteOrder.LITTLE_ENDIAN);
            nb.put(0, buf, 0, count * STRIDE);
            buf = nb;
            capacity = cap;
        }
        if (cmdN == cmdMode.length) {
            cmdMode = Arrays.copyOf(cmdMode, cmdN * 2);
            cmdFirst = Arrays.copyOf(cmdFirst, cmdN * 2);
            cmdCount = Arrays.copyOf(cmdCount, cmdN * 2);
        }
    }

    private void put(float x, float y, short u, short v, int rgba) {
        int o = count * STRIDE;
        buf.putFloat(o, x * sx + tx);
        buf.putFloat(o + 4, y * sy + ty);
        buf.putShort(o + 8, u);
        buf.putShort(o + 10, v);
        buf.putInt(o + 12, rgba);
        count++;
    }

    /** Clamp to 0..1 and scale to an unsigned 16-bit normalized value. */
    static short unorm16(float f) {
        float c = f < 0 ? 0 : (f > 1 ? 1 : f);
        return (short) Math.round(c * 65535f);
    }
}
