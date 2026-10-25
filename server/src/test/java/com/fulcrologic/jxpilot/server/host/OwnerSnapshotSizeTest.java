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

import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import com.fulcrologic.jxpilot.server.Arena;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner's snapshot size in a fight at a given input lead (the view-cull margin grows with speed times lead,
 * clamped). Measurement, printed as {@code OWNER-SNAPSHOT}; the assertions are the clamp's.
 * <p>
 * BEHAVIOR: the same game (12 robots fighting on the arena, the owner thrusting, turning and firing at random) is
 * run once per lead; every owner snapshot's bytes (all parts, packet and snapshot headers included) are summed.
 */
@DisplayName("owner snapshot size by input lead")
class OwnerSnapshotSizeTest {
    static final int WARMUP = 300;
    static final int TICKS = 900;

    /** Mean bytes per owner snapshot, mean owner speed (px/tick), and the largest snapshot. */
    record Size(double meanBytes, double meanSpeed, int maxBytes, long total) { }

    static Size run(int lead) {
        Simulation s = new Simulation(new World(TestWorlds.options(
            "gravity: -0.14\nmaxRobots: 12\nminRobots: 12\nrobotLeaveLife: 0\nlimitedLives: no\n", Arena.rows()), 5));
        World w = s.world;
        SimGameHost h = new SimGameHost(s);
        int ann = HostFx.enter(h, "Ann", 0);
        h.inputLead(ann, lead);
        Rng r = new Rng(17);
        long keys = 0;
        ControlKey[] flips = {ControlKey.THRUST, ControlKey.TURN_LEFT, ControlKey.TURN_RIGHT, ControlKey.FIRE_SHOT};
        SnapshotWriter wr = new SnapshotWriter();
        ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
        long total = 0;
        long snaps = 0;
        int max = 0;
        double speed = 0;
        for (int t = 1; t <= WARMUP + TICKS; t++) {
            for (ControlKey k : flips) {
                if (r.nextFloat() < (k == ControlKey.THRUST ? 0.02f : 0.08f)) {
                    keys ^= k.mask();
                }
            }
            keys |= ControlKey.THRUST.mask() & (t % 40 < 30 ? -1L : 0);
            h.input(ann, t, keys, 0, 0);
            h.tick(t);
            if (t <= WARMUP) {
                continue;
            }
            Player p = w.byId(ann);
            int bytes = 0;
            for (int part = 0; part < SimGameHost.SNAPSHOT_PARTS; part++) {
                b.clear();
                PacketHeader.write(b, PacketType.SNAPSHOT, 1, 0);
                wr.begin(b, t, t, part, 0, 0, 0, 0);
                boolean more = h.fillSnapshot(ann, part, t % 3 == 0, wr);
                bytes += b.position();
                if (!more) {
                    break;
                }
            }
            total += bytes;
            snaps++;
            max = Math.max(max, bytes);
            speed += Math.hypot(p.vx, p.vy);
        }
        return new Size((double) total / snaps, speed / snaps, max, total);
    }

    @Test
    @DisplayName("the margin grows with the lead up to 8 ticks, then stops; at a realistic lead (4) the cost is small")
    void sizes() {
        Size l0 = run(0);
        Size l4 = run(4);
        Size l8 = run(8);
        Size l14 = run(14);
        for (int[] c : new int[][] {{0}, {4}, {8}, {14}}) {
            Size z = switch (c[0]) {
                case 0 -> l0;
                case 4 -> l4;
                case 8 -> l8;
                default -> l14;
            };
            System.out.printf(java.util.Locale.ROOT, "OWNER-SNAPSHOT lead %2d: mean %.1f B, max %d B, owner speed %.1f px/tick%n",
                c[0], z.meanBytes(), z.maxBytes(), z.meanSpeed());
        }
        assertAll(
            () -> assertTrue(l0.meanSpeed() > 4, "the owner moves (" + l0.meanSpeed() + " px/tick)"),
            () -> assertTrue(l4.meanBytes() >= l0.meanBytes(), "a lead widens the view, never narrows it"),
            () -> assertEquals(l8.total(), l14.total(), "beyond 8 ticks of lead the snapshots are byte for byte the same"),
            () -> assertTrue(l4.meanBytes() <= l0.meanBytes() * 1.15,
                "lead 4 costs at most 15% over lead 0 (" + l4.meanBytes() + " vs " + l0.meanBytes() + ")"));
    }
}
