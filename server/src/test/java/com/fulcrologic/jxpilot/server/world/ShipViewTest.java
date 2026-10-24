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
package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.ShipParams;
import com.fulcrologic.jxpilot.common.sim.ShipWorld;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.net.DefaultsFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server's side of the kernel's world: Params as ShipParams, World as the ShipWorld view. */
@DisplayName("World and Params as the ship kernel sees them")
class ShipViewTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"blood-music2.xp", "CAMD.xp", "cloudscape.xp", "default.xp", "doggy.xp", "fireball.xp",
        "fuzz.xp", "fuzz2.xp", "globe.xp", "grandprix.xp", "newdarkhell.xp", "newdarkhell2.xp", "pad.xp", "pit.xp",
        "planetx.xp", "teamball.xp", "tourmination.xp", "tournament.xp", "war.xp"})
    @DisplayName("ShipParams.of (a client's) gives the server's kernel parameters for every shipped map")
    void sameParams(String map) throws IOException, IllegalAccessException {
        Options o = new Options();
        DefaultsFile.applyBundled(o);
        Path p = Path.of("..", "maps", map);
        if (!p.toFile().exists()) {
            p = Path.of("maps", map);
        }
        ConfigParser.parseFile(p, OptionOrigin.MAP, o);
        WorldMap m = WorldMap.fromOptions(o);
        Params server = Params.from(o, m);
        ShipParams client = ShipParams.of(o, m);
        List<String> differ = new ArrayList<>();
        int n = 0;
        for (Field f : ShipParams.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            n++;
            if (!f.get(server).equals(f.get(client))) {
                differ.add(f.getName() + " " + f.get(server) + " vs " + f.get(client));
            }
        }
        int fields = n;
        assertAll(
            () -> assertEquals(List.of(), differ, map + ": every kernel parameter is equal"),
            () -> assertTrue(fields >= 20, map + ": all " + fields + " fields were compared"));
    }

    @Test
    @DisplayName("World's ShipWorld methods read its live state: size, wrap, clock, cannons, targets, wormholes, objects")
    void worldView() {
        World w = new World(TestWorlds.options("teamPlay: yes", "x  @ ", " r(2!", "  )_2"), 1);
        ShipWorld v = w;
        w.frames = 42;
        w.roundDelay = 3;
        w.cannonItems.used[0] = Has.PHASING_DEVICE;
        w.modes.targetTeam[0] = 5;
        w.worms.setLastDest(1, 2);
        w.worms.setCountdown(1, 9);
        int h = w.objects.alloc();
        w.objects.setClicks(h, 6400, 3200);
        assertAll(
            () -> assertSame(w.map, v.map(), "the live map"),
            () -> assertSame(w.params, v.shipParams(), "the live parameters"),
            () -> assertEquals(w.width, v.width(), "width"),
            () -> assertEquals(w.height, v.height(), "height"),
            () -> assertEquals(w.pixelWidth, v.pixelWidth(), "pixel width"),
            () -> assertEquals(w.pixelHeight, v.pixelHeight(), "pixel height"),
            () -> assertEquals(w.clickWidth, v.clickWidth(), "click width"),
            () -> assertEquals(w.clickHeight, v.clickHeight(), "click height"),
            () -> assertEquals(w.wrap, v.wrap(), "wrap"),
            () -> assertEquals(42L, v.frames(), "the frame clock"),
            () -> assertEquals(3, v.roundDelay(), "the round delay"),
            () -> assertTrue(v.cannonPhased(0), "a cannon with a phasing device is phased"),
            () -> assertEquals(5, v.targetTeam(0), "the target's current team"),
            () -> assertEquals(w.worms.count(), v.wormholeCount(), "the wormhole count"),
            () -> assertEquals(w.worms.type(1), v.wormholeType(1), "a wormhole's type"),
            () -> assertEquals(w.worms.bx(2), v.wormholeBx(2), "its block x"),
            () -> assertEquals(w.worms.by(2), v.wormholeBy(2), "its block y"),
            () -> assertEquals(2, v.wormholeLastDest(1), "its last destination"),
            () -> assertEquals(9, v.wormholeCountdown(1), "its countdown"),
            () -> assertEquals(100, v.objectPx(h), "an object's pixel x"),
            () -> assertEquals(50, v.objectPy(h), "and y"));
    }
}
