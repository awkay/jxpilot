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

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The C client's texture manager ({@code texture.c} {@code Texture_load}, {@code Texture_load_from_file}):
 * a texture comes from its file option when that names a readable XPM file, the plain name first and then each
 * directory of {@code texturePath} ({@code ':'}-separated), else from the built-in image (the XPilot
 * {@code rock4.xpm} for walls and decorations, {@code ball.xpm} for balls, bundled under {@code /textures/}).
 * Loading allocates; it runs when a texture option changes.
 */
public final class TextureFiles {
    /** Built-in wall and decoration texture (C {@code rock4_xpm}). */
    public static final String ROCK = "/textures/rock4.xpm";
    /** Built-in ball texture (C {@code ball_xpm}). */
    public static final String BALL = "/textures/ball.xpm";

    private TextureFiles() {}

    /**
     * Load a texture.
     * <p>
     * BEHAVIOR: a non-blank {@code file} is tried as given, then (unless absolute) as {@code dir/file} for each
     * non-empty {@code texturePath} directory; the first readable one is parsed ({@link XpmImage}). A file that
     * is found but does not parse is reported and the built-in image used; a file found nowhere is reported as C
     * does ("Can't find texture "file"") and the built-in image used; a blank {@code file} uses the built-in image.
     *
     * @param builtin classpath resource of the built-in image ({@link #ROCK} or {@link #BALL})
     */
    public static XpmImage load(String file, String texturePath, String builtin, PrintStream log) {
        String f = file == null ? "" : file.strip();
        if (!f.isEmpty()) {
            Path found = find(f, texturePath);
            if (found == null) {
                log.println("Can't find texture \"" + f + "\"");
            } else {
                try {
                    return XpmImage.parse(Files.readString(found, StandardCharsets.ISO_8859_1));
                } catch (IOException | IllegalArgumentException e) {
                    log.println("Can't load texture \"" + found + "\": " + e.getMessage());
                }
            }
        }
        return builtin(builtin);
    }

    /** BEHAVIOR: the readable file for {@code file}: as given, else in a {@code texturePath} directory; null if none. */
    static Path find(String file, String texturePath) {
        Path plain = Path.of(file);
        if (Files.isReadable(plain) && Files.isRegularFile(plain)) {
            return plain;
        }
        if (file.startsWith("/") || texturePath == null) {
            return null;
        }
        for (String dir : texturePath.split(":")) {
            if (dir.isEmpty()) {
                continue;
            }
            Path p = Path.of(dir, file);
            if (Files.isReadable(p) && Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** BEHAVIOR: a bundled image; IllegalStateException when the resource is missing or bad (a build error). */
    public static XpmImage builtin(String resource) {
        try (InputStream in = TextureFiles.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + resource);
            }
            return XpmImage.parse(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }
}
