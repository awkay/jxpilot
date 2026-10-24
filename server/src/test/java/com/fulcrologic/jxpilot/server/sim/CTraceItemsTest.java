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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.server.world.Obj;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden traces of item weapons and devices on the original C server (tools/xpilot-ref patches 0004 and 0006, see
 * {@code src/test/resources/ctrace/items/README.txt}), replayed in the Java simulation by {@link CReplay} and compared
 * frame by frame with no tolerance: every player's physics, status, items, abilities, tanks and lock, every object C
 * traced (missiles, mines, items, shots, cannon shots, balls, wreckage), laser pulses, ECMs, busy cannons, and the
 * explosion debris (burst counts, lives, speeds, and the particles themselves around each explosion).
 * <p>
 * Replay rules: the Java world is set up as the C server was (lib/defaults, the map, the trace's command line), the
 * players join in the tick C added them and hold the keys C received (XPKEYS), and every random draw is C's own
 * (XPRAND, matched by call site in {@link CDraws}); Java's own debris expansion (a deliberate deviation) is replaced
 * by C's particles when they are made. Nothing else is copied from C.
 */
@DisplayName("C golden traces of items")
class CTraceItemsTest {
    /** The command line every default.xp recording shares (README.txt). */
    static final String[] DEFAULT = {"gravity", "-0.14", "sparksWallBounce", "no"};
    /** The command line every items-arena.xp recording shares. */
    static final String[] ARENA = {"gravity", "0", "initialLasers", "3", "initialECMs", "1", "initialMissiles", "4",
        "itemConcentratorProb", "1", "itemConcentratorRadius", "1", "movingItemProb", "0"};

    static String[] opts(String[] base, String... more) {
        String[] o = Arrays.copyOf(base, base.length + more.length);
        System.arraycopy(more, 0, o, base.length, more.length);
        return o;
    }

    static CReplay.Result replay(String trace, String... more) throws IOException {
        return CReplay.run(trace, "default.xp", Long.MAX_VALUE, opts(DEFAULT, more));
    }

    static CReplay.Result arena(String trace, String... more) throws IOException {
        return CReplay.run(trace, "items-arena.xp", Long.MAX_VALUE, opts(ARENA, more));
    }

    /** The item columns of a C item line, as {@link CReplay} prints them. */
    static String items(int[] it, int used, int have, long fuel, long max, int tanks, int cur) {
        return Arrays.toString(it) + " used " + Integer.toHexString(used) + " have " + Integer.toHexString(have)
            + " fuel " + fuel + "/" + max + " tanks " + tanks + " cur " + cur;
    }

    /** Frames compared and no difference, plus the trace-specific coverage claims. */
    @SafeVarargs
    static void check(CReplay.Result r, int frames, Function<CReplay.Result, String>... claims) {
        List<String> bad = r.mismatches().stream().limit(3).toList();
        assertAll(
            () -> assertEquals(frames, r.frames(), "C frames compared (" + r.summary() + ")"),
            () -> assertEquals(List.of(), bad, "no frame differs from C (" + r.mismatches().size() + " do)"),
            () -> {
                for (Function<CReplay.Result, String> c : claims) {
                    String failed = c.apply(r);
                    assertTrue(failed == null, failed);
                }
            });
    }

    /** A coverage claim: {@code what} holds, else the message says what the trace failed to exercise. */
    static Function<CReplay.Result, String> has(String what, java.util.function.Predicate<CReplay.Result> p) {
        return r -> p.test(r) ? null : "the trace exercises " + what + " (" + r.summary() + ")";
    }

    @Test
    @DisplayName("torpedoes at rest and while thrusting")
    void torpedo() throws IOException {
        check(replay("torpedo.txt", "initialMissiles", "4"), 84,
            has("torpedo flight", r -> r.of(Obj.TORPEDO) > 20));
    }

    @Test
    @DisplayName("heat-seeker locking on its own thrusting ship (cannons live)")
    void heatSeeker() throws IOException {
        check(replay("heat-seeker.txt", "initialMissiles", "4"), 79,
            has("heat-seeker flight", r -> r.of(Obj.HEAT_SHOT) > 15));
    }

    @Test
    @DisplayName("heat-seeker's whole flight: close and mid range errors while the ship thrusts, growing error while it coasts")
    void heatSeekerFull() throws IOException {
        check(replay("heat-seeker-full.txt", "initialMissiles", "4"), 105,
            has("a long heat-seeker flight", r -> r.of(Obj.HEAT_SHOT) > 60));
    }

    @Test
    @DisplayName("an unlocked heat-seeker finds its ship when it starts thrusting at mid range (the search's error)")
    void heatSeekerSearch() throws IOException {
        check(replay("heat-search.txt", "initialMissiles", "4"), 88,
            has("a heat-seeker flight", r -> r.of(Obj.HEAT_SHOT) > 40));
    }

    @Test
    @DisplayName("a smart missile locked on a second player, steering around walls")
    void smartMissile() throws IOException {
        check(replay("smart.txt", "initialMissiles", "4"), 140,
            has("smart missile flight", r -> r.of(Obj.SMART_SHOT) > 50));
    }

    @Test
    @DisplayName("a dropped and a thrown mine")
    void mines() throws IOException {
        check(replay("mines.txt", "initialMines", "4", "mineFuseTime", "0"), 78,
            has("mines", r -> r.of(Obj.MINE) > 50));
    }

    @Test
    @DisplayName("a fused mine: owner immunity runs out under its own ship, the explosion's debris")
    void mineFuse() throws IOException {
        check(replay("mine-fuse.txt", "initialMines", "2", "mineFuseTime", "1"), 74,
            has("the fused mine", r -> r.of(Obj.MINE) > 10),
            has("the mine explosion's particles", r -> r.particles() > 100 && r.particleFrames() > 0));
    }

    @Test
    @DisplayName("laser pulses cut short by a wall")
    void laser() throws IOException {
        check(replay("laser.txt", "initialLasers", "2"), 69, has("pulses", r -> r.pulses() > 10));
    }

    @Test
    @DisplayName("laser hits on an armoured second player (ED_LASER_HIT), ECM on it, energy packs picked up")
    void laserHit() throws IOException {
        check(arena("arena-laser.txt", "itemMineProb", "0.01", "itemEnergyPackProb", "0.01", "maxItemDensity", "0.02",
            "initialArmors", "1"), 100,
            has("pulses", r -> r.pulses() > 10), has("an ECM", r -> r.ecms() > 0),
            has("items at the concentrator", r -> r.of(Obj.ITEM) > 100));
    }

    @Test
    @DisplayName("a killed player's picked-up mines are thrown (dropItemOnKillProb 1)")
    void throwItems() throws IOException {
        check(arena("arena-throw.txt", "itemMineProb", "9.4e-5", "maxItemDensity", "0.1",
            "dropItemOnKillProb", "1", "detonateItemOnKillProb", "0"), 100,
            has("the wreckage of the killed player", r -> r.of(Obj.WRECKAGE) > 0));
    }

    @Test
    @DisplayName("a killed player's picked-up mines detonate (detonateItemOnKillProb 1)")
    void detonateItems() throws IOException {
        check(arena("arena-detonate.txt", "itemMineProb", "0.01", "maxItemDensity", "0.05", "dropItemOnKillProb", "0",
            "detonateItemOnKillProb", "1"), 121,
            has("detonated mines", r -> r.of(Obj.MINE) > 0),
            has("their explosions' particles", r -> r.particles() > 300));
    }

    @Test
    @DisplayName("an ECM makes a ship let go of the treasure ball it tows (C Fire_general_ecm, item.c:1167-1179)")
    void ecmBallDetach() throws IOException {
        check(CReplay.run("ball-ecm.txt", "ball-ecm.xp", Long.MAX_VALUE, "sparksWallBounce", "no", "initialECMs", "8"),
            110, has("the towed ball", r -> r.of(Obj.BALL) > 100), has("ECMs", r -> r.ecms() > 20));
    }

    @Test
    @DisplayName("wideangle volleys (rear shots into the ceiling)")
    void wideangle() throws IOException {
        check(replay("wideangle-rearshot.txt", "initialWideangles", "2", "initialRearshots", "3"), 58,
            has("shots", r -> r.of(Obj.SHOT) > 100));
    }

    @Test
    @DisplayName("rear shots into open space")
    void rearshot() throws IOException {
        check(replay("rearshot.txt", "initialRearshots", "3"), 68, has("shots", r -> r.of(Obj.SHOT) > 100));
    }

    @Test
    @DisplayName("afterburners")
    void afterburner() throws IOException {
        check(replay("afterburner.txt", "initialAfterburners", "5"), 61);
    }

    @Test
    @DisplayName("emergency thrust")
    void emergencyThrust() throws IOException {
        check(replay("emergency-thrust.txt", "initialEmergencyThrusts", "1"), 63);
    }

    @Test
    @DisplayName("cloak, uncloak, phasing")
    void cloakPhasing() throws IOException {
        check(replay("cloak-phasing.txt", "initialCloaks", "1", "initialPhasings", "1"), 107);
    }

    @Test
    @DisplayName("ECM blast next to a cannon: the cannon is damaged")
    void ecm() throws IOException {
        check(replay("ecm.txt", "initialECMs", "2"), 53, has("an ECM", r -> r.ecms() > 0),
            has("a damaged cannon", r -> r.cannonFrames() > 20));
    }

    @Test
    @DisplayName("lost mine and missile packs (drop offsets, speeds and lives from C's draws)")
    void loseItem() throws IOException {
        check(replay("lose-item.txt", "initialMines", "3", "initialMissiles", "2"), 84,
            has("dropped item packs", r -> r.of(Obj.ITEM) > 60));
    }

    @Test
    @DisplayName("extra tanks: switching and fuel flow")
    void tanks() throws IOException {
        check(replay("tanks.txt", "initialTanks", "2"), 73);
    }
}
