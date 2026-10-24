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

import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden traces of the original C server (tools/xpilot-ref trace.sh, see
 * {@code src/test/resources/ctrace/README.txt}): the same map, options and inputs replayed in the Java
 * simulation, compared frame by frame with no tolerance. One sim tick is one C frame, and the physics state
 * (position in clicks, the float velocity, acceleration, heading, turn state, mass) and the fixed-point fuel
 * must be bit-identical.
 * <p>
 * Replay rules: the ship starts on the base C used (seeded C RNG picks it); the C client sent power 45,
 * turnspeed 35 and turnresistance 0.12 as 1/256 fixed point (= 30/256) in its settings packet; the key state
 * of frame f is taken from C's own trace (THRUSTING bit, sign of turnacc), so key timing needs no
 * alignment. C cannon fire is random (rfrac) and never reached the C ship in these runs, so the Java cannons
 * are switched off; two traces ran with {@code +sparksWallBounce} so the ship's own exhaust (random spark
 * directions) cannot bounce back into it.
 */
@DisplayName("C golden traces (default.xp)")
class CTraceTest {
    /** One XPTRACE line (columns of patch 0002). */
    record Frame(long frame, int cx, int cy, float vx, float vy, float ax, float ay, int dir, float floatDir,
                 float turnVel, float turnAcc, float power, float mass, long fuel, boolean thrusting) {
        static Frame parse(String line) {
            String[] c = line.trim().split("\\s+");
            return new Frame(Long.parseLong(c[2]), Integer.parseInt(c[7]), Integer.parseInt(c[8]),
                Float.parseFloat(c[9]), Float.parseFloat(c[10]), Float.parseFloat(c[11]), Float.parseFloat(c[12]),
                Integer.parseInt(c[13]), Float.parseFloat(c[14]), Float.parseFloat(c[15]), Float.parseFloat(c[16]),
                Float.parseFloat(c[17]), Float.parseFloat(c[18]), Long.parseLong(c[19]), "1".equals(c[20]));
        }

        /** The same columns read from a Java player. */
        static Frame of(long frame, Player p) {
            return new Frame(frame, p.cx, p.cy, p.vx, p.vy, p.accX, p.accY, p.dir, p.floatDir, p.turnVel,
                p.turnAcc, p.power, p.mass, (long) p.fuel, (p.status & Obj.THRUSTING) != 0);
        }
    }

    static List<Frame> load(String name) throws IOException {
        List<Frame> out = new ArrayList<>();
        try (InputStream in = CTraceTest.class.getResourceAsStream("/ctrace/" + name)) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                if (l.startsWith("XPTRACE ")) {
                    out.add(Frame.parse(l));
                }
            }
        }
        return out;
    }

    /** Result of one replay: frames compared and the mismatching frames (Java vs C). */
    record Result(int frames, List<String> mismatches) {}

    static Result replay(String trace, boolean sparksBounce) throws IOException {
        return replay(trace, sparksBounce, 0);
    }

    /**
     * BEHAVIOR: frames up to {@code syncUntil} are not compared; after each of them the ship is set to C's
     * state (position, velocity, heading, turn state, fuel, mass), so events Java does not replay (a C cannon
     * shot) are carried over.
     */
    static Result replay(String trace, boolean sparksBounce, long syncUntil) throws IOException {
        List<Frame> c = load(trace);
        Simulation s = TestWorlds.shipped("default.xp", 1);
        World w = s.world;
        if (!sparksBounce) {
            w.params.objBounceMask &= ~Obj.SPARK;
        }
        Arrays.fill(w.cannonDeadTime, Integer.MAX_VALUE);
        Player p = s.ships.join("Refpilot", 0);
        // C frame 1 is after one move from the base centre: the base block holds the first position
        int bx = (c.get(0).cx() >> 6) / 35;
        int by = (c.get(0).cy() >> 6) / 35;
        for (Base b : w.map.bases()) {
            if (b.bx() == bx && b.by() == by) {
                p.homeBase = b.index();
            }
        }
        s.ships.goHome(p);
        s.ships.applySettings(p, 45f, 35f, 30 / 256f, 35f, 25f, 30 / 256f);
        long[] in = new long[w.players.length];
        List<String> bad = new ArrayList<>();
        for (Frame f : c) {
            long keys = f.thrusting() ? ControlKey.THRUST.mask() : 0;
            keys |= f.turnAcc() > 0 ? ControlKey.TURN_LEFT.mask() : (f.turnAcc() < 0 ? ControlKey.TURN_RIGHT.mask() : 0);
            in[p.slot] = keys;
            s.tick(in);
            if (f.frame() <= syncUntil) {
                p.setClicks(f.cx(), f.cy());
                p.vx = f.vx();
                p.vy = f.vy();
                p.velocity = (float) Math.hypot(f.vx(), f.vy());
                p.dir = f.dir();
                p.floatDir = f.floatDir();
                p.turnVel = f.turnVel();
                p.oldTurnVel = f.turnVel();
                p.fuel = f.fuel();
                p.mass = f.mass();
                continue;
            }
            Frame j = Frame.of(f.frame(), p);
            if (!j.equals(f)) {
                bad.add("frame " + f.frame() + "\n  C    " + f + "\n  Java " + j);
            }
        }
        return new Result((int) c.stream().filter(f -> f.frame() > syncUntil).count(), bad);
    }

    @Test
    @DisplayName("thrust 13 frames then coast through the gravity field: every frame bit-identical")
    void thrustCoast() throws IOException {
        Result r = replay("thrust-coast.txt", false);
        assertAll(
            () -> assertEquals(64, r.frames(), "64 C frames are compared"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("resting on the base (floor bounces), a wall-blocked turn and its decay: bit-identical")
    void restAndBlockedTurn() throws IOException {
        Result r = replay("rest-turn.txt", true);
        List<Frame> c = load("rest-turn.txt");
        assertAll(
            () -> assertEquals(68, r.frames(), "68 C frames are compared"),
            () -> assertTrue(c.stream().anyMatch(f -> f.turnAcc() != 0), "(the trace turns)"),
            () -> assertTrue(c.stream().filter(f -> f.vy() < 0).count() > 5, "(the trace bounces on the floor)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("thrust, turn while thrusting, coast and a 25 px/frame wall bounce: bit-identical")
    void thrustTurnImpact() throws IOException {
        Result r = replay("thrust-turn-impact.txt", false);
        List<Frame> c = load("thrust-turn-impact.txt");
        assertAll(
            () -> assertEquals(139, r.frames(), "139 C frames are compared"),
            () -> assertTrue(c.stream().anyMatch(f -> f.vy() > 10) && c.stream().anyMatch(f -> f.vy() < -25),
                "(the trace bounces off a wall at speed)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("thrust held against the ceiling (sliding along it), then an impact: bit-identical")
    void wallThrust() throws IOException {
        Result r = replay("wall-thrust.txt", false);
        List<Frame> c = load("wall-thrust.txt");
        assertAll(
            () -> assertEquals(111, r.frames(), "111 C frames are compared"),
            () -> assertTrue(c.stream().filter(f -> f.thrusting() && f.cy() >> 6 == 1458).count() > 30,
                "(the trace thrusts for more than 30 frames at the ceiling)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("thrust into a 45-degree slope at 16 px/frame and the bounce: bit-identical after C's cannon hit")
    void slopeImpact() throws IOException {
        Result r = replay("slope-impact.txt", false, 26);
        List<Frame> c = load("slope-impact.txt");
        assertAll(
            () -> assertEquals(63, r.frames(), "frames 27-89 are compared"),
            () -> assertTrue(c.stream().anyMatch(f -> f.frame() > 26 && f.vx() > 16)
                && c.stream().anyMatch(f -> f.frame() > 26 && f.vy() < -9.9), "(the trace bounces off the slope)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }
}
