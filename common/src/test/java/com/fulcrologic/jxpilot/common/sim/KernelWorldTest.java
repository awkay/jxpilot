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
package com.fulcrologic.jxpilot.common.sim;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link KernelWorld} and the default methods of {@link ShipWorld}; {@link ShipParams}. */
class KernelWorldTest {
    /** 5x3 with a wall, a cannon (index 0), a target of team 2, and wormholes: normal, in, out. */
    static final String[] FEATURES = {"x  @ ", " r(2!", "  )  "};

    @Nested
    @DisplayName("KernelWorld")
    class World {
        @Test
        @DisplayName("takes size, wrap and wall distances from the map; the dynamic state starts as the server's")
        void initial() {
            KernelWorld w = KernelWorld.of(KernelFx.options("", FEATURES));
            WorldMap m = w.map();
            byte[] wd = WallDist.compute(m);
            boolean wallDistOk = true;
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 5; x++) {
                    wallDistOk &= w.wallDist(x, y) == (wd[y * 5 + x] & 0xFF);
                }
            }
            boolean distOk = wallDistOk;
            assertAll(
                () -> assertEquals(5, w.width(), "width in blocks"),
                () -> assertEquals(3, w.height(), "height in blocks"),
                () -> assertEquals(5 * Const.BLOCK_SZ, w.pixelWidth(), "width in pixels"),
                () -> assertEquals(3 * Const.BLOCK_SZ, w.pixelHeight(), "height in pixels"),
                () -> assertEquals(5 * Const.BLOCK_SZ * 64, w.clickWidth(), "width in clicks"),
                () -> assertEquals(3 * Const.BLOCK_SZ * 64, w.clickHeight(), "height in clicks"),
                () -> assertFalse(w.wrap(), "no edge wrap"),
                () -> assertTrue(distOk, "the wall distances are WallDist's"),
                () -> assertEquals(1L, w.frames(), "the frame clock starts at C's first frame"),
                () -> assertEquals(0, w.roundDelay(), "no round delay"),
                () -> assertFalse(w.cannonPhased(0), "no cannon is phased"),
                () -> assertEquals(0, w.targetTeam(0), "outside team play targets are team 0"),
                () -> assertEquals(3, w.wormholeCount(), "the map's wormholes"),
                () -> assertEquals(-1, w.wormholeLastDest(0), "with no destination"),
                () -> assertEquals(0, w.wormholeCountdown(0), "and no countdown"),
                () -> assertEquals(0, w.objectPx(5), "no pool objects: 0"),
                () -> assertEquals(0, w.objectPy(5), "0"),
                () -> assertTrue(w.shipParams().shipMass > 0, "the kernel parameters of the options"));
        }

        @Test
        @DisplayName("wormholes keep their map type and block; targets in team play keep their team")
        void features() {
            KernelWorld w = KernelWorld.of(KernelFx.options("teamPlay: yes", FEATURES));
            WorldMap m = w.map();
            int[] types = new int[3];
            int[] bxs = new int[3];
            int[] bys = new int[3];
            for (int i = 0; i < 3; i++) {
                types[i] = w.wormholeType(i);
                bxs[i] = w.wormholeBx(i);
                bys[i] = w.wormholeBy(i);
            }
            int[] mapBx = new int[3];
            int[] mapBy = new int[3];
            int[] mapType = new int[3];
            for (int i = 0; i < 3; i++) {
                mapBx[i] = m.wormholes().get(i).bx();
                mapBy[i] = m.wormholes().get(i).by();
                mapType[i] = switch (m.wormholes().get(i).type()) {
                    case NORMAL -> ShipWorld.WORM_NORMAL;
                    case IN -> ShipWorld.WORM_IN;
                    case OUT -> ShipWorld.WORM_OUT;
                };
            }
            w.cannonPhased[0] = true;
            w.setWormholeState(1, 2, 7);
            w.frames = 99;
            w.roundDelay = 4;
            assertAll(
                () -> assertArrayEquals(mapType, types, "each wormhole has its map type"),
                () -> assertTrue(java.util.Arrays.stream(types).boxed().toList()
                    .containsAll(java.util.List.of(ShipWorld.WORM_NORMAL, ShipWorld.WORM_IN, ShipWorld.WORM_OUT)),
                    "(fixture: one of each)"),
                () -> assertArrayEquals(mapBx, bxs, "and block x"),
                () -> assertArrayEquals(mapBy, bys, "and block y"),
                () -> assertEquals(m.targets().get(0).team(), w.targetTeam(0), "team play: the map team"),
                () -> assertEquals(2, w.targetTeam(0), "(fixture: team 2)"),
                () -> assertTrue(w.cannonPhased(0), "the set phasing is read back"),
                () -> assertEquals(2, w.wormholeLastDest(1), "the set destination"),
                () -> assertEquals(7, w.wormholeCountdown(1), "the set countdown"),
                () -> assertEquals(99L, w.frames(), "the set clock"),
                () -> assertEquals(4, w.roundDelay(), "the set round delay"));
        }
    }

    @Nested
    @DisplayName("KernelWorld wormhole table")
    class Wormholes {
        @Test
        @DisplayName("temporary wormholes are mirrored after the map's; the table grows; a map index is not overwritten")
        void temporary() {
            KernelWorld w = KernelWorld.of(KernelFx.options("", FEATURES));
            int n = w.mapWormholes();
            w.setTempWormhole(n, 4, 0, ShipWorld.WORM_IN);
            w.setTempWormhole(n + 1, 0, 2, ShipWorld.WORM_OUT);
            w.setWormholeCount(n + 2);
            w.setWormholeState(n, n + 1, 12);
            int far = n + KernelWorld.TEMP_CAPACITY + 5;
            w.setTempWormhole(far, 3, 1, ShipWorld.WORM_OUT);
            int mapType = w.wormholeType(0);
            int mapBx = w.wormholeBx(0);
            w.setTempWormhole(0, 9, 9, ShipWorld.WORM_OUT);
            KernelWorld low = KernelWorld.of(KernelFx.options("", FEATURES));
            low.setWormholeCount(1);
            KernelWorld big = KernelWorld.of(KernelFx.options("", FEATURES));
            big.setTempWormhole(200, 2, 1, ShipWorld.WORM_OUT);
            KernelWorld state = KernelWorld.of(KernelFx.options("", FEATURES));
            state.setWormholeState(300, 4, 5);
            state.setWormholeState(0, 2, 3);
            assertAll(
                () -> assertEquals(3, n, "(fixture: three map wormholes)"),
                () -> assertEquals(n + 2, w.wormholeCount(), "the count includes the temporary ones"),
                () -> assertEquals(ShipWorld.WORM_IN, w.wormholeType(n), "a mirrored IN"),
                () -> assertEquals(4, w.wormholeBx(n), "at its block x"),
                () -> assertEquals(0, w.wormholeBy(n), "and y"),
                () -> assertEquals(ShipWorld.WORM_OUT, w.wormholeType(n + 1), "a mirrored OUT"),
                () -> assertEquals(2, w.wormholeBy(n + 1), "at its block"),
                () -> assertEquals(n + 1, w.wormholeLastDest(n), "the mirrored destination"),
                () -> assertEquals(12, w.wormholeCountdown(n), "and countdown"),
                () -> assertEquals(ShipWorld.WORM_OUT, w.wormholeType(far), "beyond the first capacity the table grew"),
                () -> assertEquals(3, w.wormholeBx(far), "and holds the block"),
                () -> assertEquals(ShipWorld.WORM_IN, w.wormholeType(far - 1), "a slot never mirrored reads as IN"),
                () -> assertEquals(-1, w.wormholeLastDest(far - 1), "with no destination"),
                () -> assertEquals(mapType, w.wormholeType(0), "a map wormhole keeps its map type"),
                () -> assertEquals(mapBx, w.wormholeBx(0), "and block"),
                () -> assertEquals(n, low.wormholeCount(), "the count never drops below the map's"),
                () -> assertEquals(ShipWorld.WORM_IN, w.wormholeType(n + 5), "an unmirrored slot within capacity: IN"),
                () -> assertEquals(-1, w.wormholeLastDest(n + 5), "with no destination"),
                () -> assertEquals(ShipWorld.WORM_OUT, big.wormholeType(200), "a fresh table grows to a far index"),
                () -> assertEquals(2, big.wormholeBx(200), "and holds its block"),
                () -> assertEquals(4, state.wormholeLastDest(300), "state at a far index grows the table too"),
                () -> assertEquals(5, state.wormholeCountdown(300), "with the countdown"),
                () -> assertEquals(2, state.wormholeLastDest(0), "index 0 is a held index"),
                () -> assertEquals(3, state.wormholeCountdown(0), "with its countdown"));
        }

        @Test
        @DisplayName("an index the table never held reads as an IN wormhole with no destination, also when negative")
        void unknown() {
            KernelWorld w = KernelWorld.of(KernelFx.options("", FEATURES));
            int huge = 100_000;
            assertAll(
                () -> assertEquals(ShipWorld.WORM_IN, w.wormholeType(huge), "type IN"),
                () -> assertEquals(0, w.wormholeBx(huge), "block x 0"),
                () -> assertEquals(0, w.wormholeBy(huge), "block y 0"),
                () -> assertEquals(-1, w.wormholeLastDest(huge), "no destination"),
                () -> assertEquals(0, w.wormholeCountdown(huge), "no countdown"),
                () -> assertEquals(ShipWorld.WORM_IN, w.wormholeType(-1), "a negative index too"),
                () -> assertEquals(ShipWorld.WORM_IN, w.wormholeType(3 + KernelWorld.TEMP_CAPACITY),
                    "the first index past the initial table"),
                () -> assertEquals(-1, w.wormholeLastDest(-1), "with no destination"));
        }

        @Test
        @DisplayName("a ship flying into a mirrored wormhole block the table does not know warps instead of failing")
        void mirroredBlock() {
            // a wall at block (2,2) keeps (3,2) traced (wall distances are not recomputed for new blocks, as C)
            KernelWorld w = KernelWorld.of(KernelFx.options("", "       ", "       ", " _x    ", "       ", "       "));
            w.map().setBlock(3, 2, com.fulcrologic.jxpilot.common.map.BlockType.WORMHOLE);
            w.map().setItemId(3, 2, 500);
            ShipBody p = KernelFx.ship(3 * Click.BLOCK_CLICKS + 1120, 2 * Click.BLOCK_CLICKS + 1120, 0, 1, 0);
            KernelFx.Rec r = new KernelFx.Rec();
            new ShipMover(w, () -> 0.5).movePlayer(p, r);
            assertAll(
                () -> assertEquals(MoveState.CRASH_WORMHOLE, r.crashType, "the unknown hole warps the ship"),
                () -> assertEquals(500, r.crashWormhole, "naming the block's index"));
        }
    }

    @Nested
    @DisplayName("ShipWorld defaults")
    class Defaults {
        @Test
        @DisplayName("wrap: with edgeWrap positions outside [0, size) move by one size; without, nothing changes")
        void wrap() {
            KernelWorld f = KernelWorld.of(KernelFx.options("", "   ", "   "));
            KernelWorld r = KernelWorld.of(KernelFx.options("edgeWrap: yes", "   ", "   "));
            int cw = r.clickWidth();
            int ch = r.clickHeight();
            int pw = r.pixelWidth();
            int ph = r.pixelHeight();
            assertAll(
                () -> assertEquals(-5, f.wrapXClick(-5), "flat: x clicks unchanged"),
                () -> assertEquals(ch + 5, f.wrapYClick(ch + 5), "flat: y clicks unchanged"),
                () -> assertEquals(-5.5, f.wrapXPixel(-5.5), "flat: x pixels unchanged"),
                () -> assertEquals(ph + 1.0, f.wrapYPixel(ph + 1.0), "flat: y pixels unchanged"),
                () -> assertEquals(cw - 5, r.wrapXClick(-5), "x below 0 wraps up"),
                () -> assertEquals(5, r.wrapXClick(cw + 5), "x at or past the width wraps down"),
                () -> assertEquals(0, r.wrapXClick(cw), "x = width is 0"),
                () -> assertEquals(7, r.wrapXClick(7), "x inside is kept"),
                () -> assertEquals(0, r.wrapXClick(0), "x = 0 is kept"),
                () -> assertEquals(0, r.wrapYClick(0), "y = 0 is kept"),
                () -> assertEquals(0.0, r.wrapXPixel(0), "x pixel 0 is kept"),
                () -> assertEquals(0.0, r.wrapYPixel(0), "y pixel 0 is kept"),
                () -> assertEquals(0.0, r.wrapYPixel(ph), "y pixels at the height wrap to 0"),
                () -> assertEquals(cw - 1, r.wrapXClick(cw - 1), "the last click column is kept"),
                () -> assertEquals(ch - 1, r.wrapYClick(ch - 1), "the last click row is kept"),
                () -> assertEquals(pw - 0.5, r.wrapXPixel(pw - 0.5), "just inside the width is kept"),
                () -> assertEquals(ph - 0.5, r.wrapYPixel(ph - 0.5), "just inside the height is kept"),
                () -> assertEquals(ch - 5, r.wrapYClick(-5), "y below 0 wraps up"),
                () -> assertEquals(0, r.wrapYClick(ch), "y = height is 0"),
                () -> assertEquals(7, r.wrapYClick(7), "y inside is kept"),
                () -> assertEquals(pw - 5.5, r.wrapXPixel(-5.5), "x pixels below 0"),
                () -> assertEquals(0.0, r.wrapXPixel(pw), "x pixels at the width"),
                () -> assertEquals(3.0, r.wrapXPixel(3.0), "x pixels inside"),
                () -> assertEquals(ph - 1.0, r.wrapYPixel(-1.0), "y pixels below 0"),
                () -> assertEquals(1.0, r.wrapYPixel(ph + 1.0), "y pixels past the height"),
                () -> assertEquals(3.0, r.wrapYPixel(3.0), "y pixels inside"));
        }

        @Test
        @DisplayName("gravity is the map's at the block, out-of-map blocks clamped to the edge")
        void gravity() {
            KernelWorld w = KernelWorld.of(KernelFx.options("gravity: -0.5\nplanetGravity: 0", " +   ", "     ", "     "));
            WorldMap m = w.map();
            assertAll(
                () -> assertEquals((float) m.gravityX(2, 1), w.gravityX(2, 1), "x inside"),
                () -> assertEquals((float) m.gravityY(2, 1), w.gravityY(2, 1), "y inside"),
                () -> assertEquals((float) m.gravityX(0, 0), w.gravityX(-3, -3), "below 0: the first block"),
                () -> assertEquals((float) m.gravityY(4, 2), w.gravityY(9, 9), "past the end: the last block"),
                () -> assertEquals((float) m.gravityX(4, 0), w.gravityX(5, -1), "x past, y below"),
                () -> assertEquals(0, ShipWorld.clampBlock(-1, 5), "clamp: below 0 is 0"),
                () -> assertEquals(4, ShipWorld.clampBlock(5, 5), "clamp: n is n - 1"),
                () -> assertEquals(3, ShipWorld.clampBlock(3, 5), "clamp: inside is kept"));
        }
    }

    @Nested
    @DisplayName("ShipParams")
    class Params {
        @Test
        @DisplayName("reads the kernel's options; C's Move_init limits apply; angles become RES units")
        void of() {
            String opts = "shipMass: 30\nminItemMass: 2\nshotMass: 0.25\nshotSpeed: 33\nfireRepeatRate: 3\n"
                + "edgeBounce: no\nmaxShieldedWallBounceSpeed: 1e9\nmaxUnshieldedWallBounceSpeed: -4\n"
                + "maxShieldedPlayerWallBounceAngle: 270\nmaxUnshieldedPlayerWallBounceAngle: 45\n"
                + "playerWallBounceBrakeFactor: 1.5\nwallBounceFuelDrainMult: 5000\nfriction: 0.05\n"
                + "blockFriction: 0.25\ncoriolis: 30\nteamImmunity: no\ntargetTeamCollision: no\nturnThrust: yes\n"
                + "cloakedExhaust: no\nwormTime: 2\nframesPerSecond: 12\nedgeWrap: yes\nteamPlay: yes";
            ShipParams p = ShipParams.of(KernelFx.options(opts, "     ", "  _  "), 
                WorldMap.fromOptions(KernelFx.options(opts, "     ", "  _  ")));
            ShipParams lo = ShipParams.of(KernelFx.options("maxShieldedPlayerWallBounceAngle: -5\n"
                    + "playerWallBounceBrakeFactor: -1\nwallBounceFuelDrainMult: -2", "  ", " _"),
                WorldMap.fromOptions(KernelFx.options("", "  ", " _")));
            double hyp = (int) StrictMath.hypot(5 * 35, 2 * 35);
            assertAll(
                () -> assertEquals(hyp, p.hypotenuse, "the world diagonal, truncated"),
                () -> assertEquals(30f, p.shipMass, "shipMass"),
                () -> assertEquals(2f, p.minItemMass, "minItemMass"),
                () -> assertEquals(0.25f, p.shotMass, "shotMass"),
                () -> assertEquals(33f, p.shotSpeed, "shotSpeed"),
                () -> assertEquals(3, p.fireRepeatRate, "fireRepeatRate"),
                () -> assertFalse(p.edgeBounce, "edgeBounce"),
                () -> assertTrue(p.edgeWrap, "edgeWrap from the map"),
                () -> assertTrue(p.teamPlay, "teamPlay from the map"),
                () -> assertEquals((float) hyp, p.maxShieldedWallBounceSpeed, "speeds at most the diagonal"),
                () -> assertEquals(0f, p.maxUnshieldedWallBounceSpeed, "and at least 0"),
                () -> assertEquals(180f, p.maxShieldedAngleDeg, "angles at most 180 degrees"),
                () -> assertEquals(Const.RES / 2, p.maxShieldedAngle, "180 degrees is RES/2"),
                () -> assertEquals(Const.RES / 8, p.maxUnshieldedAngle, "45 degrees is RES/8"),
                () -> assertEquals(0f, lo.maxShieldedAngleDeg, "angles at least 0"),
                () -> assertEquals(1f, p.playerWallBrakeFactor, "brake at most 1"),
                () -> assertEquals(0f, lo.playerWallBrakeFactor, "and at least 0"),
                () -> assertEquals(1000f, p.wallBounceFuelDrainMult, "drain at most 1000"),
                () -> assertEquals(0f, lo.wallBounceFuelDrainMult, "and at least 0"),
                () -> assertEquals(0.05f, p.friction, "friction"),
                () -> assertEquals(0.25f, p.blockFriction, "blockFriction"),
                () -> assertEquals(30, p.coriolis, "coriolis"),
                () -> assertFalse(p.teamImmunity, "teamImmunity"),
                () -> assertFalse(p.targetTeamCollision, "targetTeamCollision"),
                () -> assertTrue(p.turnThrust, "turnThrust"),
                () -> assertFalse(p.cloakedExhaust, "cloakedExhaust"),
                () -> assertEquals(24, p.wormTime, "wormTime in frames (2 s at 12 fps)"));
        }
    }
}
