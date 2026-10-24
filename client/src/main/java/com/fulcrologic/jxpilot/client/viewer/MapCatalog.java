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
package com.fulcrologic.jxpilot.client.viewer;

import com.fulcrologic.jxpilot.common.map.BundledMaps;
import com.fulcrologic.jxpilot.common.map.MapSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The maps the viewer's Tab key cycles through: the bundled maps, or the {@code .xp} files of one directory
 * sorted case-insensitively by file name.
 */
public final class MapCatalog {
    private final List<MapSource> maps;

    private MapCatalog(List<MapSource> maps) {
        this.maps = maps;
    }

    /**
     * The catalog a map belongs to.
     * <p>
     * BEHAVIOR: a bundled map gives the bundled maps ({@link #bundled}); a file gives its directory ({@link #scan}).
     */
    public static MapCatalog around(MapSource map, BundledMaps bundled) throws IOException {
        return map.isBundled() ? bundled(bundled) : scan(map.file().toAbsolutePath().normalize().getParent());
    }

    /** BEHAVIOR: every bundled map, in index order. */
    public static MapCatalog bundled(BundledMaps bundled) {
        List<MapSource> out = new ArrayList<>();
        for (String f : bundled.files()) {
            out.add(MapSource.bundled(f));
        }
        return new MapCatalog(List.copyOf(out));
    }

    /**
     * List {@code dir}'s {@code *.xp} files (extension case-insensitive; not recursive).
     * <p>
     * BEHAVIOR: sorted by lower-cased file name; non-.xp files and directories are ignored.
     */
    public static MapCatalog scan(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".xp"))
                .forEach(files::add);
        }
        files.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        List<MapSource> out = new ArrayList<>(files.size());
        for (Path p : files) {
            out.add(MapSource.of(p));
        }
        return new MapCatalog(List.copyOf(out));
    }

    public List<MapSource> maps() { return maps; }

    /**
     * Index of {@code map}, or -1.
     * <p>
     * BEHAVIOR: a bundled map matches the bundled entry of the same file name; a file matches the entry with the
     * same normalized absolute path.
     */
    public int indexOf(MapSource map) {
        Path f = map.isBundled() ? null : map.file().toAbsolutePath().normalize();
        for (int i = 0; i < maps.size(); i++) {
            MapSource m = maps.get(i);
            boolean same = f == null ? m.isBundled() && m.fileName().equals(map.fileName())
                : !m.isBundled() && m.file().toAbsolutePath().normalize().equals(f);
            if (same) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Index after {@code i}, wrapping to 0 at the end; 0 for an index not in the catalog.
     * Returns -1 for an empty catalog.
     */
    public int next(int i) {
        if (maps.isEmpty()) {
            return -1;
        }
        return i < 0 || i + 1 >= maps.size() ? 0 : i + 1;
    }
}
