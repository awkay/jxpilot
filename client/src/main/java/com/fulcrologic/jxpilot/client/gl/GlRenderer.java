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
 * Thin OpenGL 3.3 core layer: one shader (position transform, texture x vertex colour), the
 * dynamic stream buffer that {@link VertexBatch} flushes into, and static meshes.
 */
public final class GlRenderer implements VertexBatch.Sink {
    private static final String VS = """
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
    private static final String FS = """
        #version 330 core
        in vec2 v_uv;
        in vec4 v_col;
        uniform sampler2D u_tex;
        out vec4 o_col;
        void main() {
            o_col = v_col * texture(u_tex, v_uv);
        }
        """;

    /** A vertex buffer uploaded once, with its draw commands. */
    public static final class Mesh {
        final int vao;
        final int vbo;
        final int[] mode;
        final int[] first;
        final int[] count;

        Mesh(int vao, int vbo, int[] mode, int[] first, int[] count) {
            this.vao = vao;
            this.vbo = vbo;
            this.mode = mode;
            this.first = first;
            this.count = count;
        }

        public int vertexCount() {
            int n = 0;
            for (int c : count) {
                n += c;
            }
            return n;
        }
    }

    private final int program;
    private final int uXf;
    private final int dynVao;
    private final int dynVbo;
    private final int dynBytes;
    private final float[] xf = new float[4];
    private int drawCalls;

    public GlRenderer(int dynamicBytes, int texture) {
        program = link(compile(GL_VERTEX_SHADER, VS), compile(GL_FRAGMENT_SHADER, FS));
        uXf = glGetUniformLocation(program, "u_xf");
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "u_tex"), 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        dynBytes = dynamicBytes;
        dynVao = glGenVertexArrays();
        dynVbo = glGenBuffers();
        glBindVertexArray(dynVao);
        glBindBuffer(GL_ARRAY_BUFFER, dynVbo);
        glBufferData(GL_ARRAY_BUFFER, dynBytes, GL_STREAM_DRAW);
        attribs();
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void attribs() {
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, VertexBatch.STRIDE, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_UNSIGNED_SHORT, true, VertexBatch.STRIDE, 8);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, VertexBatch.STRIDE, 12);
    }

    /** Set the position transform used by subsequent draws ({@code ndc = pos * xy + zw}). */
    public void setTransform(float[] t) {
        glUniform4f(uXf, t[0], t[1], t[2], t[3]);
    }

    /** Set the transform from four floats. */
    public void setTransform(float sx, float sy, float tx, float ty) {
        glUniform4f(uXf, sx, sy, tx, ty);
    }

    /** Upload and draw a dynamic batch's vertices (the caller clears it afterwards). */
    @Override
    public void flush(VertexBatch b) {
        int n = b.vertexCount();
        if (n == 0) {
            return;
        }
        glBindVertexArray(dynVao);
        glBindBuffer(GL_ARRAY_BUFFER, dynVbo);
        glBufferData(GL_ARRAY_BUFFER, dynBytes, GL_STREAM_DRAW); // orphan
        ByteBuffer buf = b.buffer();
        buf.limit(n * VertexBatch.STRIDE).position(0);
        glBufferSubData(GL_ARRAY_BUFFER, 0, buf);
        buf.clear();
        for (int i = 0; i < b.commandCount(); i++) {
            glDrawArrays(glMode(b.commandMode(i)), b.commandFirst(i), b.commandVertices(i));
            drawCalls++;
        }
    }

    /** Upload a (growable) batch as a static mesh. */
    public Mesh upload(VertexBatch b) {
        int n = b.vertexCount();
        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        ByteBuffer direct = MemoryUtil.memAlloc(Math.max(1, n * VertexBatch.STRIDE));
        try {
            direct.put(0, b.buffer(), 0, n * VertexBatch.STRIDE);
            direct.limit(n * VertexBatch.STRIDE);
            glBufferData(GL_ARRAY_BUFFER, direct, GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(direct);
        }
        attribs();
        int c = b.commandCount();
        int[] mode = new int[c];
        int[] first = new int[c];
        int[] count = new int[c];
        for (int i = 0; i < c; i++) {
            mode[i] = glMode(b.commandMode(i));
            first[i] = b.commandFirst(i);
            count[i] = b.commandVertices(i);
        }
        return new Mesh(vao, vbo, mode, first, count);
    }

    /** Draw a static mesh with the current transform. */
    public void draw(Mesh m) {
        glBindVertexArray(m.vao);
        for (int i = 0; i < m.mode.length; i++) {
            glDrawArrays(m.mode[i], m.first[i], m.count[i]);
            drawCalls++;
        }
    }

    public void delete(Mesh m) {
        if (m != null) {
            glDeleteBuffers(m.vbo);
            glDeleteVertexArrays(m.vao);
        }
    }

    /** Draw calls since the last {@link #resetStats()}. */
    public int drawCalls() { return drawCalls; }

    public void resetStats() { drawCalls = 0; }

    private static int glMode(int mode) {
        return mode == VertexBatch.LINES ? GL_LINES : GL_TRIANGLES;
    }

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
        glDeleteBuffers(dynVbo);
        glDeleteVertexArrays(dynVao);
        glDeleteProgram(program);
    }
}
