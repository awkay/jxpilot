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
package com.fulcrologic.jxpilot.client.latency;

import com.fulcrologic.jxpilot.client.net.KernelSync;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.sim.ShipBody;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PREDICT sections through the real net path (server SnapshotBuilder, ServerNet, the emulated link,
 * ClientConnection and {@link KernelSync}): after every tick of a scripted flight on globe.xp with no other ship
 * (no robots: nothing the kernel does not model; it does graze the map's walls under gravity, which the kernel
 * models), the ship the client decodes is bit-equal ({@link ShipBody#sameState}) to the server's player after that
 * tick, and the frame clock matches. OWN_SHOTS too: every decoded own bullet is one of the server's own bullets
 * after that tick, with its spawn tick, ordinal and position (to the half pixel), and every own bullet within
 * 300 px of the ship is in the list. The flight's bullets end both ways, by expiry and against walls or ships,
 * and a bullet gone from the server is gone from the next decoded list (it would not match).
 */
@DisplayName("PREDICT round trip")
class PredictRoundTripTest {
    static final long SEC = LatencyRig.SEC;
    static final long MS = 1_000_000L;
    static final long THRUST = ControlKey.THRUST.mask();
    static final long LEFT = ControlKey.TURN_LEFT.mask();
    static final long RIGHT = ControlKey.TURN_RIGHT.mask();
    static final long FIRE = ControlKey.FIRE_SHOT.mask();
    static final long SHIELD = ControlKey.SHIELD.mask();

    /** Thrust, turns both ways and firing in four of six phases: keys change every few ticks for 4 s after the spawn. */
    static long keys(long sinceSpawn) {
        long ms = sinceSpawn / MS;
        if (ms >= 100 && ms < 200) {
            return SHIELD; // pressed and released: the ship starts with its shield up (C DEF_USED) and cannot fire
        }
        if (ms < 300 || ms > 4300) {
            return 0;
        }
        long phase = (ms / 230) % 6;
        return switch ((int) phase) {
            case 0 -> THRUST | FIRE;
            case 1 -> THRUST | LEFT;
            case 2 -> LEFT | FIRE;
            case 3 -> RIGHT | FIRE;
            case 4 -> FIRE;
            default -> THRUST | RIGHT;
        };
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("every PREDICT decodes to the server player's state after its tick, bit for bit")
    void roundTrip() {
        Scenario s = new Scenario(100, Scenario.Jitter.NORMAL_15, Scenario.Loss.NONE);
        String[] base = Course.FREE_FLIGHT.serverArgs(0);
        String[] args = java.util.Arrays.copyOf(base, base.length + 2);
        args[base.length] = "-shotLife";
        args[base.length + 1] = "20"; // bullets expire within the flight, as well as hitting walls
        LatencyRig rig = new LatencyRig(args, s.direction(), s.direction(), 21,
            InputToPhoton.tickPhase(21));
        long spawn = rig.runUntilPlaying(LatencyRig.START + 30 * SEC);
        int id = rig.client.connection().playerId();
        int ring = 1 << 12;
        byte[][] truth = new byte[ring][];
        long[] truthFrames = new long[ring];
        int[] truthTick = new int[ring];
        ByteBuffer enc = ByteBuffer.allocate(ShipBody.WIRE_SIZE);
        @SuppressWarnings("unchecked")
        List<double[]>[] shots = new List[ring];
        java.util.Map<Long, Double> alive = new java.util.HashMap<>();
        int[] ends = {0, 0};
        long touches0 = rig.server.world().byId(id).lastWallTouch;
        rig.onTick = (r, t) -> {
            int tick = r.server.net.currentTick();
            Player p = r.server.world().byId(id);
            if (p == null) {
                return;
            }
            p.encode(enc, 0, r.server.world().frames);
            int slot = tick & (ring - 1);
            truth[slot] = enc.array().clone();
            truthFrames[slot] = r.server.world().frames;
            truthTick[slot] = tick;
            List<double[]> own = new ArrayList<>();
            com.fulcrologic.jxpilot.server.world.Objects o = r.server.world().objects;
            java.util.Map<Long, Double> now = new java.util.HashMap<>();
            for (int k = 0; k < o.count(); k++) {
                int h = o.active[k];
                if (o.type[h] == com.fulcrologic.jxpilot.common.sim.Obj.SHOT && o.owner[h] == id && o.shotOrdinal[h] >= 0) {
                    now.put((long) o.spawnTick[h] << 8 | o.shotOrdinal[h], o.life[h]);
                }
            }
            for (java.util.Map.Entry<Long, Double> e : alive.entrySet()) {
                if (!now.containsKey(e.getKey())) {
                    ends[e.getValue() <= 1 ? 0 : 1]++;   // life ran out, or a wall or ship ended it early
                }
            }
            alive.clear();
            alive.putAll(now);
            for (int k = 0; k < o.count(); k++) {
                int h = o.active[k];
                if (o.type[h] == com.fulcrologic.jxpilot.common.sim.Obj.SHOT && o.owner[h] == id && o.shotOrdinal[h] >= 0) {
                    double dx = r.server.world().wrapDx(o.cx[h] / 64.0 - p.cx / 64.0);
                    double dy = r.server.world().wrapDy(o.cy[h] / 64.0 - p.cy / 64.0);
                    own.add(new double[] {o.spawnTick[h], o.shotOrdinal[h], o.cx[h] / 64.0, o.cy[h] / 64.0,
                        Math.hypot(dx, dy)});
                }
            }
            shots[slot] = own;
        };
        int[] compared = {0};
        int[] last = {-1};
        List<String> mismatches = new ArrayList<>();
        List<String> shotMismatches = new ArrayList<>();
        int[] ownSeen = {0};
        ByteBuffer mine = ByteBuffer.allocate(ShipBody.WIRE_SIZE);
        rig.setKeys(t -> keys(t - spawn));
        rig.onFrame = (r, t) -> {
            KernelSync k = r.client.connection().kernel();
            if (!k.valid() || k.tick() == last[0]) {
                return;
            }
            last[0] = k.tick();
            int slot = k.tick() & (ring - 1);
            if (truthTick[slot] != k.tick() || truth[slot] == null) {
                return;
            }
            k.body().encode(mine, 0, truthFrames[slot]);
            compared[0]++;
            if (k.shotsTick() == k.tick()) {
                List<double[]> own = shots[slot];
                int near = 0;
                for (double[] sh : own) {
                    near += sh[4] < 300 ? 1 : 0;
                }
                int matched = 0;
                for (int i = 0; i < k.ownShots(); i++) {
                    boolean found = false;
                    for (double[] sh : own) {
                        double ex = r.server.world().wrapDx(k.shotX(i) - sh[2]);
                        double ey = r.server.world().wrapDy(k.shotY(i) - sh[3]);
                        if (sh[0] == k.shotSpawnTick(i) && sh[1] == k.shotOrdinal(i) && Math.abs(ex) <= 0.5
                            && Math.abs(ey) <= 0.5) {
                            found = true;
                            matched += sh[4] < 300 ? 1 : 0;
                        }
                    }
                    if (!found && shotMismatches.size() < 5) {
                        shotMismatches.add("tick " + k.tick() + " shot " + k.shotSpawnTick(i) + ":" + k.shotOrdinal(i));
                    }
                }
                ownSeen[0] += k.ownShots();
                if (matched < near && shotMismatches.size() < 5) {
                    shotMismatches.add("tick " + k.tick() + ": " + near + " own shots near the ship, " + matched + " sent");
                }
            }
            if (mine.mismatch(ByteBuffer.wrap(truth[slot])) >= 0 || k.frames() != truthFrames[slot]) {
                if (mismatches.size() < 5) {
                    mismatches.add("tick " + k.tick() + " byte " + mine.mismatch(ByteBuffer.wrap(truth[slot]))
                        + " frames " + k.frames() + " vs " + truthFrames[slot]);
                }
            }
        };
        rig.runUntil(spawn + 5 * SEC);
        Player p = rig.server.world().byId(id);
        int n = compared[0];
        System.out.printf("[predict round trip] %d ticks compared, %d own shots decoded, %d expired, %d ended early%n",
            n, ownSeen[0], ends[0], ends[1]);
        assertAll(
            () -> assertTrue(n > 60, "most ticks of 5 s at 14 Hz were compared (" + n + ")"),
            () -> assertEquals(List.of(), mismatches, "every decoded PREDICT is the server's state"),
            () -> assertEquals(List.of(), shotMismatches, "every decoded own shot is the server's, and none near is missing"),
            () -> assertTrue(ownSeen[0] > 20, "own shots were decoded (" + ownSeen[0] + " in all)"),
            () -> assertTrue(ends[0] > 0, "some own bullets expired (" + ends[0] + ")"),
            () -> assertTrue(ends[1] > 0, "some ended early, against a wall (" + ends[1] + ")"),
            () -> assertEquals(1, rig.server.world().numPlayers, "no other ship"),
            () -> assertTrue(p.lastWallTouch > touches0, "the flight touched a wall too (frame " + p.lastWallTouch + ")"),
            () -> assertTrue(rig.client.connection().kernel().predicts() >= n, "PREDICTs arrived"));
    }
}
