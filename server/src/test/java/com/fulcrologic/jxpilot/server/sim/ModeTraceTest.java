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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.math.CRandom;
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden traces of the game modes against the original C server (tools/xpilot-ref trace.sh with
 * {@code --modes}, patch 0005; see {@code src/test/resources/ctrace/README.txt}). The same map and inputs
 * are replayed in the Java simulation and the ship, every ball/asteroid/wreckage object and the race state are
 * compared frame by frame with no tolerance.
 * <p>
 * Key state per frame comes from C's own trace: THRUSTING, the sign of turnacc, and the connector (HAS_CONNECTOR
 * in {@code used}). C's {@code LONG_MAX} ball life is compared as frames counted down from it.
 */
@DisplayName("C golden traces (game modes)")
class ModeTraceTest {
    /** The C server's random seed in every recording (trace.sh --seed 42). */
    static final int SEED = 42;

    /** Simulation over a map file from the test resources. */
    static Simulation resourceMap(String name, int seed) throws Exception {
        Options o = new Options();
        Path p = Path.of(ModeTraceTest.class.getResource("/ctrace/" + name).toURI());
        ConfigParser.parseFile(p, OptionOrigin.MAP, o);
        return new Simulation(new World(o, seed));
    }

    /** A Java object as the C XPMOBJ columns that matter (life as frames below the "infinite" start). */
    static String objKey(World w, int h) {
        Objects o = w.objects;
        StringBuilder sb = new StringBuilder();
        sb.append(o.type[h]).append(' ').append(o.cx[h]).append(' ').append(o.cy[h]).append(' ')
            .append(o.prevX[h]).append(' ').append(o.prevY[h]).append(' ').append(o.vx[h]).append(' ')
            .append(o.vy[h]).append(' ');
        if (o.type[h] == Obj.BALL) {
            sb.append((long) (Balls.BALL_LIFE - o.life[h]));
        } else {
            sb.append((long) o.life[h]);
        }
        sb.append(' ').append(o.owner[h]).append(' ').append(o.team[h]).append(' ')
            .append(Integer.toHexString(o.status[h])).append(' ').append(o.mass[h]);
        if (o.type[h] == Obj.BALL) {
            sb.append(' ').append(w.modes.ballOwner[h]).append(' ').append(w.modes.ballTreasure[h]).append(' ')
                .append(w.modes.ballLength[h]);
        } else {
            sb.append(' ').append(w.objects.size[h]).append(' ').append(w.objects.rotation[h]).append(' ')
                .append(w.objects.turnSpeed[h]);
        }
        return sb.toString();
    }

    /** The same key from a C line (C ids/teams mapped: NO_ID -1, TEAM_NOT_SET 0xffff to -1). */
    static String objKey(ModeTrace.Obj c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.type()).append(' ').append(c.cx()).append(' ').append(c.cy()).append(' ').append(c.prevX())
            .append(' ').append(c.prevY()).append(' ').append(c.vx()).append(' ').append(c.vy()).append(' ');
        if (c.type() == Obj.BALL) {
            sb.append(Long.MAX_VALUE - c.life());
        } else {
            sb.append(c.life());
        }
        int team = c.team() == 0xFFFF ? Const.TEAM_NOT_SET : c.team();
        sb.append(' ').append(c.id()).append(' ').append(team).append(' ').append(Integer.toHexString(c.status()))
            .append(' ').append(c.mass());
        if (c.type() == Obj.BALL) {
            sb.append(' ').append(c.a()).append(' ').append(c.b()).append(' ').append(c.c());
        } else {
            sb.append(' ').append(c.a()).append(' ').append(c.b()).append(' ').append(c.c());
        }
        return sb.toString();
    }

    /** Result: frames compared and the first mismatches. */
    record Result(int frames, List<String> mismatches) {}

    /**
     * Replay a trace with one human ship: start on the base C used, C client settings (power 45, turnspeed 35,
     * turnresistance 30/256), per-frame keys from C's state.
     */
    static Result replay(Simulation s, ModeTrace t) {
        World w = s.world;
        Player p = s.ships.join("Refpilot", 0);
        CTraceTest.Frame first = t.frames.get(0).ship;
        int bx = (first.cx() >> 6) / 35;
        int by = (first.cy() >> 6) / 35;
        for (Base b : w.map.bases()) {
            if (b.bx() == bx && b.by() == by) {
                p.homeBase = b.index();
            }
        }
        s.ships.goHome(p);
        s.ships.applySettings(p, 45f, 35f, 30 / 256f, 35f, 25f, 30 / 256f);
        long[] in = new long[w.players.length];
        List<String> bad = new ArrayList<>();
        boolean wasConnected = false;
        boolean wasDestructing = false;
        // a trace with C's draw counts replays C's random stream: seeded like the C server (XPILOT_SEED 42),
        // brought to C's count before every frame (the C frame code draws too), its draws checked after
        CRandom mt = t.frames.get(0).drawsBefore >= 0 ? new CRandom(SEED) : null;
        w.rng.useC(mt);
        for (ModeTrace.Frame f : t.frames) {
            if (mt != null) {
                if (mt.draws() > f.drawsBefore && bad.size() < 6) {
                    bad.add("frame " + f.frame + ": Java drew " + (mt.draws() - f.drawsBefore) + " more than C before it");
                }
                w.rng.skip((int) Math.max(0, f.drawsBefore - mt.draws()));
            }
            CTraceTest.Frame c = f.ship;
            long keys = c.thrusting() ? ControlKey.THRUST.mask() : 0;
            keys |= c.turnAcc() > 0 ? ControlKey.TURN_LEFT.mask() : (c.turnAcc() < 0 ? ControlKey.TURN_RIGHT.mask() : 0);
            ModeTrace.Pl2 pl2 = f.players.get(0);
            if ((pl2.used() & Has.CONNECTOR) != 0) {
                keys |= ControlKey.CONNECTOR.mask();
            }
            boolean connected = false;
            for (ModeTrace.Obj o : f.objs) {
                connected |= o.type() == Obj.BALL && o.id() == p.id;
            }
            if (wasConnected && !connected) {
                // the ball came loose this frame: C's player pressed DROP_BALL ('d')
                keys |= ControlKey.DROP_BALL.mask();
            }
            wasConnected = connected;
            boolean destructing = (f.shipStatus & Obj.SELF_DESTRUCT) != 0;
            if (destructing && !wasDestructing) {
                // C's player toggled self-destruct this frame (End)
                keys |= ControlKey.SELF_DESTRUCT.mask();
            }
            wasDestructing = destructing;
            in[p.slot] = keys;
            s.tick(in);
            if (bad.size() >= 6) {
                continue;
            }
            if (mt != null && mt.draws() != f.drawsAfter) {
                bad.add("frame " + f.frame + " random draws in the frame: C " + (f.drawsAfter - f.drawsBefore)
                    + ", Java " + (mt.draws() - f.drawsBefore));
            }
            CTraceTest.Frame j = CTraceTest.Frame.of(c.frame(), p);
            if (!j.equals(c)) {
                bad.add("frame " + c.frame() + " ship\n  C    " + c + "\n  Java " + j);
            }
            List<String> cobj = new ArrayList<>();
            for (ModeTrace.Obj o : f.objs) {
                cobj.add(objKey(o));
            }
            List<String> jobj = new ArrayList<>();
            Objects o = w.objects;
            for (int k = 0; k < o.count(); k++) {
                int h = o.active[k];
                if ((o.type[h] & (Obj.BALL | Obj.ASTEROID | Obj.WRECKAGE)) != 0) {
                    jobj.add(objKey(w, h));
                }
            }
            // C's object order follows its debris, which Java makes differently (client replay), so after
            // debris died the pool orders differ: compare the objects as a set
            java.util.Collections.sort(cobj);
            java.util.Collections.sort(jobj);
            if (!cobj.equals(jobj)) {
                bad.add("frame " + c.frame() + " objects\n  C    " + cobj + "\n  Java " + jobj);
            }
            String cr = pl2.check() + " " + pl2.round() + " " + pl2.time() + " " + pl2.bestLap() + " "
                + pl2.reaching() + " " + ((pl2.have() & Has.BALL) != 0);
            String jr = p.check + " " + p.round + " " + p.time + " " + p.bestLap + " " + (p.ball >= 0) + " "
                + ((p.have & Has.BALL) != 0);
            if (!cr.equals(jr)) {
                bad.add("frame " + c.frame() + " race/connector (check round time best reaching hasBall)\n  C    "
                    + cr + "\n  Java " + jr);
            }
        }
        return new Result(t.frames.size(), bad);
    }

    @Test
    @DisplayName("connect, tow and swing a ball on the spring connector: ship and ball bit-identical")
    void ballTow() throws Exception {
        ModeTrace t = ModeTrace.load("ball-tow.txt");
        Simulation s = resourceMap("ball-tow.xp", 1);
        Result r = replay(s, t);
        assertAll(
            () -> assertEquals(75, r.frames(), "75 C frames are compared"),
            () -> assertTrue(t.frames.stream().anyMatch(f -> f.objs.get(0).id() == 1), "(the ball gets connected)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("race: pass A at the start, climb through B, fall back through A completing lap 1: bit-identical")
    void raceClimb() throws Exception {
        ModeTrace t = ModeTrace.load("race-climb.txt");
        Simulation s = resourceMap("race-climb.xp", 1);
        Result r = replay(s, t);
        ModeTrace.Pl2 last = t.frames.get(t.frames.size() - 1).players.get(0);
        assertAll(
            () -> assertEquals(213, r.frames(), "213 C frames are compared"),
            () -> assertEquals(2, last.round(), "(C completed lap 1)"),
            () -> assertEquals(171, last.bestLap(), "(in 171 frames)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("asteroids with C's random stream: placement, motion, bounces, spin, breaks, wreckage: bit-identical")
    void asteroidBoxExact() throws Exception {
        ModeTrace t = ModeTrace.load("asteroid-box.txt");
        Simulation s = resourceMap("asteroid-box.xp", 1);
        Result r = replay(s, t);
        long breaks = t.frames.stream().filter(f -> f.objs.stream().anyMatch(o -> o.type() == Obj.WRECKAGE)).count();
        assertAll(
            () -> assertEquals(214, r.frames(), "214 C frames are compared"),
            () -> assertTrue(breaks > 10, "(asteroids broke: " + breaks + " frames with wreckage)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("race respawn with C's random stream: self-destruct after lap 1 started, explode, reappear at the "
        + "last checkpoint with C's random speed and heading: bit-identical")
    void raceRespawn() throws Exception {
        ModeTrace t = ModeTrace.load("race-respawn.txt");
        Simulation s = resourceMap("race-climb.xp", 1);
        Result r = replay(s, t);
        long respawn = t.frames.stream().filter(f -> f.ship.dir() != 32).count();
        assertAll(
            () -> assertEquals(255, r.frames(), "255 C frames are compared"),
            () -> assertTrue(respawn > 0, "(C's respawn turned the ship: " + respawn + " frames)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("CRandom is C's generator: seeded with 42, it reaches the state the C server dumped")
    void cRandomMatchesTheDump() throws Exception {
        ModeTrace t = ModeTrace.load("asteroid-box.txt");
        CRandom ours = new CRandom(SEED);
        for (long i = 0; i < t.mtDraws; i++) {
            ours.next();
        }
        CRandom dumped = new CRandom(0);
        dumped.load(t.mtLeft, t.mtNext, t.mtState, t.mtDraws);
        boolean same = true;
        for (int i = 0; i < 2000; i++) {
            same &= ours.next() == dumped.next();
        }
        boolean all = same;
        assertAll(
            () -> assertTrue(t.mtFrame > 0, "(the trace has the dump)"),
            () -> assertTrue(all, "the next 2000 values (over a reload) agree"));
    }

    @Test
    @DisplayName("tow, drop the ball, and its free flight with wall bounces (no life factor for balls): bit-identical")
    void ballDrop() throws Exception {
        ModeTrace t = ModeTrace.load("ball-drop.txt");
        Simulation s = resourceMap("ball-tow.xp", 1);
        Result r = replay(s, t);
        assertAll(
            () -> assertEquals(112, r.frames(), "112 C frames are compared"),
            () -> assertTrue(t.frames.get(t.frames.size() - 1).objs.get(0).id() == -1, "(the ball was dropped)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }
}
