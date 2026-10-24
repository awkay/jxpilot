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

import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a map argument ({@code --map}, {@code mapFileName}, {@code --view-map}) into a {@link MapSource}.
 * Every place that opens a map by name goes through {@link #resolve(String)}.
 * <ul>
 *   <li>A plain name (no {@code /} or {@code \}, no drive letter) is a bundled map first:
 *       {@code tourmination}, {@code tourmination.xp} and {@code TOURMINATION} are the same map.</li>
 *   <li>A plain name that is not bundled is a file in the current directory.</li>
 *   <li>Anything with a path in it is a file, and only the file system is tried.</li>
 * </ul>
 * File lookups follow C {@code openMapFile} (fileparser.c): the name as given, then with {@code .xp}, then with
 * {@code .map}, unless it already ends in one of them. C looks in the current directory before its map directory;
 * here the bundled maps come first (see DEVIATIONS-server.md).
 */
public final class MapLocator {
    private MapLocator() {}

    /** {@link #resolve(String, BundledMaps, Path)} with the bundled maps and the current directory. */
    public static MapSource resolve(String spec) throws FileNotFoundException {
        return resolve(spec, BundledMaps.standard(), Path.of(""));
    }

    /**
     * Resolve a map argument.
     * <p>
     * BEHAVIOR: a plain name ({@link #isPlainName}) that {@link BundledMaps#find} matches is that bundled map, even
     * when a file of that name exists in {@code dir}.
     * BEHAVIOR: a plain name with no bundled match is the first existing regular file among
     * {@link #candidates} resolved against {@code dir}.
     * BEHAVIOR: a path is only looked up on the file system, the same way (an absolute path ignores {@code dir}).
     * BEHAVIOR: nothing found throws FileNotFoundException naming the value, the files tried and every bundled name.
     *
     * @param dir the directory relative names are looked up in ({@code Path.of("")} is the current directory)
     */
    public static MapSource resolve(String spec, BundledMaps bundled, Path dir) throws FileNotFoundException {
        boolean plain = isPlainName(spec);
        if (plain) {
            String f = bundled.find(spec);
            if (f != null) {
                return MapSource.bundled(f);
            }
        }
        List<String> tried = new ArrayList<>();
        for (String c : candidates(spec)) {
            Path p = dir.resolve(c);
            if (Files.isRegularFile(p)) {
                return MapSource.of(p);
            }
            tried.add(p.toString());
        }
        String where = dir.toString().isEmpty() ? "the current directory" : dir.toString();
        String why = plain ? "it is not a bundled map, and " + where + " has no file " + String.join(", ", tried)
            : "no file " + String.join(", ", tried);
        throw new FileNotFoundException("map '" + spec + "' not found: " + why + ". Bundled maps: "
            + String.join(", ", bundled.names()) + " (--list-maps describes them).");
    }

    /**
     * BEHAVIOR: true iff {@code spec} has no '/' or '\' and does not start with a drive letter and a colon
     * ({@code C:}).
     */
    public static boolean isPlainName(String spec) {
        if (spec.indexOf('/') >= 0 || spec.indexOf('\\') >= 0) {
            return false;
        }
        return !(spec.length() >= 2 && spec.charAt(1) == ':' && isAsciiLetter(spec.charAt(0)));
    }

    /**
     * The file names C {@code openMapFile} tries, in order.
     * <p>
     * BEHAVIOR: {@code spec}, then {@code spec.xp} and {@code spec.map} unless {@code spec} already ends in
     * {@code .xp} or {@code .map} (case-sensitive, as C's strcmp).
     */
    static List<String> candidates(String spec) {
        if (spec.endsWith(".xp") || spec.endsWith(".map")) {
            return List.of(spec);
        }
        return List.of(spec, spec + ".xp", spec + ".map");
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
