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
package com.fulcrologic.jxpilot.server.snapshot;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SnapshotBuilder game-mode sections (targets, treasures, timers, balls, connectors, asteroids, wreckage, radar)")
class SnapshotModesTest {
    static final String[] ROWS = {
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "x                                                          x",
        "x   !                                                  !   x",
        "x                                                          x",
        "x   *                                                  *   x",
        "x 1 1                                                  2 2 x",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    };

    final ByteBuffer[] parts = new ByteBuffer[4];
    /** One builder per world, as the server keeps one (it remembers which viewers had timers). */
    final java.util.Map<World, SnapshotBuilder> builders = new java.util.IdentityHashMap<>();

    record Out(List<String> targets, List<String> treasures, List<String> timers, List<String> balls,
               List<String> connectors, List<String> asteroids, List<String> wrecks, List<String> radar,
               List<Integer> shipFlags) {}

    Out build(Simulation s, Player viewer, boolean radar) {
        World w = s.world;
        for (int i = 0; i < parts.length; i++) {
            parts[i] = ByteBuffer.allocate(1200);
        }
        int n = builders.computeIfAbsent(w, SnapshotBuilder::new).build(viewer, 1, w.events.burstSeq(), radar, parts);
        Out o = new Out(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        SnapshotReader r = new SnapshotReader();
        r.setWorld(w.pixelWidth, w.pixelHeight, w.wrap);
        Records.TargetState tg = new Records.TargetState();
        Records.TreasureState tr = new Records.TreasureState();
        Records.Timers tm = new Records.Timers();
        Records.Ball bl = new Records.Ball();
        Records.Connector cn = new Records.Connector();
        Records.Asteroid as = new Records.Asteroid();
        Records.Wreckage wr = new Records.Wreckage();
        Records.Radar rd = new Records.Radar();
        Records.Ship sh = new Records.Ship();
        for (int p = 0; p < n; p++) {
            ByteBuffer b = parts[p].duplicate();
            b.flip();
            assertTrue(r.begin(b), "header");
            while (r.nextSection()) {
                for (int i = 0; i < r.count(); i++) {
                    switch (r.type()) {
                        case Section.TARGET -> {
                            tg.wrap(r, i);
                            o.targets.add(tg.index() + ":" + tg.flags() + ":" + Math.round(tg.damageLeft() * 100));
                        }
                        case Section.TREASURE -> {
                            tr.wrap(r, i);
                            o.treasures.add(tr.index() + ":" + tr.flags());
                        }
                        case Section.TIMERS -> {
                            tm.wrap(r, i);
                            o.timers.add(tm.damaged() + ":" + tm.roundDelay() + ":" + tm.timeLeft()
                                + (tm.shutdownCount() >= 0 ? " shutdown " + tm.shutdownCount() + "/" + tm.shutdownDelay()
                                : ""));
                        }
                        case Section.BALL -> {
                            bl.wrap(r, i);
                            o.balls.add(bl.id() + ":" + Math.round(bl.x()) + "," + Math.round(bl.y()) + ":" + bl.team()
                                + ":" + bl.connectedPlayer());
                        }
                        case Section.CONNECTOR -> {
                            cn.wrap(r, i);
                            o.connectors.add(Math.round(cn.x0()) + "," + Math.round(cn.y0()) + "-" + Math.round(cn.x1())
                                + "," + Math.round(cn.y1()) + ":" + cn.kind());
                        }
                        case Section.ASTEROID -> {
                            as.wrap(r, i);
                            o.asteroids.add(Math.round(as.x()) + "," + Math.round(as.y()) + ":" + as.size() + ":"
                                + Math.round(as.rotation() * Const.RES / (2 * Math.PI)));
                        }
                        case Section.WRECKAGE -> {
                            wr.wrap(r, i);
                            o.wrecks.add(Math.round(wr.x()) + "," + Math.round(wr.y()) + ":" + wr.shape() + ":" + wr.size()
                                + ":" + Math.round(wr.rotation() * Const.RES / (2 * Math.PI)));
                        }
                        case Section.RADAR -> {
                            rd.wrap(r, i);
                            o.radar.add(String.valueOf(rd.flags()));
                        }
                        case Section.SHIP -> {
                            sh.wrap(r, i);
                            o.shipFlags.add(sh.flags());
                        }
                        default -> { }
                    }
                }
            }
        }
        return o;
    }

    @Test
    @DisplayName("map objects in view: targets (dead flag, strength), treasures (has ball); far ones culled")
    void mapObjects() {
        Simulation s = TestWorlds.sim("teamPlay: yes\ngravity: 0\n", ROWS);
        World w = s.world;
        Player v = TestWorlds.join(s, "V", 1);
        v.initPixels(4.5 * 35, 3 * 35);
        w.modes.targetDamage[0] = w.modes.targetDamage[0] / 4;
        Out o = build(s, v, false);
        w.targetDeadTime[0] = 5;
        w.modes.treasureHave[0] = false;
        Out dead = build(s, v, false);
        assertAll(
            () -> assertEquals(List.of("0:0:25"), o.targets(), "the near target: alive, a quarter left (the far one culled)"),
            () -> assertEquals(List.of("0:1"), o.treasures(), "the near treasure has its ball"),
            () -> assertEquals(List.of("0:1:25"), dead.targets(), "dead flag"),
            () -> assertEquals(List.of("0:0"), dead.treasures(), "no ball at home"),
            () -> assertEquals(List.of(), o.timers(), "no timer active: no TIMERS"));
    }

    @Test
    @DisplayName("balls (handle, treasure team, holder), the reaching connector, asteroids, wreckage, ship flags")
    void objects() {
        Simulation s = TestWorlds.sim("teamPlay: yes\ngravity: 0\n", ROWS);
        World w = s.world;
        Player v = TestWorlds.join(s, "V", 1);
        v.initPixels(10 * 35, 3 * 35);
        Player h = TestWorlds.join(s, "H", 2);
        h.initPixels(12 * 35, 3 * 35);
        int ball = 0;
        w.objects.owner[ball] = h.id;
        h.have |= Has.BALL;
        h.used |= Has.REPAIR;
        h.used &= ~Has.SHIELD;
        v.ball = ball;
        int ast = s.asteroids.make(15 * 35, 3 * 35, 3, 0, 0f);
        w.objects.rotation[ast] = 32;
        s.wreckage.make(9 * 35, 3 * 35, 0f, 0f, -1, 0, 5f, 5f, 5f, 0, 6, 1, 0, 0, 0f, 0f, 5, 5);
        Out o = build(s, v, false);
        int bx = (int) Math.round(4.5 * 35);
        assertAll(
            () -> assertEquals(List.of(ball + ":" + bx + ",80:1:" + h.id), o.balls(),
                "the treasure ball: handle, team of its treasure (1), connected to H"),
            () -> assertEquals(List.of(bx + ",80-350,105:" + Records.Connector.KIND_BALL), o.connectors(),
                "V reaches for it: a connector from the ball to V"),
            () -> assertEquals(List.of("525,105:3:32"), o.asteroids(), "asteroid with size and rotation"),
            () -> assertEquals(1, o.wrecks().size(), "one wreckage piece"),
            () -> assertTrue(o.wrecks().get(0).startsWith("315,105:"), "at its place"),
            () -> assertEquals(List.of(Records.ShipFlags.HAS_BALL | Records.ShipFlags.REPAIRING), o.shipFlags(),
                "H carries a ball and repairs"));
    }

    @Test
    @DisplayName("gameDuration: the wall-clock seconds left while it runs, then the shutdown countdown (C Send_shutdown)")
    void gameDurationTimers() {
        Simulation s = TestWorlds.sim("teamPlay: yes\ngravity: 0\ngameDuration: 1\nmaxRoundTime: 60\n", ROWS);
        World w = s.world;
        Player v = TestWorlds.join(s, "V", 1);
        long[] now = {500};
        w.wallSeconds = () -> now[0];
        w.roundTime = 14 * 10;
        Out before = build(s, v, false);
        s.rounds.startGameDuration(true);
        s.rounds.checkGameDuration();
        Out running = build(s, v, false);
        now[0] = 560;
        s.rounds.checkGameDuration();
        w.roundTime = -1;
        Out over = build(s, v, false);
        w.shutdownServer = -1;
        Out stopped = build(s, v, false);
        assertAll(
            () -> assertEquals(List.of("0:0:10"), before.timers(), "not started: the round timer"),
            () -> assertEquals(List.of("0:0:60"), running.timers(), "60 s left on the wall clock"),
            () -> assertEquals(List.of("0:0:-1 shutdown 420/1000"), over.timers(),
                "game over: 30 * 14 frames to shutdown, against C's ShutdownDelay 1000"),
            () -> assertEquals(List.of("0:0:-1"), stopped.timers(), "no countdown: the all-clear record"));
    }

    @Test
    @DisplayName("timers while active; radar blips for balls and asteroids only when their options are on")
    void timersAndRadar() {
        Simulation s = TestWorlds.sim("teamPlay: yes\ngravity: 0\nmaxRoundTime: 60\ntreasuresOnRadar: yes\n"
            + "asteroidsOnRadar: yes\n", ROWS);
        World w = s.world;
        Player v = TestWorlds.join(s, "V", 1);
        v.damaged = 3;
        w.roundTime = 14 * 10 + 1;
        w.roundDelaySend = 5;
        w.roundDelay = 9;
        s.asteroids.make(30 * 35, 3 * 35, 1, 0, 0f);
        Out o = build(s, v, true);
        v.damaged = 0;
        w.roundTime = -1;
        w.roundDelaySend = 0;
        Out clear = build(s, v, false);
        Out after = build(s, v, false);
        Simulation off = TestWorlds.sim("teamPlay: yes\ngravity: 0\n", ROWS);
        Player q = TestWorlds.join(off, "Q", 1);
        off.asteroids.make(30 * 35, 3 * 35, 1, 0, 0f);
        Out o2 = build(off, q, true);
        long balls = o.radar().stream().filter(f -> f.equals(String.valueOf(Records.Radar.FLAG_BALL))).count();
        long asts = o.radar().stream().filter(f -> f.equals(String.valueOf(Records.Radar.FLAG_ASTEROID))).count();
        assertAll(
            () -> assertEquals(List.of("3:9:11"), o.timers(), "damaged, round delay, 141 frames = 11 s rounded up"),
            () -> assertEquals(List.of("0:0:-1"), clear.timers(), "once all stopped: one all-clear record"),
            () -> assertEquals(List.of(), after.timers(), "then nothing"),
            () -> assertEquals(2, balls, "both treasure balls on the radar"),
            () -> assertEquals(1, asts, "the asteroid on the radar"),
            () -> assertTrue(o2.radar().stream().noneMatch(f -> !f.equals(String.valueOf(Records.Radar.FLAG_SELF))),
                "options off: only the own ship"),
            () -> assertEquals(Protocol.NO_ID, 0xFFFF, "(NO_ID)"),
            () -> assertTrue(Obj.BALL > 0, "(ball)"));
    }

    @Test
    @DisplayName("every temporary wormhole is sent with its block and type")
    void tempWormholes() {
        Simulation s = TestWorlds.sim("teamPlay: yes\ngravity: 0\nwormTime: 5\n", ROWS);
        World w = s.world;
        Player v = TestWorlds.join(s, "V", 1);
        w.worms.addTemp(20, 3, 50, 2);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = ByteBuffer.allocate(1200);
        }
        int n = new SnapshotBuilder(w).build(v, 1, w.events.burstSeq(), false, parts);
        List<String> got = new ArrayList<>();
        SnapshotReader r = new SnapshotReader();
        r.setWorld(w.pixelWidth, w.pixelHeight, w.wrap);
        Records.TempWormhole t = new Records.TempWormhole();
        for (int p = 0; p < n; p++) {
            ByteBuffer b = parts[p].duplicate();
            b.flip();
            r.begin(b);
            while (r.nextSection()) {
                for (int i = 0; i < r.count(); i++) {
                    if (r.type() == Section.TEMP_WORMHOLE) {
                        t.wrap(r, i);
                        got.add(t.bx() + "," + t.by() + ":" + t.type());
                    }
                }
            }
        }
        assertEquals(List.of("20,3:1", "50,2:2"), got, "IN (type 1) and OUT (type 2), even out of view");
    }
}
