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
            VertexBatch outline = StaticMap.build(m, p, false, true);
            VertexBatch filled = StaticMap.build(m, p, true, true);
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
                () -> assertTrue(outline.segmentCount() >= w, n + ": at least one outline segment per wall block"),
                () -> assertTrue(filled.vertexCount() >= 3 * w, n + ": at least one triangle per wall block"),
                () -> assertTrue(outline.pointCount() > 0, n + ": background dots exist"),
                () -> assertEquals(RadarImage.WIDTH * RadarImage.height(m), radar.length, n + ": radar size"),
                () -> assertTrue(wp > 0, n + ": walls show on the radar"));
        }
    }
}
