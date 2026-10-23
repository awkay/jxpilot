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

import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Simulation")
class SimulationTest {
    static final String[] ROWS = {
        "xxxxxxxxxxxxxxxxxxxx",
        "x                  x",
        "x  _      _        x",
        "x                  x",
        "x           #      x",
        "xxxxxxxxxxxxxxxxxxxx",
    };

    static Simulation sim(String extra) {
        return TestWorlds.sim("gravity: 0\n" + extra, ROWS);
    }

    static long[] keys(Simulation s, long mask) {
        long[] in = new long[s.world.players.length];
        java.util.Arrays.fill(in, mask);
        return in;
    }

    static List<Integer> kinds(Events e, long from) {
        List<Integer> out = new ArrayList<>();
        for (long q = from; q < e.seq(); q++) {
            out.add(e.kind[Events.index(q)]);
        }
        return out;
    }

    @Nested
    @DisplayName("tick: clocks and input")
    class Clocks {
        @Test
        @DisplayName("advances the tick by one and the frame clock by dt = fps/60; applies each slot's keys")
        void clocks() {
            Simulation s = sim("");
            Player p = TestWorlds.join(s, "a", 0);
            s.tick(keys(s, ControlKey.THRUST.mask()));
            assertAll(
                () -> assertEquals(1, s.world.tick, "tick advances by one"),
                () -> assertEquals(14.0 / 60, s.world.frames, 1e-12, "the frame clock advances by 14/60 at the default 14 fps"),
                () -> assertTrue((p.status & Obj.THRUSTING) != 0, "the slot's THRUST key took effect"),
                () -> assertTrue(p.vx != 0 || p.vy != 0, "thrust accelerated the ship in the same tick"));
        }
    }

    @Nested
    @DisplayName("autofire")
    class Autofire {
        /** Shots fired, counted by fuel: each costs (long)(-0.2*256) = 51 (shot.c Fire_general_shot). */
        int shotsAfter(String extra, int ticks) {
            Simulation s = sim("framesPerSecond: 60\n" + extra);
            Player p = TestWorlds.join(s, "a", 0);
            p.used &= ~Has.SHIELD;
            TestWorlds.run(s, 2, 0L);
            double fuel = p.fuel;
            TestWorlds.run(s, ticks, ControlKey.FIRE_SHOT.mask());
            return (int) Math.round((fuel - p.fuel) / 51);
        }

        @Test
        @DisplayName("only players holding fire autofire")
        void onlyHolders() {
            Simulation s = sim("framesPerSecond: 60\nfireRepeatRate: 2");
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            a.used &= ~Has.SHIELD;
            b.used &= ~Has.SHIELD;
            TestWorlds.run(s, 2, 0L);
            double fuelB = b.fuel;
            long[] in = new long[s.world.players.length];
            in[a.slot] = ControlKey.FIRE_SHOT.mask();
            TestWorlds.run(s, 6, in);
            assertEquals(fuelB, b.fuel, "a player not holding fire spends no fuel on shots");
        }

        @Test
        @DisplayName("holding fire repeats every fireRepeatRate frames; with rate 0 only the press fires")
        void repeat() {
            assertAll(
                () -> assertEquals(5, shotsAfter("fireRepeatRate: 2\nshotLife: 100", 9),
                    "rate 2 at 60 fps: shots at frames 2, 4, 6, 8, 10 of the 9 held ticks"),
                () -> assertEquals(1, shotsAfter("fireRepeatRate: 0\nshotLife: 100", 9), "rate 0: one shot per press"));
        }
    }

    @Nested
    @DisplayName("regenerateFuel")
    class Regen {
        @Test
        @DisplayName("stations gain (int)(players*0.06*256) per frame times dt, capped at 500 units; none without players")
        void regen() {
            Simulation s = sim("");
            World w = s.world;
            double start = w.fuelLevel[0];
            s.tick(keys(s, 0));
            double noPlayers = w.fuelLevel[0];
            TestWorlds.join(s, "a", 0);
            TestWorlds.join(s, "b", 0);
            s.regenerateFuel();
            double two = w.fuelLevel[0];
            w.fuelLevel[0] = 500 * 256 - 1;
            s.regenerateFuel();
            assertAll(
                () -> assertEquals(start, noPlayers, "no regeneration without players"),
                () -> assertEquals(start + (int) (2 * 0.06 * 256) * (14.0 / 60), two, 1e-9,
                    "two players: +(int)(30.72) = 30 per frame, times 14/60"),
                () -> assertEquals(500 * 256, w.fuelLevel[0], "capped at MAX_STATION_FUEL"));
        }
    }

    @Nested
    @DisplayName("moveObjects / expireObjects")
    class ObjectsPhase {
        int object(World w, int status, double life) {
            Objects o = w.objects;
            int h = o.alloc();
            o.type[h] = Obj.DEBRIS;
            o.status[h] = status;
            o.life[h] = life;
            o.initPixels(h, 300, 100);
            return h;
        }

        @Test
        @DisplayName("at dt = 0.5: vel += (acc + gravity) * dt, life -= dt")
        void scaled() {
            Simulation s = TestWorlds.sim("framesPerSecond: 30\ngravity: -0.5\ngravityAngle: 45", ROWS);
            World w = s.world;
            int g = object(w, Obj.GRAVITY, 10);
            w.objects.accX[g] = 0.25;
            w.objects.accY[g] = 0.125;
            int n = object(w, 0, 10);
            w.objects.accX[n] = 0.25;
            w.objects.accY[n] = 0.125;
            double gx = w.map.gravityX(8, 3);
            double gy = w.map.gravityY(8, 3);
            s.tick(new long[w.players.length]);
            assertAll(
                () -> assertTrue(gx != 0 && gy != 0, "fixture: 45 degree gravity has both components"),
                () -> assertEquals((0.25 + gx) * 0.5, w.objects.vx[g], 1e-12, "gravity object vx = (accX + gx) * dt"),
                () -> assertEquals((0.125 + gy) * 0.5, w.objects.vy[g], 1e-12, "gravity object vy = (accY + gy) * dt"),
                () -> assertEquals(0.125, w.objects.vx[n], 1e-12, "other object vx = accX * dt"),
                () -> assertEquals(0.0625, w.objects.vy[n], 1e-12, "other object vy = accY * dt"),
                () -> assertEquals(9.5, w.objects.life[n], 1e-12, "life decreases by dt = 0.5"));
        }

        @Test
        @DisplayName("gravity and acceleration act on GRAVITY objects, acceleration only on the others; life runs down by dt")
        void gravityAndLife() {
            Simulation s = TestWorlds.sim("framesPerSecond: 60\ngravity: -0.5\ngravityAngle: 90", ROWS);
            World w = s.world;
            int g = object(w, Obj.GRAVITY, 10);
            int n = object(w, 0, 10);
            w.objects.accX[n] = 0.25;
            int dying = object(w, 0, 1);
            s.tick(new long[w.players.length]);
            assertAll(
                () -> assertEquals(-0.5, w.objects.vy[g], 1e-12, "a GRAVITY object gains gravity * dt"),
                () -> assertEquals(0.0, w.objects.vy[n], "a non-GRAVITY object ignores gravity"),
                () -> assertEquals(0.25, w.objects.vx[n], 1e-12, "acceleration applies to every object"),
                () -> assertEquals(9.0, w.objects.life[g], 1e-12, "life decreases by dt = 1 frame"),
                () -> assertFalse(w.objects.isLive(dying), "an object whose life reaches 0 is deleted"),
                () -> assertEquals(2, w.objects.count(), "the other two live on"));
        }
    }

    @Nested
    @DisplayName("countDownWormholes")
    class Worms {
        @Test
        @DisplayName("wormhole countdowns decrease by dt and stop at zero")
        void countdown() {
            Simulation s = TestWorlds.sim("framesPerSecond: 30", "xxxxxx", "x@ _@x", "xxxxxx");
            s.world.wormCountdown[0] = 2;
            s.world.wormCountdown[1] = 0.25;
            s.countDownWormholes();
            assertAll(
                () -> assertEquals(1.5, s.world.wormCountdown[0], 1e-12, "decreases by dt = 0.5"),
                () -> assertEquals(0.0, s.world.wormCountdown[1], "stops at zero"));
        }
    }

    @Nested
    @DisplayName("kill phase and score events")
    class KillAndScores {
        @Test
        @DisplayName("a ship marked KILLED explodes and is reset in the same tick; one SCORE event per changed player")
        void kill() {
            Simulation s = sim("framesPerSecond: 60");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            s.tick(keys(s, 0));
            long from = w.events.seq();
            long bursts = w.events.burstSeq();
            a.status |= Obj.KILLED;
            s.scoring.score(b, 5);
            s.tick(keys(s, 0));
            List<Integer> k = kinds(w.events, from);
            long scoreEvents = k.stream().filter(x -> x == Events.SCORE).count();
            assertAll(
                () -> assertEquals(0, a.status & (Obj.KILLED | Obj.PLAYING), "the killed ship is reset: not KILLED, not PLAYING"),
                () -> assertEquals(3 * 60 , a.count, 1e-9, "it waits 3*FPS frames to respawn"),
                () -> assertTrue(w.events.burstSeq() > bursts, "it exploded (a debris burst)"),
                () -> assertEquals(2, scoreEvents, "one SCORE event each for the dead ship and the scorer"),
                () -> assertFalse(a.scoreDirty || b.scoreDirty, "dirty flags are cleared"));
            int last = -1;
            for (long q = from; q < w.events.seq(); q++) {
                int i = Events.index(q);
                if (w.events.kind[i] == Events.SCORE && w.events.a[i] == b.id) {
                    last = i;
                }
            }
            int bi = last;
            assertAll(
                () -> assertEquals(5f, w.events.value[bi], "the SCORE event carries the score"),
                () -> assertEquals(-1, w.events.b[bi], "unlimited lives are sent as -1"),
                () -> assertEquals(0, w.events.c[bi], "a playing ship has no status bits"));
        }

        @Test
        @DisplayName("team score changes emit TEAM_SCORE once")
        void teamScore() {
            Simulation s = TestWorlds.sim("teamPlay: yes\ngravity: 0", "xxxxxxx", "x 1 2 x", "xxxxxxx");
            Player p = TestWorlds.join(s, "a", 1);
            s.tick(new long[s.world.players.length]);
            long from = s.world.events.seq();
            s.scoring.score(p, 3);
            s.scoring.score(p, 4);
            s.emitScores();
            List<Integer> k = kinds(s.world.events, from);
            int i = Events.index(s.world.events.seq() - 1);
            assertAll(
                () -> assertEquals(List.of(Events.SCORE, Events.TEAM_SCORE), k, "one SCORE then one TEAM_SCORE"),
                () -> assertEquals(1, s.world.events.a[i], "for team 1"),
                () -> assertEquals(7f, s.world.events.value[i], "with the team total"));
        }

        @Test
        @DisplayName("scoreStatus: dead, paused and out-of-lives bits")
        void status() {
            Player p = new Player(0);
            p.status = Obj.PLAYING;
            int alive = Simulation.scoreStatus(p);
            p.status = Obj.PAUSE | Obj.GAME_OVER;
            assertAll(
                () -> assertEquals(0, alive, "a playing ship has no bits"),
                () -> assertEquals(Msg.Score.STATUS_DEAD | Msg.Score.STATUS_PAUSED | Msg.Score.STATUS_OUT_OF_LIVES,
                    Simulation.scoreStatus(p), "not playing, paused and game over"));
        }
    }

    @Nested
    @DisplayName("tick runs every phase")
    class Phases {
        @Test
        @DisplayName("stations regenerate, wormholes count down, cannons fire and collisions kill within ticks")
        void phases() {
            Simulation s = TestWorlds.sim("framesPerSecond: 60\ngravity: 0\ncannonSmartness: 0\nshotLife: 200",
                "xxxxxxxxxxxx",
                "x          x",
                "x  _       x",
                "x          x",
                "x  r  #  @@x",
                "xxxxxxxxxxxx");
            World w = s.world;
            Player p = TestWorlds.join(s, "a", 0);
            p.used &= ~Has.SHIELD;
            w.fuelLevel[0] = 100;
            w.wormCountdown[0] = 5;
            s.tick(new long[w.players.length]);
            double regen = w.fuelLevel[0];
            double worm = w.wormCountdown[0];
            int deaths = p.deaths;
            for (int i = 0; i < 400 && p.deaths == deaths; i++) {
                s.tick(new long[w.players.length]);
            }
            assertAll(
                () -> assertEquals(100 + (int) (0.06 * 256), regen, 1e-9, "the tick regenerated the station"),
                () -> assertEquals(4.0, worm, 1e-9, "the tick counted the wormhole down"),
                () -> assertEquals(deaths + 1, p.deaths, "the cannon below the base shot the unshielded ship within 400 ticks"));
        }
    }
}
