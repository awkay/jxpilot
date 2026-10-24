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
package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The XPilot 4.5.5 maps that ship inside the jars, as classpath resources under {@link #DIR}.
 * <p>
 * The build copies them byte for byte from the repository's {@code maps/} (see {@code maps/README.md}, which is
 * bundled next to them). A jar cannot list a directory, so {@link #INDEX} names them, one file per line.
 * A map is known by its file name without {@code .xp}; {@link #find} matches names case-insensitively.
 */
public final class BundledMaps {
    /** Classpath directory of the bundled maps. */
    public static final String DIR = "/xpilot/maps/";
    /** Classpath resource listing the bundled map files. */
    public static final String INDEX = DIR + "index.txt";
    /** The extension every bundled map has. */
    public static final String EXTENSION = ".xp";

    private final List<String> files;

    private BundledMaps(List<String> files) {
        this.files = List.copyOf(files);
    }

    private static final class Standard {
        static final BundledMaps INSTANCE = new BundledMaps(parseIndex(resourceText(INDEX)));
    }

    /** The maps listed in this class path's {@link #INDEX} (read once). */
    public static BundledMaps standard() {
        return Standard.INSTANCE;
    }

    /** A bundle of the given file names, for tests. */
    static BundledMaps of(List<String> files) {
        return new BundledMaps(files);
    }

    /**
     * BEHAVIOR: one file name per line, trimmed; blank lines and lines starting with '#' are skipped; the order
     * is kept.
     */
    static List<String> parseIndex(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String s = line.strip();
            if (!s.isEmpty() && s.charAt(0) != '#') {
                out.add(s);
            }
        }
        return out;
    }

    /** The bundled file names, in index order (e.g. {@code CAMD.xp}). */
    public List<String> files() {
        return files;
    }

    /** BEHAVIOR: the names to give {@code --map}: each file name without its {@code .xp}, in index order. */
    public List<String> names() {
        List<String> out = new ArrayList<>(files.size());
        for (String f : files) {
            out.add(stripExtension(f));
        }
        return out;
    }

    /**
     * The bundled file a plain map name refers to.
     * <p>
     * BEHAVIOR: a file whose name equals {@code name} or {@code name + ".xp"}, ignoring case; null when none does.
     */
    public String find(String name) {
        for (String f : files) {
            if (f.equalsIgnoreCase(name) || f.equalsIgnoreCase(name + EXTENSION)) {
                return f;
            }
        }
        return null;
    }

    /** The raw bytes of a bundled map file (edge: reads the class path). */
    public static byte[] bytes(String file) throws IOException {
        try (InputStream in = BundledMaps.class.getResourceAsStream(DIR + file)) {
            if (in == null) {
                throw new IOException("missing resource " + DIR + file);
            }
            return in.readAllBytes();
        }
    }

    private static String resourceText(String resource) {
        try (InputStream in = BundledMaps.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stripExtension(String file) {
        return file.toLowerCase(Locale.ROOT).endsWith(EXTENSION) ? file.substring(0, file.length() - EXTENSION.length())
            : file;
    }

    /** What {@code --list-maps} shows of one map, from its header options. */
    public record Info(String name, String title, String author, int width, int height) {
        /**
         * BEHAVIOR: name is {@code file} without {@code .xp}; title, author and size are the map's
         * {@code mapName}, {@code mapAuthor}, {@code mapWidth} and {@code mapHeight} (the option defaults
         * when the map has none).
         */
        static Info of(String file, Options mapOptions) {
            return new Info(stripExtension(file), mapOptions.getString(Opt.MAP_NAME),
                mapOptions.getString(Opt.MAP_AUTHOR), mapOptions.getInt(Opt.MAP_WIDTH),
                mapOptions.getInt(Opt.MAP_HEIGHT));
        }
    }

    /** Every bundled map's {@link Info}, in index order (edge: parses each map). */
    public List<Info> infos() throws IOException {
        List<Info> out = new ArrayList<>(files.size());
        for (String f : files) {
            Options o = new Options();
            MapSource.bundled(f).parseInto(o);
            out.add(Info.of(f, o));
        }
        return out;
    }

    /**
     * The {@code --list-maps} text.
     * <p>
     * BEHAVIOR: a heading line, then a column header (NAME, SIZE, TITLE, AUTHOR) and one row per map in the given
     * order; NAME, SIZE ({@code WxH} blocks) and TITLE are padded to their widest entry plus two spaces; every line
     * ends with a newline and no line has trailing spaces.
     */
    public static String listing(List<Info> infos) {
        int nameW = "NAME".length();
        int sizeW = "SIZE".length();
        int titleW = "TITLE".length();
        for (Info i : infos) {
            nameW = Math.max(nameW, i.name().length());
            sizeW = Math.max(sizeW, size(i).length());
            titleW = Math.max(titleW, i.title().length());
        }
        StringBuilder sb = new StringBuilder(128 + infos.size() * 128);
        sb.append("Bundled maps (give the NAME to --map; a value with a path is a file):\n");
        row(sb, nameW, sizeW, titleW, "NAME", "SIZE", "TITLE", "AUTHOR");
        for (Info i : infos) {
            row(sb, nameW, sizeW, titleW, i.name(), size(i), i.title(), i.author());
        }
        return sb.toString();
    }

    private static String size(Info i) {
        return i.width() + "x" + i.height();
    }

    private static void row(StringBuilder sb, int nameW, int sizeW, int titleW, String name, String size, String title,
                            String author) {
        int start = sb.length();
        pad(sb, name, nameW + 2);
        pad(sb, size, sizeW + 2);
        pad(sb, title, titleW + 2);
        sb.append(author);
        int end = sb.length();
        while (end > start && sb.charAt(end - 1) == ' ') {
            end--;
        }
        sb.setLength(end);
        sb.append('\n');
    }

    private static void pad(StringBuilder sb, String s, int width) {
        sb.append(s);
        for (int k = s.length(); k < width; k++) {
            sb.append(' ');
        }
    }
}
