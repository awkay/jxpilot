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

import com.fulcrologic.jxpilot.client.render.VertexBatch;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Thin OpenGL 3.3 core layer drawing {@link VertexBatch} streams with three programs:
 * <ul>
 *   <li>textured triangles from an ordinary vertex buffer;</li>
 *   <li>segments, expanded in the vertex shader into quads of their width in framebuffer pixels
 *   with square or butt caps (the sign bit of the stored width, see {@link VertexBatch#CAP_BUTT});</li>
 *   <li>points, expanded into axis-aligned squares. For static meshes drawn with a
 *   {@link #setPointScale point scale}, a point's side is a C size scaled by C's {@code WINSCALE}
 *   rule and its centre moves by {@code WinScale.squareShift} (background dots at any zoom).</li>
 * </ul>
 * Segments and points use vertex pulling: their records sit unchanged in buffer textures
 * ({@code RG32UI}, 3 texels per 24-byte segment; {@code RGBA32UI}, 1 texel per 16-byte point) and a
 * plain {@code glDrawArrays} of 6 vertices per item fetches them by {@code gl_VertexID / 6}. On
 * Apple's GL this is markedly cheaper per item than instancing, and a command's first item is just
 * the draw offset. Integer formats keep colours bit-exact (a float view could canonicalize the NaN
 * patterns that opaque colours form).
 */
public final class GlRenderer implements VertexBatch.Sink {
    private static final String TRI_VS = """
        #version 330 core
        layout(location = 0) in vec2 a_pos;
        layout(location = 1) in vec2 a_uv;
        layout(location = 2) in vec4 a_col;
        uniform vec4 u_xf;
        out vec2 v_uv;
        out vec4 v_col;
        void main() {
            gl_Position = vec4(a_pos * u_xf.xy + u_xf.zw, 0.0, 1.0);
            v_uv = a_uv;
            v_col = a_col;
        }
        """;
    private static final String TRI_FS = """
        #version 330 core
        in vec2 v_uv;
        in vec4 v_col;
        uniform sampler2D u_tex;
        out vec4 o_col;
        void main() {
            o_col = v_col * texture(u_tex, v_uv);
        }
        """;
    /**
     * Tiled fills (C {@code FillTiled}, textured walls and decorations): the texture coordinate is the
     * position times {@code u_tile.xy} plus {@code u_tile.zw}, sampled with GL_REPEAT from unit 3.
     */
    private static final String TILE_VS = """
        #version 330 core
        layout(location = 0) in vec2 a_pos;
        layout(location = 2) in vec4 a_col;
        uniform vec4 u_xf;
        uniform vec4 u_tile;
        out vec2 v_uv;
        out vec4 v_col;
        void main() {
            gl_Position = vec4(a_pos * u_xf.xy + u_xf.zw, 0.0, 1.0);
            v_uv = a_pos * u_tile.xy + u_tile.zw;
            v_col = a_col;
        }
        """;
    private static final String TILE_FS = """
        #version 330 core
        in vec2 v_uv;
        in vec4 v_col;
        uniform sampler2D u_tiletex;
        out vec4 o_col;
        void main() {
            o_col = v_col * texture(u_tiletex, v_uv);
        }
        """;
    /** Shared helpers: quad corner per vertex (along, side), pixel-space transforms, colour unpacking. */
    private static final String COMMON = """
        const vec2 CORNER[6] = vec2[6](vec2(0.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
                                       vec2(0.0, -1.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
        uniform vec4 u_xf;
        uniform vec2 u_view;
        uniform float u_ratio;
        out vec4 v_col;
        vec2 toPixels(vec2 p) { return ((p * u_xf.xy + u_xf.zw) + 1.0) * 0.5 * u_view; }
        vec4 fromPixels(vec2 p) { return vec4(p / u_view * 2.0 - 1.0, 0.0, 1.0); }
        vec4 unpack(uint c) {
            return vec4(float(c & 255u), float((c >> 8u) & 255u), float((c >> 16u) & 255u), float(c >> 24u)) / 255.0;
        }
        """;
    private static final String SEG_VS = """
        #version 330 core
        uniform usamplerBuffer u_items;
        """ + COMMON + """
        void main() {
            int i = gl_VertexID / 6;
            uvec2 a = texelFetch(u_items, 3 * i).xy;
            uvec2 b = texelFetch(u_items, 3 * i + 1).xy;
            uvec2 cw = texelFetch(u_items, 3 * i + 2).xy;
            vec2 p1 = toPixels(uintBitsToFloat(a));
            vec2 p2 = toPixels(uintBitsToFloat(b));
            vec2 d = p2 - p1;
            float len = length(d);
            vec2 t = len > 1e-4 ? d / len : vec2(1.0, 0.0);
            vec2 n = vec2(-t.y, t.x);
            float hw = 0.5 * abs(uintBitsToFloat(cw.y)) * u_ratio;
            float ext = (cw.y & 0x80000000u) != 0u ? 0.0 : hw;
            vec2 c = CORNER[gl_VertexID - 6 * i];
            gl_Position = fromPixels(mix(p1 - t * ext, p2 + t * ext, c.x) + n * (hw * c.y));
            v_col = unpack(cw.x);
        }
        """;
    private static final String PT_VS = """
        #version 330 core
        uniform usamplerBuffer u_items;
        uniform float u_winscale;
        """ + COMMON + """
        void main() {
            int i = gl_VertexID / 6;
            uvec4 r = texelFetch(u_items, i);
            float s = uintBitsToFloat(r.z);
            vec2 p = toPixels(uintBitsToFloat(r.xy));
            if (u_winscale > 0.0) {
                float w = max(1.0, floor(s * u_winscale + 0.5));
                float shift = ((w - 1.0) - (s - 1.0) * u_winscale) * 0.5 * u_ratio;
                p += vec2(shift, -shift);
                s = w;
            }
            float h = 0.5 * s * u_ratio;
            vec2 c = CORNER[gl_VertexID - 6 * i];
            gl_Position = fromPixels(p + vec2(mix(-h, h, c.x), h * c.y));
            v_col = unpack(r.w);
        }
        """;
    private static final String SOLID_FS = """
        #version 330 core
        in vec4 v_col;
        out vec4 o_col;
        void main() {
            o_col = v_col;
        }
        """;

    /** Texture units: the UI atlas, segment items, point items. */
    private static final int UNIT_SEGMENTS = 1;
    private static final int UNIT_POINTS = 2;
    private static final int UNIT_TILE = 3;
    /** Buffer-texture texels per segment (RG32UI, 8 bytes each). */
    static final int SEGMENT_TEXELS = VertexBatch.SEGMENT_STRIDE / 8;

    /** One program and its uniform locations; uniforms are re-sent when the shared values change. */
    private static final class Program {
        final int id;
        final int uXf;
        final int uView;
        final int uRatio;
        final int uWinscale;
        int version = -1;

        Program(int id) {
            this.id = id;
            uXf = glGetUniformLocation(id, "u_xf");
            uView = glGetUniformLocation(id, "u_view");
            uRatio = glGetUniformLocation(id, "u_ratio");
            uWinscale = glGetUniformLocation(id, "u_winscale");
        }
    }

    /** GL objects for the three streams of one batch or mesh. */
    private static final class Streams {
        int triVao;
        int triVbo;
        int segBuf;
        int segTex;
        int ptBuf;
        int ptTex;

        void delete() {
            glDeleteBuffers(triVbo);
            glDeleteBuffers(segBuf);
            glDeleteBuffers(ptBuf);
            glDeleteTextures(segTex);
            glDeleteTextures(ptTex);
            glDeleteVertexArrays(triVao);
        }
    }

    /** Buffers uploaded once, with their draw commands. */
    public static final class Mesh {
        final Streams s;
        final int[] mode;
        final int[] first;
        final int[] count;
        final int triangles;
        final int segments;
        final int points;

        Mesh(Streams s, VertexBatch b) {
            this.s = s;
            int c = b.commandCount();
            mode = new int[c];
            first = new int[c];
            count = new int[c];
            for (int i = 0; i < c; i++) {
                mode[i] = b.commandMode(i);
                first[i] = b.commandFirst(i);
                count[i] = b.commandLength(i);
            }
            triangles = b.vertexCount() / 3;
            segments = b.segmentCount();
            points = b.pointCount();
        }

        /** Size summary, e.g. for logging. */
        public String describe() {
            return segments + " segments, " + triangles + " triangles, " + points + " points";
        }
    }

    private final Program tri;
    private final Program seg;
    private final Program pt;
    private final Program tile;
    private final int uTile;
    private final int emptyVao;
    private final int maxTexels;
    private final Streams dyn = new Streams();
    private final float[] xf = {1, 1, 0, 0};
    private float viewW = 1;
    private float viewH = 1;
    private float ratio = 1;
    private float pointScale;
    private int version;
    private Program current;
    private int boundSegTex = -1;
    private int boundPtTex = -1;
    private int drawCalls;

    public GlRenderer(int texture) {
        tri = new Program(link(compile(GL_VERTEX_SHADER, TRI_VS), compile(GL_FRAGMENT_SHADER, TRI_FS)));
        seg = new Program(link(compile(GL_VERTEX_SHADER, SEG_VS), compile(GL_FRAGMENT_SHADER, SOLID_FS)));
        pt = new Program(link(compile(GL_VERTEX_SHADER, PT_VS), compile(GL_FRAGMENT_SHADER, SOLID_FS)));
        tile = new Program(link(compile(GL_VERTEX_SHADER, TILE_VS), compile(GL_FRAGMENT_SHADER, TILE_FS)));
        uTile = glGetUniformLocation(tile.id, "u_tile");
        glUseProgram(tile.id);
        glUniform1i(glGetUniformLocation(tile.id, "u_tiletex"), UNIT_TILE);
        glUseProgram(tri.id);
        glUniform1i(glGetUniformLocation(tri.id, "u_tex"), 0);
        glUseProgram(seg.id);
        glUniform1i(glGetUniformLocation(seg.id, "u_items"), UNIT_SEGMENTS);
        glUseProgram(pt.id);
        glUniform1i(glGetUniformLocation(pt.id, "u_items"), UNIT_POINTS);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        maxTexels = glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE);
        emptyVao = glGenVertexArrays();
        createStreams(dyn);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }

    /**
     * Fail early if a static mesh of this many segments or points could not be addressed by a buffer
     * texture, naming the limit that was hit.
     */
    public void checkCapacity(int segments, int points) {
        if ((long) segments * SEGMENT_TEXELS > maxTexels) {
            throw new IllegalStateException("GL_MAX_TEXTURE_BUFFER_SIZE is " + maxTexels + " texels; the mesh's "
                + segments + " segments need " + (long) segments * SEGMENT_TEXELS + " (" + SEGMENT_TEXELS + " each)");
        }
        if (points > maxTexels) {
            throw new IllegalStateException("GL_MAX_TEXTURE_BUFFER_SIZE is " + maxTexels + " texels; the mesh's "
                + points + " points need one each");
        }
    }

    /** Most segments one buffer texture can hold (GL 3.3 only guarantees 65536 texels, i.e. 21845). */
    public int maxSegments() {
        return maxTexels / SEGMENT_TEXELS;
    }

    /** Most points one buffer texture can hold. */
    public int maxPoints() {
        return maxTexels;
    }

    /** Create a stream set's GL objects; binds buffer textures, so the bound-texture caches are reset. */
    private void createStreams(Streams s) {
        s.triVao = glGenVertexArrays();
        s.triVbo = glGenBuffers();
        glBindVertexArray(s.triVao);
        glBindBuffer(GL_ARRAY_BUFFER, s.triVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, VertexBatch.STRIDE, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_UNSIGNED_SHORT, true, VertexBatch.STRIDE, 8);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, VertexBatch.STRIDE, 12);
        s.segBuf = glGenBuffers();
        s.ptBuf = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, s.segBuf);
        glBufferData(GL_TEXTURE_BUFFER, VertexBatch.SEGMENT_STRIDE, GL_STREAM_DRAW);
        glBindBuffer(GL_TEXTURE_BUFFER, s.ptBuf);
        glBufferData(GL_TEXTURE_BUFFER, VertexBatch.POINT_STRIDE, GL_STREAM_DRAW);
        s.segTex = glGenTextures();
        s.ptTex = glGenTextures();
        glActiveTexture(GL_TEXTURE0 + UNIT_SEGMENTS);
        glBindTexture(GL_TEXTURE_BUFFER, s.segTex);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RG32UI, s.segBuf);
        glActiveTexture(GL_TEXTURE0 + UNIT_POINTS);
        glBindTexture(GL_TEXTURE_BUFFER, s.ptTex);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, s.ptBuf);
        glActiveTexture(GL_TEXTURE0);
        boundSegTex = -1;
        boundPtTex = -1;
    }

    /** Set the position transform used by subsequent draws ({@code ndc = pos * xy + zw}). */
    public void setTransform(float[] t) {
        xf[0] = t[0];
        xf[1] = t[1];
        xf[2] = t[2];
        xf[3] = t[3];
        version++;
    }

    /** Framebuffer size and framebuffer pixels per logical pixel (line widths and point sizes). */
    public void setView(int width, int height, double pixelRatio) {
        viewW = width;
        viewH = height;
        ratio = (float) pixelRatio;
        version++;
    }

    /**
     * C {@code WINSCALE} factor for the point sizes of subsequent draws: the zoom for static meshes
     * whose points hold C sizes (background dots), 0 for batches whose sizes are already final.
     */
    public void setPointScale(double scale) {
        if ((float) scale != pointScale) {
            pointScale = (float) scale;
            version++;
        }
    }

    private void use(Program p) {
        if (current != p) {
            glUseProgram(p.id);
            current = p;
        }
        if (p.version != version) {
            glUniform4f(p.uXf, xf[0], xf[1], xf[2], xf[3]);
            if (p.uView >= 0) {
                glUniform2f(p.uView, viewW, viewH);
                glUniform1f(p.uRatio, ratio);
            }
            if (p.uWinscale >= 0) {
                glUniform1f(p.uWinscale, pointScale);
            }
            p.version = version;
        }
    }

    /** Upload and draw a dynamic batch (the caller clears it afterwards). */
    @Override
    public void flush(VertexBatch b) {
        if (b.commandCount() == 0) {
            return;
        }
        upload(GL_ARRAY_BUFFER, dyn.triVbo, b.triangleBuffer(), b.vertexCount() * VertexBatch.STRIDE);
        upload(GL_TEXTURE_BUFFER, dyn.segBuf, b.segmentBuffer(), b.segmentCount() * VertexBatch.SEGMENT_STRIDE);
        upload(GL_TEXTURE_BUFFER, dyn.ptBuf, b.pointBuffer(), b.pointCount() * VertexBatch.POINT_STRIDE);
        for (int i = 0; i < b.commandCount(); i++) {
            drawCommand(dyn, b.commandMode(i), b.commandFirst(i), b.commandLength(i));
        }
    }

    /** One {@code glBufferData} per non-empty stream (orphans the old storage). */
    private static void upload(int target, int buffer, ByteBuffer buf, int bytes) {
        if (bytes == 0) {
            return;
        }
        glBindBuffer(target, buffer);
        buf.limit(bytes).position(0);
        glBufferData(target, buf, GL_STREAM_DRAW);
        buf.clear();
    }

    private void drawCommand(Streams s, int mode, int first, int n) {
        switch (mode) {
            case VertexBatch.TRIANGLES -> {
                use(tri);
                glBindVertexArray(s.triVao);
                glDrawArrays(GL_TRIANGLES, first, n);
            }
            case VertexBatch.LINES -> {
                use(seg);
                if (boundSegTex != s.segTex) {
                    glActiveTexture(GL_TEXTURE0 + UNIT_SEGMENTS);
                    glBindTexture(GL_TEXTURE_BUFFER, s.segTex);
                    glActiveTexture(GL_TEXTURE0);
                    boundSegTex = s.segTex;
                }
                glBindVertexArray(emptyVao);
                glDrawArrays(GL_TRIANGLES, 6 * first, 6 * n);
            }
            default -> {
                use(pt);
                if (boundPtTex != s.ptTex) {
                    glActiveTexture(GL_TEXTURE0 + UNIT_POINTS);
                    glBindTexture(GL_TEXTURE_BUFFER, s.ptTex);
                    glActiveTexture(GL_TEXTURE0);
                    boundPtTex = s.ptTex;
                }
                glBindVertexArray(emptyVao);
                glDrawArrays(GL_TRIANGLES, 6 * first, 6 * n);
            }
        }
        drawCalls++;
    }

    /** Upload a (growable) batch as a static mesh. */
    public Mesh upload(VertexBatch b) {
        checkCapacity(b.segmentCount(), b.pointCount());
        Streams s = new Streams();
        createStreams(s);
        uploadStatic(GL_ARRAY_BUFFER, s.triVbo, b.triangleBuffer(), b.vertexCount() * VertexBatch.STRIDE);
        uploadStatic(GL_TEXTURE_BUFFER, s.segBuf, b.segmentBuffer(), b.segmentCount() * VertexBatch.SEGMENT_STRIDE);
        uploadStatic(GL_TEXTURE_BUFFER, s.ptBuf, b.pointBuffer(), b.pointCount() * VertexBatch.POINT_STRIDE);
        return new Mesh(s, b);
    }

    private static void uploadStatic(int target, int buffer, ByteBuffer src, int bytes) {
        if (bytes == 0) {
            return;
        }
        ByteBuffer direct = MemoryUtil.memAlloc(bytes);
        try {
            direct.put(0, src, 0, bytes);
            glBindBuffer(target, buffer);
            glBufferData(target, direct, GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(direct);
        }
    }

    /**
     * Draw a static mesh's triangles filled with a repeating texture (C {@code FillTiled}): a vertex at mesh
     * position p samples the texture at {@code p * (su, sv) + (ou, ov)} (texture units, wrapping), modulated by
     * the vertex colour. Other commands of the mesh are skipped.
     */
    public void drawTiled(Mesh m, int texture, float su, float sv, float ou, float ov) {
        use(tile);
        glUniform4f(uTile, su, sv, ou, ov);
        glActiveTexture(GL_TEXTURE0 + UNIT_TILE);
        glBindTexture(GL_TEXTURE_2D, texture);
        glActiveTexture(GL_TEXTURE0);
        glBindVertexArray(m.s.triVao);
        for (int i = 0; i < m.mode.length; i++) {
            if (m.mode[i] == VertexBatch.TRIANGLES) {
                glDrawArrays(GL_TRIANGLES, m.first[i], m.count[i]);
                drawCalls++;
            }
        }
    }

    /** A repeating, nearest-sampled RGBA texture from packed pixels (row 0 at the top) for {@link #drawTiled}. */
    public static int tileTexture(int width, int height, int[] pixels) {
        int t = glGenTextures();
        glActiveTexture(GL_TEXTURE0 + UNIT_TILE);
        glBindTexture(GL_TEXTURE_2D, t);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        java.nio.IntBuffer b = MemoryUtil.memAllocInt(width * height);
        try {
            b.put(pixels, 0, width * height).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, b);
        } finally {
            MemoryUtil.memFree(b);
        }
        glActiveTexture(GL_TEXTURE0);
        return t;
    }

    /** Release a {@link #tileTexture} (0 is ignored). */
    public static void deleteTexture(int t) {
        if (t != 0) {
            glDeleteTextures(t);
        }
    }

    /** Draw a static mesh with the current transform. */
    public void draw(Mesh m) {
        for (int i = 0; i < m.mode.length; i++) {
            drawCommand(m.s, m.mode[i], m.first[i], m.count[i]);
        }
    }

    public void delete(Mesh m) {
        if (m != null) {
            if (boundSegTex == m.s.segTex) {
                boundSegTex = -1;
            }
            if (boundPtTex == m.s.ptTex) {
                boundPtTex = -1;
            }
            m.s.delete();
        }
    }

    /** Draw calls since the last {@link #resetStats()}. */
    public int drawCalls() { return drawCalls; }

    public void resetStats() { drawCalls = 0; }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Shader compile failed: " + glGetShaderInfoLog(s));
        }
        return s;
    }

    private static int link(int vs, int fs) {
        int p = glCreateProgram();
        glAttachShader(p, vs);
        glAttachShader(p, fs);
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Program link failed: " + glGetProgramInfoLog(p));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        return p;
    }

    public void dispose() {
        dyn.delete();
        glDeleteVertexArrays(emptyVao);
        glDeleteProgram(tri.id);
        glDeleteProgram(seg.id);
        glDeleteProgram(pt.id);
        glDeleteProgram(tile.id);
    }
}
