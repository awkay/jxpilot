package com.fulcrologic.jxpilot.client.viewer;

import com.fulcrologic.jxpilot.client.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MapCatalogTest {
    @Test
    void listsXpFilesSortedCaseInsensitively(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("b.xp"), "");
        Files.writeString(dir.resolve("A.XP"), "");
        Files.writeString(dir.resolve("c.txt"), "");
        Files.createDirectory(dir.resolve("d.xp"));
        MapCatalog c = MapCatalog.scan(dir);
        assertEquals(List.of("A.XP", "b.xp"), c.maps().stream().map(p -> p.getFileName().toString()).toList(),
            ".xp files only (extension case-insensitive), no directories, sorted ignoring case");
    }

    @Test
    void findsAndCyclesMaps(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.xp"), "");
        Files.writeString(dir.resolve("b.xp"), "");
        MapCatalog c = MapCatalog.scan(dir);
        assertAll(
            () -> assertEquals(1, c.indexOf(dir.resolve("x/../b.xp")), "finds a file through a non-normalized path"),
            () -> assertEquals(-1, c.indexOf(dir.resolve("z.xp")), "-1 for an unknown file"),
            () -> assertEquals(1, c.next(0), "next after 0"),
            () -> assertEquals(0, c.next(1), "wraps after the last"),
            () -> assertEquals(0, c.next(-1), "an unknown index starts at 0"),
            () -> assertEquals(0, c.next(5), "an out-of-range index starts at 0"));
    }

    @Test
    void emptyCatalogHasNoNext(@TempDir Path dir) throws IOException {
        assertEquals(-1, MapCatalog.scan(dir).next(0), "-1 when there are no maps");
    }

    @Test
    void repositoryHasNineteenMaps() throws IOException {
        assertEquals(19, MapCatalog.scan(Fixtures.MAPS).maps().size(), "all shipped maps are found");
    }
}
