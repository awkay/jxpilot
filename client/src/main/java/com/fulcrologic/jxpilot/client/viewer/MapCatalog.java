package com.fulcrologic.jxpilot.client.viewer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The {@code .xp} maps of one directory, sorted case-insensitively by file name, with cycling. */
public final class MapCatalog {
    private final List<Path> maps;

    private MapCatalog(List<Path> maps) {
        this.maps = maps;
    }

    /**
     * List {@code dir}'s {@code *.xp} files (extension case-insensitive; not recursive).
     * <p>
     * BEHAVIOR: sorted by lower-cased file name; non-.xp files and directories are ignored.
     */
    public static MapCatalog scan(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".xp"))
                .forEach(out::add);
        }
        out.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return new MapCatalog(List.copyOf(out));
    }

    public List<Path> maps() { return maps; }

    /** Index of {@code file} (compared by normalized absolute path), or -1. */
    public int indexOf(Path file) {
        Path f = file.toAbsolutePath().normalize();
        for (int i = 0; i < maps.size(); i++) {
            if (maps.get(i).toAbsolutePath().normalize().equals(f)) {
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
