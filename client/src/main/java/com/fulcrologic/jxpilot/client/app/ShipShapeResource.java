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
package com.fulcrologic.jxpilot.client.app;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * The C client's {@code shipShape} / {@code shipShapeFile} resources (default.c
 * {@code Get_shipshape_resource}): the shape string the client converts and sends.
 */
public final class ShipShapeResource {
    private ShipShapeResource() {}

    /** C {@code fgets(line, 1024, fp)}: a line is read in pieces of at most this many chars. */
    static final int LINE_MAX = 1023;

    /** Reads a whole file (an effect, faked in tests). */
    @FunctionalInterface
    public interface FileText {
        String read(String path) throws IOException;
    }

    /** The real file reader: Latin-1, as the C client reads bytes. */
    public static String readLatin1(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.ISO_8859_1);
    }

    /**
     * The shape string of a launch (C {@code Get_shipshape_resource}).
     * <p>
     * BEHAVIOR: each resource comes from the command line when given there, else from the resource file;
     * a missing one is "" (C's defaults: no shape, {@code SHIP_FILE} "").
     * BEHAVIOR: a non-empty shape without '(' is a name: when a shape file is set, its first line whose
     * {@link #lookup name} matches becomes the shape; no match keeps the name (which then converts to the
     * default ship, as in C).
     * BEHAVIOR: an unreadable shape file is reported on {@code err} ("path: message", C perror) and the
     * name is kept.
     *
     * @param cmdShape {@code --shape}/{@code --shipShape}, or null when not given
     * @param cmdFile  {@code --shipShapeFile}, or null when not given
     * @param rcShape  the resource file's {@code shipShape}, or null
     * @param rcFile   the resource file's {@code shipShapeFile}, or null
     */
    public static String resolve(String cmdShape, String cmdFile, String rcShape, String rcFile, FileText files,
                                 PrintStream err) {
        String shape = cmdShape != null ? cmdShape : (rcShape != null ? rcShape : "");
        String file = cmdFile != null ? cmdFile : (rcFile != null ? rcFile : "");
        if (shape.isEmpty() || shape.indexOf('(') >= 0 || file.isEmpty()) {
            return shape;
        }
        String text;
        try {
            text = files.read(file);
        } catch (IOException e) {
            err.println(file + ": " + (e instanceof NoSuchFileException ? "No such file or directory" : e.getMessage()));
            return shape;
        }
        String line = lookup(text, shape);
        return line != null ? line : shape;
    }

    /**
     * The shape file line named {@code name}, read as C's loop over {@code fgets} lines.
     * <p>
     * BEHAVIOR: lines keep their '\n' and are cut into pieces of at most {@value #LINE_MAX} chars (fgets).
     * BEHAVIOR: a line holding "(name:" (else "(NM:") names a shape: the name starts after that colon and its
     * spaces and ends before the next ')' (or at the end of the line, newline included); it must equal
     * {@code name} exactly.
     * BEHAVIOR: the first matching line is returned whole, else null.
     */
    public static String lookup(String text, String name) {
        int pos = 0;
        int n = text.length();
        while (pos < n) {
            int nl = text.indexOf('\n', pos);
            int end = Math.min(nl < 0 ? n : nl + 1, pos + LINE_MAX);
            String line = text.substring(pos, end);
            pos = end;
            if (name.equals(nameOf(line))) {
                return line;
            }
        }
        return null;
    }

    /** BEHAVIOR: the name a line declares (see {@link #lookup}), or null when it declares none. */
    static String nameOf(String line) {
        int at = line.indexOf("(name:");
        if (at < 0) {
            at = line.indexOf("(NM:");
        }
        if (at < 0) {
            return null;
        }
        int s = line.indexOf(':', at) + 1;
        while (s < line.length() && line.charAt(s) == ' ') {
            s++;
        }
        int close = line.indexOf(')', s);
        return close < 0 ? line.substring(s) : line.substring(s, close);
    }
}
