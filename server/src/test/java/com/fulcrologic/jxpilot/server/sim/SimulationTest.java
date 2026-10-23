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
        @DisplayName("numbers the tick, advances the frame clock by one frame; applies each slot's keys")
        void clocks() {
            Simulation s = sim("");
            Player p = TestWorlds.join(s, "a", 0);
            long f0 = s.world.frames;
            s.tick(keys(s, ControlKey.THRUST.mask()));
            int thrusting = p.status & Obj.THRUSTING;
            int t1 = s.world.tick;
            long f1 = s.world.frames;
            s.tick(41, keys(s, 0));
            assertAll(
                () -> assertEquals(1L, f0, "C frame_loops starts at 1"),
                () -> assertEquals(1, t1, "tick(input) runs tick world.tick + 1 and leaves world.tick at it"),
                () -> assertEquals(2L, f1, "the frame clock advances by one frame per tick"),
                () -> assertEquals(41, s.world.tick, "tick(T, input) runs tick T (the network layer numbers ticks)"),
                () -> assertEquals(3L, s.world.frames, "the frame clock still advances by one"),
                () -> assertTrue(thrusting != 0, "the slot's THRUST key took effect"),
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
        @DisplayName("stations gain (int)(players*0.06*256) per frame, capped at 500 units; none without players")
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
                () -> assertEquals(start + (int) (2 * 0.06 * 256), two, "two players: +(int)(30.72) = 30 per frame"),
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
        @DisplayName("at 30 fps: vel += acc + gravity in float, life -= 1")
        void wholeFrame() {
            Simulation s = TestWorlds.sim("framesPerSecond: 30\ngravity: -0.5\ngravityAngle: 45", ROWS);
            World w = s.world;
            int g = object(w, Obj.GRAVITY, 10);
            w.objects.accX[g] = 0.25f;
            w.objects.accY[g] = 0.125f;
            int n = object(w, 0, 10);
            w.objects.accX[n] = 0.25f;
            w.objects.accY[n] = 0.125f;
            float gx = (float) w.map.gravityX(8, 3);
            float gy = (float) w.map.gravityY(8, 3);
            s.tick(new long[w.players.length]);
            assertAll(
                () -> assertTrue(gx != 0 && gy != 0, "fixture: 45 degree gravity has both components"),
                () -> assertEquals(0f + (0.25f + gx), w.objects.vx[g], "gravity object vx += accX + gx (C floats)"),
                () -> assertEquals(0f + (0.125f + gy), w.objects.vy[g], "gravity object vy += accY + gy"),
                () -> assertEquals(0.25f, w.objects.vx[n], "other object vx += accX"),
                () -> assertEquals(0.125f, w.objects.vy[n], "other object vy += accY"),
                () -> assertEquals(9.0, w.objects.life[n], "life decreases by one frame"));
        }

        @Test
        @DisplayName("gravity is read at the object's own block (local gravity varies by block)")
        void gravityAtBlock() {
            Simulation s = TestWorlds.sim("framesPerSecond: 60\ngravity: 0",
                "xxxxxxxxxxxxxxxxxxxx",
                "x                  x",
                "x  _      _        x",
                "x   +              x",
                "x                  x",
                "xxxxxxxxxxxxxxxxxxxx");
            World w = s.world;
            Objects o = w.objects;
            int g = o.alloc();
            o.type[g] = Obj.DEBRIS;
            o.status[g] = Obj.GRAVITY;
            o.life[g] = 10;
            o.initPixels(g, 3 * 35 + 17, 1 * 35 + 17);
            float gx = w.gravityX(3, 1);
            float gy = w.gravityY(3, 1);
            s.tick(new long[w.players.length]);
            assertAll(
                () -> assertTrue(gy != w.gravityY(19, 5) && gx != w.gravityX(19, 5) && gy != w.gravityY(0, 0),
                    "fixture: gravity differs between blocks"),
                () -> assertEquals(gx, o.vx[g], "vx += gravity x of block (3, 1), diagonal to the + at (4, 2)"),
                () -> assertEquals(gy, o.vy[g], "vy += gravity y of block (3, 1), diagonal to the + at (4, 2)"));
        }

        @Test
        @DisplayName("gravity and acceleration act on GRAVITY objects, acceleration only on the others; life runs down")
        void gravityAndLife() {
            Simulation s = TestWorlds.sim("framesPerSecond: 60\ngravity: -0.5\ngravityAngle: 90", ROWS);
            World w = s.world;
            int g = object(w, Obj.GRAVITY, 10);
            int n = object(w, 0, 10);
            w.objects.accX[n] = 0.25f;
            int dying = object(w, 0, 1);
            s.tick(new long[w.players.length]);
            assertAll(
                () -> assertEquals(-0.5, w.objects.vy[g], 1e-12, "a GRAVITY object gains gravity"),
                () -> assertEquals(0.0, w.objects.vy[n], "a non-GRAVITY object ignores gravity"),
                () -> assertEquals(0.25, w.objects.vx[n], 1e-12, "acceleration applies to every object"),
                () -> assertEquals(9.0, w.objects.life[g], 1e-12, "life decreases by 1 frame"),
                () -> assertFalse(w.objects.isLive(dying), "an object whose life reaches 0 is deleted"),
                () -> assertEquals(2, w.objects.count(), "the other two live on"));
        }
    }

    @Nested
    @DisplayName("countDownWormholes")
    class Worms {
        @Test
        @DisplayName("wormhole countdowns decrease by one frame and stop at zero")
        void countdown() {
            Simulation s = TestWorlds.sim("framesPerSecond: 30", "xxxxxx", "x@ _@x", "xxxxxx");
            s.world.wormCountdown[0] = 2;
            s.world.wormCountdown[1] = 0;
            s.countDownWormholes();
            assertAll(
                () -> assertEquals(1, s.world.wormCountdown[0], "decreases by one frame"),
                () -> assertEquals(0, s.world.wormCountdown[1], "stays at zero"));
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
            s.scoring.score(b, 5, 0, 0, "");
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
            s.scoring.score(p, 3, 0, 0, "");
            s.scoring.score(p, 4, 0, 0, "");
            s.emitScores();
            List<Integer> k = kinds(s.world.events, from);
            int i = Events.index(s.world.events.seq() - 1);
            assertAll(
                () -> assertEquals(List.of(Events.SCORE_OBJECT, Events.SCORE_OBJECT, Events.SCORE, Events.TEAM_SCORE), k,
                    "a score object per change, then one SCORE then one TEAM_SCORE"),
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

    @Nested
    @DisplayName("C iteration orders (I3)")
    class Orders {
        @Test
        @DisplayName("expireObjects runs last to first: debris an expiring cannon shot makes this tick keeps its full life")
        void expireOrder() {
            Simulation s = sim("framesPerSecond: 60");
            World w = s.world;
            Objects o = w.objects;
            int h = o.alloc();
            o.type[h] = Obj.CANNON_SHOT;
            o.mass[h] = 0.4f;
            o.life[h] = 1;
            o.initPixels(h, 200, 100);
            int other = o.alloc();
            o.type[other] = Obj.SPARK;
            o.life[other] = 5;
            o.initPixels(other, 300, 100);
            s.expireObjects();
            boolean fresh = true;
            int debris = 0;
            for (int k = 0; k < o.count(); k++) {
                int d = o.active[k];
                if (o.type[d] == Obj.DEBRIS) {
                    debris++;
                    fresh &= o.life[d] == o.fuseLife[d];
                }
            }
            boolean allFresh = fresh;
            int n = debris;
            assertAll(
                () -> assertTrue(n > 0, "(the cannon shot exploded)"),
                () -> assertTrue(allFresh, "the new debris was not aged in the same pass (C counts down from the end)"),
                () -> assertEquals(4.0, o.life[other], "the older spark aged once"));
        }

        @Test
        @DisplayName("the kill loop runs last player first (explosion bursts in reverse join order)")
        void killOrder() {
            Simulation s = sim("framesPerSecond: 60");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            long b0 = w.events.burstSeq();
            a.status |= Obj.KILLED;
            b.status |= Obj.KILLED;
            s.killPlayers();
            assertEquals(List.of(b.id, a.id), List.of(w.events.burstOwner[Events.burstIndex(b0)],
                w.events.burstOwner[Events.burstIndex(b0 + 1)]), "b (last in order) explodes first");
        }

        @Test
        @DisplayName("ships update in join order: the first refueller empties a station before the second")
        void updateOrder() {
            Simulation s = sim("framesPerSecond: 60");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            for (Player p : List.of(a, b)) {
                p.used &= ~Has.SHIELD;
                p.initPixels(w.map.fuels().get(0).x() + (p == a ? -30 : 30), w.map.fuels().get(0).y() + 30);
                p.used |= Has.REFUEL;
                p.fs = 0;
            }
            w.fuelLevel[0] = 1280;
            double fa = a.fuel;
            double fb = b.fuel;
            s.tick(keys(s, 0));
            assertAll(
                () -> assertEquals(1280.0, a.fuel - fa, "a (first) gets a full REFUEL_RATE"),
                () -> assertEquals(30.0, b.fuel - fb, "b gets what is left: two players' regeneration (2*15)"));
        }

        @Test
        @DisplayName("objects move in pool order: the first of two shots into a cannon destroys it and gets the points")
        void moveOrder() {
            Simulation s = TestWorlds.sim("framesPerSecond: 60\ngravity: 0", "xxxxxxxxxx", "x        x",
                "x _  r _ x", "x        x", "xxxxxxxxxx");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            Objects o = w.objects;
            int[] shots = new int[2];
            for (int i = 0; i < 2; i++) {
                int h = o.alloc();
                o.type[h] = Obj.SHOT;
                o.owner[h] = i == 0 ? b.id : a.id;
                o.life[h] = 10;
                o.mass[h] = 0.1f;
                o.initPixels(h, 5 * 35 - 4, 2 * 35 + 2);
                o.vx[h] = 30;
                shots[i] = h;
            }
            s.moveObjects();
            assertAll(
                () -> assertTrue(w.cannonDeadTime[0] > 0, "the cannon died"),
                () -> assertEquals(1.0, b.score, 1e-6, "b's shot (first in the pool) gets cannonPoints"),
                () -> assertEquals(0.0, a.score, "a's shot came second"));
        }
    }
}
