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
package com.fulcrologic.jxpilot.server.host;

import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEHAVIOR (KernelState): a scan logs what changed since the last one (cannon phasing, target team, wormhole count,
 * a temporary wormhole, a tracked block or item id) and nothing when nothing changed; drain sends the log from a
 * cursor; initial sends what differs from the map as loaded (after a RESET for a resync, nothing for a newcomer with
 * nothing to send); behind tells a cursor the ring ran past.
 */
@DisplayName("KernelState")
class KernelStateTest {
    static final String[] MAP = {
        "xxxxxxxxxxxxx",
        "x           x",
        "x _ r ! @ _ x",
        "x           x",
        "xxxxxxxxxxxxx",
    };

    static final int B = Msg.KernelState.BLOCK;
    static final int C = Msg.KernelState.CANNON;
    static final int T = Msg.KernelState.TARGET;

    static List<String> drain(KernelState k, long from) {
        HostFx.Wire wire = new HostFx.Wire();
        k.drain(wire.out, from);
        return wire.take();
    }

    static List<String> initial(KernelState k) {
        HostFx.Wire wire = new HostFx.Wire();
        k.initial(wire.out, true);
        return wire.take();
    }

    static List<String> newcomer(KernelState k) {
        HostFx.Wire wire = new HostFx.Wire();
        k.initial(wire.out, false);
        return wire.take();
    }

    @Test
    @DisplayName("scan logs each change once; drain sends them from a cursor; initial is RESET plus the differences")
    void changes() {
        Simulation s = HostFx.sim("", MAP);
        World w = s.world;
        KernelState k = new KernelState(w);
        k.scan();
        long quiet = k.seq();
        List<String> fresh = initial(k);
        List<String> freshNewcomer = newcomer(k);
        int cx = w.map.cannons().get(0).bx();
        int cy = w.map.cannons().get(0).by();
        w.cannonItems.used[0] |= Has.PHASING_DEVICE;
        w.map.setBlock(cx, cy, BlockType.SPACE);
        w.modes.targetTeam[0] = 3;
        k.scan();
        long after = k.seq();
        int tx = w.map.targets().get(0).bx();
        int ty = w.map.targets().get(0).by();
        k.scan();
        long second = k.seq();
        List<String> sent = drain(k, quiet);
        List<String> fromMiddle = drain(k, quiet + 2);
        List<String> changed = initial(k);
        List<String> changedNewcomer = newcomer(k);
        w.map.setBlock(cx, cy, BlockType.CANNON);
        w.cannonItems.used[0] &= ~Has.PHASING_DEVICE;
        k.scan();
        List<String> revived = drain(k, after);
        List<String> back = initial(k);
        long beforeTarget = k.seq();
        w.map.setBlock(tx, ty, BlockType.SPACE);
        k.scan();
        List<String> targetGone = drain(k, beforeTarget);
        String block = B + ":" + cx + "," + cy + "," + (BlockType.SPACE & 0xFF);
        assertAll(
            () -> assertEquals(0, quiet, "nothing changed: nothing logged"),
            () -> assertEquals(List.of("KSTATE 6:0,0,0,0"), fresh, "a fresh world: a resync is RESET alone"),
            () -> assertEquals(List.of(), freshNewcomer, "a newcomer gets nothing"),
            () -> assertEquals(List.of("KSTATE " + C + ":0,0,1,0 " + T + ":0,0,0,3 " + block + ","
                + w.map.itemId(cx, cy)), changedNewcomer, "a newcomer after changes: the differences, no RESET"),
            () -> assertEquals(3, after - quiet, "three changes: phasing, team, block"),
            () -> assertEquals(after, second, "a second scan logs nothing new"),
            () -> assertEquals(1, sent.size(), "one message"),
            () -> assertTrue(sent.get(0).startsWith("KSTATE " + C + ":0,0,1,0 " + T + ":0,0,0,3 " + block),
                "phased, target team 3, the cannon's block is space: " + sent),
            () -> assertEquals(List.of("KSTATE " + block + "," + w.map.itemId(cx, cy)), fromMiddle,
                "from a later cursor only the rest"),
            () -> assertTrue(changed.get(0).startsWith("KSTATE 6:0,0,0,0 " + C + ":0,0,1,0 " + T + ":0,0,0,3 " + block),
                "initial after changes: RESET and the differences: " + changed),
            () -> assertTrue(revived.get(0).contains(C + ":0,0,0,0") && revived.get(0).contains(B + ":" + cx + "," + cy
                + "," + (BlockType.CANNON & 0xFF)), "the revival is logged: " + revived),
            () -> assertEquals(List.of("KSTATE 6:0,0,0,0 " + T + ":0,0,0,3"), back,
                "back to the map's cannon: only the team still differs"),
            () -> assertTrue(targetGone.get(0).contains(B + ":" + tx + "," + ty + "," + (BlockType.SPACE & 0xFF)),
                "a destroyed target's block is logged: " + targetGone));
    }

    /** A map with {@code n} cannons in a row (and a base). */
    static String[] cannons(int n) {
        String row = "x" + "r".repeat(n) + "x";
        String open = "x" + " ".repeat(n) + "x";
        String wall = "x".repeat(n + 2);
        String base = "x _" + " ".repeat(n - 2) + "x";
        return new String[] {wall, open, row, open, base, wall};
    }

    @Test
    @DisplayName("drain returns the cursor after what it sent; more than one message's worth is split; a cannon's"
        + " item id is compared with the map's")
    void bulk() {
        Simulation s = HostFx.sim("", cannons(120));
        World w = s.world;
        KernelState k = new KernelState(w);
        for (int i = 0; i < 120; i++) {
            w.cannonItems.used[i] |= Has.PHASING_DEVICE;
        }
        k.scan();
        HostFx.Wire wire = new HostFx.Wire();
        long cursor = k.drain(wire.out, 0);
        List<String> sent = wire.take();
        List<String> all = initial(k);
        for (int i = 0; i < 120; i++) {
            w.cannonItems.used[i] &= ~Has.PHASING_DEVICE;
        }
        int c1x = w.map.cannons().get(1).bx();
        int c1y = w.map.cannons().get(1).by();
        w.map.setBlock(c1x, c1y, BlockType.SPACE);
        k.scan();
        w.map.setBlock(c1x, c1y, BlockType.CANNON);
        k.scan();
        List<String> revived = newcomer(k);
        long beforeTemps = k.seq();
        for (int i = 0; i < 40; i++) {
            w.worms.addTemp(1 + i, 1, 1 + i, 3);
        }
        k.scan();
        int blocksLogged = 0;
        for (String m : drain(k, beforeTemps)) {
            for (String e : m.split(" ")) {
                blocksLogged += e.startsWith(B + ":") ? 1 : 0;
            }
        }
        int fBlocks = blocksLogged;
        int entries = 0;
        for (String m : all) {
            entries += m.split(" ").length - 1;
        }
        int counted = entries;
        assertAll(
            () -> assertEquals(120, cursor, "drain returns the cursor after the 120 entries it sent"),
            () -> assertEquals(2, sent.size(), "120 entries need two messages (" + Msg.KernelState.MAX_ENTRIES + " each)"),
            () -> assertEquals(2, all.size(), "and so does the initial state"),
            () -> assertEquals(121, counted, "RESET and the 120 phased cannons"),
            () -> assertEquals(List.of(), revived, "a revived cannon has the map's block and item id again: no difference"),
            () -> assertEquals(80, fBlocks, "80 new temporary wormhole blocks, beyond the tracked set's first capacity"));
    }

    @Test
    @DisplayName("a temporary wormhole: the count, its entry and its blocks are logged; behind past the ring")
    void wormholes() {
        Simulation s = HostFx.sim("", MAP);
        World w = s.world;
        KernelState k = new KernelState(w);
        int mapCount = w.worms.mapCount;
        w.worms.addTemp(2, 1, 10, 3);
        k.scan();
        long once = k.seq();
        k.scan();
        long twice = k.seq();
        List<String> sent = drain(k, 0);
        String all = String.join(" ", sent);
        boolean ahead = k.behind(0);
        for (int i = 0; i < 40; i++) {
            w.worms.addTemp(3 + i % 5, 1, 10, 1 + i % 3);
        }
        k.scan();
        long many = k.seq();
        k.scan();
        long manyAgain = k.seq();
        long start = k.seq();
        while (k.seq() - start < KernelState.RING) {
            w.modes.targetTeam[0] = (int) (k.seq() % 2) + 1;
            k.scan();
        }
        boolean exactly = k.behind(start);
        w.modes.targetTeam[0] = w.modes.targetTeam[0] == 1 ? 2 : 1;
        k.scan();
        boolean past = k.behind(start);
        assertAll(
            () -> assertTrue(all.contains(Msg.KernelState.WORM_COUNT + ":0,0,0," + (mapCount + 2)),
                "the count grows by the in and out ends: " + all),
            () -> assertTrue(all.contains(Msg.KernelState.WORM + ":" + mapCount + ",2,1,"), "the entry's block: " + all),
            () -> assertTrue(all.contains(B + ":2,1," + (BlockType.WORMHOLE & 0xFF)), "its block is a wormhole now"),
            () -> assertTrue(all.contains(B + ":10,3," + (BlockType.WORMHOLE & 0xFF)), "and the other end"),
            () -> assertEquals(once, twice, "a second scan with the temporaries in place logs nothing"),
            () -> assertTrue(many - twice >= 81, "80 more temporaries: each logged, the count too (" + (many - twice) + ")"),
            () -> assertEquals(many, manyAgain, "and nothing on the next scan"),
            () -> assertFalse(ahead, "a cursor at 0 is not behind yet"),
            () -> assertFalse(exactly, "exactly RING entries behind: the ring still holds them all"),
            () -> assertTrue(past, "one more and it is behind"),
            () -> assertFalse(k.behind(k.seq() - 5), "a recent cursor is not"));
    }
}
