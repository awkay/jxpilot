package com.fulcrologic.jxpilot.client.gl;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;

/** A GLFW window with an OpenGL 3.3 core, forward-compatible context. */
public final class Window {
    private final long handle;
    private int fbWidth;
    private int fbHeight;
    private int winWidth;
    private int winHeight;

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

    /** Re-read window and framebuffer sizes (call once per frame; cheap). */
    public void refreshSize() {
        try (MemoryStack s = MemoryStack.stackPush()) {
            IntBuffer w = s.mallocInt(1);
            IntBuffer h = s.mallocInt(1);
            glfwGetFramebufferSize(handle, w, h);
            fbWidth = w.get(0);
            fbHeight = h.get(0);
            glfwGetWindowSize(handle, w, h);
            winWidth = w.get(0);
            winHeight = h.get(0);
        }
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
