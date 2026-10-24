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
    static final int FRAMES_TREASURE_CASH = 95;

    /** Simulation over a map file from the test resources. */
    static Simulation resourceMap(String name, int seed) throws Exception {
        Options o = new Options();
        // the C server reads lib/defaults too (e.g. SoftWalls' wall bounce speeds)
        com.fulcrologic.jxpilot.server.net.DefaultsFile.applyBundled(o);
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
        return replayUntil(s, t, Long.MAX_VALUE);
    }

    /** {@link #replay} up to and including C frame {@code last} (debugging). */
    static Result replayUntil(Simulation s, ModeTrace t, long last) {
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
            if (f.frame > last) {
                break;
            }
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
            if ((pl2.used() & Has.SHOT) != 0) {
                keys |= ControlKey.FIRE_SHOT.mask();
            }
            if ((pl2.used() & Has.SHIELD) != 0) {
                // C's client keeps the shield up and lowers it to fire (autoShield)
                keys |= ControlKey.SHIELD.mask();
            }
            boolean connected = false;
            boolean recreated = false;
            for (ModeTrace.Obj o : f.objs) {
                connected |= o.type() == Obj.BALL && o.id() == p.id;
                // a ball C deleted and remade in its treasure starts its life count again
                recreated |= o.type() == Obj.BALL && o.life() == Long.MAX_VALUE;
            }
            if (wasConnected && !connected && !recreated && (pl2.used() & Has.CONNECTOR) == 0) {
                // the ball came loose this frame and lives on, and the connector went off with it: C's player
                // pressed DROP_BALL ('d', Detach_ball(ind, -1)); a snapped connector stays on
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
            long msgFrom = w.events.seq();
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
            // C keeps scores in DFLOAT (float), we in double (DEVIATIONS "Score"): equal to float precision
            if (!Double.isNaN(pl2.score()) && Math.abs(pl2.score() - p.score) > 1e-5 * Math.max(1, Math.abs(p.score))
                && bad.size() < 6) {
                bad.add("frame " + c.frame() + " score\n  C    " + pl2.score() + "\n  Java " + p.score);
            }
            List<String> cmsg = new ArrayList<>();
            for (String[] m : f.messages) {
                if (Long.parseLong(m[0]) == f.shipLoop) {
                    cmsg.add(m[1]);
                }
            }
            List<String> jmsg = SimFixture.messages(w, msgFrom);
            if (f.shipLoop >= 0 && !cmsg.equals(jmsg) && bad.size() < 6) {
                bad.add("frame " + c.frame() + " messages\n  C    " + cmsg + "\n  Java " + jmsg);
            }
            for (int[] tr : f.treasures) {
                String ct = "treasure " + tr[0] + " have " + tr[1] + " destroyed " + tr[2];
                String jt = "treasure " + tr[0] + " have " + (w.modes.treasureHave[tr[0]] ? 1 : 0) + " destroyed "
                    + w.modes.treasureDestroyed[tr[0]];
                if (!ct.equals(jt) && bad.size() < 6) {
                    bad.add("frame " + c.frame() + "\n  C    " + ct + "\n  Java " + jt);
                }
            }
            for (int i = 0; i < f.teams.size(); i++) {
                String[] tc = f.teams.get(i).split(" ");
                int team = Integer.parseInt(tc[0]);
                String jt = team + " " + w.modes.teamTreasuresLeft[team] + " " + w.modes.teamTreasuresDestroyed[team];
                double cs = f.teamScores.get(i);
                if ((!f.teams.get(i).equals(jt) || Math.abs(cs - w.teamScore[team]) > 1e-6) && bad.size() < 6) {
                    bad.add("frame " + c.frame() + " team (team left destroyed score)\n  C    " + f.teams.get(i) + " " + cs
                        + "\n  Java " + jt + " " + w.teamScore[team]);
                }
            }
            for (int[] tg : f.targets) {
                String ct = "target " + tg[0] + " damage " + tg[1] + " dead " + tg[2];
                String jt = "target " + tg[0] + " damage " + w.modes.targetDamage[tg[0]] + " dead "
                    + w.targetDeadTime[tg[0]];
                if (!ct.equals(jt) && bad.size() < 6) {
                    bad.add("frame " + c.frame() + "\n  C    " + ct + "\n  Java " + jt);
                }
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
            () -> assertEquals(211, r.frames(), "211 C frames are compared"),
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
            () -> assertEquals(213, r.frames(), "213 C frames are compared"),
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
    @DisplayName("targets: shots wear a team 2 target down, it repairs between shots, is destroyed and revives: "
        + "bit-identical with C's random stream")
    void targetShoot() throws Exception {
        ModeTrace t = ModeTrace.load("target-shoot.txt");
        Simulation s = resourceMap("target-shoot.xp", 1);
        Result r = replay(s, t);
        assertAll(
            () -> assertEquals(171, r.frames(), "171 C frames are compared"),
            () -> assertTrue(t.frames.stream().anyMatch(f -> f.targets.get(0)[2] > 0), "(C destroyed it)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("treasure capture: connect to the team 2 ball, pull it into the own treasure; with nobody of team 2 "
        + "in the game C only awards Rate/2 and the ball goes home: bit-identical with C's random stream")
    void treasureCash() throws Exception {
        ModeTrace t = ModeTrace.load("treasure-cash.txt");
        Simulation s = resourceMap("treasure-cash.xp", 1);
        Result r = replay(s, t);
        double last = t.frames.get(t.frames.size() - 1).teamScores.get(0);
        assertAll(
            () -> assertEquals(FRAMES_TREASURE_CASH, r.frames(), "all C frames are compared"),
            () -> assertTrue(last > 0, "(team 1 scored " + last + ")"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("a 2 lap race: two climbs, lap times and best lap, the finish, Race_game_over's reset and a new "
        + "race: bit-identical with C's random stream")
    void raceLaps() throws Exception {
        ModeTrace t = ModeTrace.load("race-laps.txt");
        Simulation s = resourceMap("race-laps.xp", 1);
        Result r = replay(s, t);
        boolean reset = t.frames.stream().anyMatch(f -> f.frame > 200 && f.players.get(0).round() == 0);
        assertAll(
            () -> assertEquals(405, r.frames(), "405 C frames are compared"),
            () -> assertTrue(reset, "(C finished the race and reset it)"),
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
            () -> assertEquals(107, r.frames(), "107 C frames are compared"),
            () -> assertTrue(t.frames.get(t.frames.size() - 1).objs.get(0).id() == -1, "(the ball was dropped)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    /** Frames in which C's ship score went up (asteroids it broke), from the trace. */
    static long scoreRises(ModeTrace t) {
        long n = 0;
        double last = 0;
        for (ModeTrace.Frame f : t.frames) {
            double sc = f.players.get(0).score();
            if (sc > last + 0.5) {
                n++;
            }
            last = sc;
        }
        return n;
    }

    @Test
    @DisplayName("an idle shielded ship under an asteroid concentrator: asteroids hit it, break, push it into a "
        + "treasure (death), break-up debris hits it: bit-identical with C's random stream and particles")
    void asteroidRam() throws Exception {
        ModeTrace t = ModeTrace.load("asteroid-ram.txt");
        Simulation s = resourceMap("asteroid-ram.xp", 1);
        Result r = replay(s, t);
        boolean died = t.frames.stream().anyMatch(f -> f.messages.stream()
            .anyMatch(m -> m[1].equals("Refpilot smashed against a treasure.")));
        assertAll(
            () -> assertEquals(268, r.frames(), "268 C frames are compared"),
            () -> assertTrue(scoreRises(t) >= 4, "(the ship broke asteroids: " + scoreRises(t) + ")"),
            () -> assertTrue(died, "(and died)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("thrusting through asteroids: thrust sparks push them, the ship rams and breaks them: bit-identical")
    void asteroidThrust() throws Exception {
        ModeTrace t = ModeTrace.load("asteroid-thrust.txt");
        Simulation s = resourceMap("asteroid-box.xp", 1);
        Result r = replay(s, t);
        assertAll(
            () -> assertEquals(194, r.frames(), "194 C frames are compared"),
            () -> assertTrue(scoreRises(t) >= 4, "(the ship broke asteroids: " + scoreRises(t) + ")"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("turn and thrust among asteroids, breaking them down to size 1: bit-identical")
    void asteroidTurn() throws Exception {
        ModeTrace t = ModeTrace.load("asteroid-turn.txt");
        Simulation s = resourceMap("asteroid-box.xp", 1);
        Result r = replay(s, t);
        assertAll(
            () -> assertEquals(213, r.frames(), "213 C frames are compared"),
            () -> assertTrue(scoreRises(t) >= 4, "(the ship broke asteroids: " + scoreRises(t) + ")"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("lift the ball out of its shaft, drop it, and it lands back in its treasure (and rests there "
        + "again, life restarted): bit-identical")
    void ballShaft() throws Exception {
        ModeTrace t = ModeTrace.load("ball-shaft.txt");
        Simulation s = resourceMap("ball-shaft.xp", 1);
        Result r = replay(s, t);
        long landings = 0;
        long last = Long.MAX_VALUE;
        for (ModeTrace.Frame f : t.frames) {
            ModeTrace.Obj b = f.objs.get(0);
            if (b.id() == -1 && last < b.life()) {
                landings++;
            }
            last = b.life();
        }
        long n = landings;
        assertAll(
            () -> assertEquals(109, r.frames(), "109 C frames are compared"),
            () -> assertTrue(n >= 1, "(the loose ball landed in its treasure: " + n + ")"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("an over-stretched connector snaps (5%), the held connector reaches and reconnects, three times: "
        + "bit-identical")
    void ballSnap() throws Exception {
        ModeTrace t = ModeTrace.load("ball-snap.txt");
        Simulation s = resourceMap("ball-snap.xp", 1);
        Result r = replay(s, t);
        long snaps = 0;
        boolean was = false;
        for (ModeTrace.Frame f : t.frames) {
            boolean connected = f.objs.get(0).id() != -1;
            if (was && !connected && (f.players.get(0).used() & Has.CONNECTOR) != 0) {
                snaps++;
            }
            was = connected;
        }
        long n = snaps;
        assertAll(
            () -> assertEquals(120, r.frames(), "120 C frames are compared"),
            () -> assertEquals(3, n, "(the connector snapped 3 times with the connector key held)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("tow the ball up, drop it, and it bounces back into the ship: the shielded ship bounces off it and "
        + "pays ED_BALL_HIT: bit-identical")
    void ballRam() throws Exception {
        ModeTrace t = ModeTrace.load("ball-ram.txt");
        Simulation s = resourceMap("ball-tow.xp", 1);
        Result r = replay(s, t);
        long hits = 0;
        for (int i = 1; i < t.frames.size(); i++) {
            // 50 fuel (12800) for the ball plus the shield's 51
            if (t.frames.get(i - 1).ship.fuel() - t.frames.get(i).ship.fuel() == 12851) {
                hits++;
            }
        }
        long n = hits;
        assertAll(
            () -> assertEquals(108, r.frames(), "108 C frames are compared"),
            () -> assertEquals(1, n, "(the ship hit the ball once)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("team rounds with targets and one life: shots wear team 2's target down (it repairs), the only "
        + "team 1 ship self-destructs, the round ends in a draw and the next begins: bit-identical")
    void teamRounds() throws Exception {
        ModeTrace t = ModeTrace.load("team-rounds.txt");
        Simulation s = resourceMap("team-rounds.xp", 1);
        Result r = replay(s, t);
        boolean draw = t.frames.stream().anyMatch(f -> f.messages.stream()
            .anyMatch(m -> m[1].equals(" < We have a draw as everyone died! >")));
        boolean damaged = t.frames.stream().anyMatch(f -> f.targets.get(0)[1] < 64000);
        assertAll(
            () -> assertEquals(340, r.frames(), "340 C frames are compared"),
            () -> assertTrue(damaged, "(the target took damage)"),
            () -> assertTrue(draw, "(the round ended)"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }
}
