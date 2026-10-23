package com.fulcrologic.jxpilot.client.gl;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.stb.STBImageWrite.stbi_flip_vertically_on_write;
import static org.lwjgl.stb.STBImageWrite.stbi_write_png;

/** Reads the current framebuffer and writes it as a PNG. */
public final class Screenshot {
    private Screenshot() {}

    public static void save(Path file, int width, int height) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ByteBuffer px = MemoryUtil.memAlloc(width * height * 4);
        try {
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadBuffer(GL_BACK);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, px);
            stbi_flip_vertically_on_write(true);
            if (!stbi_write_png(file.toString(), width, height, 4, px, width * 4)) {
                throw new IOException("stbi_write_png failed for " + file);
            }
        } finally {
            MemoryUtil.memFree(px);
        }
    }
}
