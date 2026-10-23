package com.fulcrologic.jxpilot.client;

import com.fulcrologic.jxpilot.client.map.RadarImage;
import com.fulcrologic.jxpilot.client.map.StaticMap;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.client.viewer.MapCatalog;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Every shipped map builds its static geometry and radar picture. */
class ShippedMapsTest {
    @Test
    void everyMapBuildsWallsFeaturesAndRadar() throws Exception {
        Palette p = Palette.xpilotDefault();
        for (Path file : MapCatalog.scan(Fixtures.MAPS).maps()) {
            WorldMap m = WorldMap.load(file);
            VertexBatch outline = StaticMap.outlineWalls(m, p);
            VertexBatch filled = StaticMap.filledWalls(m, p);
            VertexBatch features = StaticMap.features(m, p, true);
            int[] radar = RadarImage.build(m, p, true);
            int walls = 0;
            for (byte t : m.blocks()) {
                if (t == BlockType.FILLED || BlockType.isDiagonal(t)) {
                    walls++;
                }
            }
            int wallPixels = 0;
            for (int px : radar) {
                if (px == p.color(Palette.WALL_RADAR_COLOR)) {
                    wallPixels++;
                }
            }
            String n = file.getFileName().toString();
            int w = walls;
            int wp = wallPixels;
            assertAll(n,
                () -> assertTrue(w > 0, n + " has walls"),
                () -> assertTrue(outline.vertexCount() >= 2 * w, n + ": at least one outline segment per wall block"),
                () -> assertTrue(filled.vertexCount() >= 3 * w, n + ": at least one triangle per wall block"),
                () -> assertTrue(features.vertexCount() > 0, n + ": features (dots, bases) exist"),
                () -> assertEquals(RadarImage.WIDTH * RadarImage.height(m), radar.length, n + ": radar size"),
                () -> assertTrue(wp > 0, n + ": walls show on the radar"));
        }
    }
}
