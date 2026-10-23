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

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;


import static org.lwjgl.glfw.GLFW.*;

/** A GLFW window with an OpenGL 3.3 core, forward-compatible context. */
public final class Window {
    private final long handle;
    private int fbWidth;
    private int fbHeight;
    private int winWidth;
    private int winHeight;
    private final int[] w1 = new int[1];
    private final int[] h1 = new int[1];

    public Window(String title, int width, int height, boolean visible, boolean vsync) {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("glfwInit failed");
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, visible ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_SCALE_TO_MONITOR, GLFW_TRUE);
        glfwWindowHint(GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW_TRUE);
        handle = glfwCreateWindow(width, height, title, 0, 0);
        if (handle == 0) {
            throw new IllegalStateException("glfwCreateWindow failed");
        }
        glfwMakeContextCurrent(handle);
        GL.createCapabilities();
        glfwSwapInterval(vsync ? 1 : 0);
        refreshSize();
    }

    public long handle() { return handle; }

    /**
     * Re-read window and framebuffer sizes (call once per frame). Uses preallocated arrays: a
     * {@code MemoryStack} buffer would allocate an {@code IntBuffer} wrapper per call.
     */
    public void refreshSize() {
        glfwGetFramebufferSize(handle, w1, h1);
        fbWidth = w1[0];
        fbHeight = h1[0];
        glfwGetWindowSize(handle, w1, h1);
        winWidth = w1[0];
        winHeight = h1[0];
    }

    public int framebufferWidth() { return fbWidth; }

    public int framebufferHeight() { return fbHeight; }

    /** Framebuffer pixels per window (logical) pixel. */
    public double pixelRatio() {
        return winWidth == 0 ? 1 : (double) fbWidth / winWidth;
    }

    public boolean shouldClose() { return glfwWindowShouldClose(handle); }

    public void close() { glfwSetWindowShouldClose(handle, true); }

    public void setTitle(CharSequence t) { glfwSetWindowTitle(handle, t); }

    public void swap() { glfwSwapBuffers(handle); }

    public void dispose() {
        glfwDestroyWindow(handle);
        glfwTerminate();
        GLFWErrorCallback cb = glfwSetErrorCallback(null);
        if (cb != null) {
            cb.free();
        }
    }
}
