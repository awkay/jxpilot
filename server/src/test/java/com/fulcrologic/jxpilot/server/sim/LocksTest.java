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

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks and visibility: event.c (Player_lock_allowed, Player_lock_closest, team_dead, the lock keys),
 * player.c (Compute_sensor_range, Go_home), update.c:938-956 and frame.c Frame_status (LOCK_VISIBLE).
 */
@DisplayName("Locks (event.c locks, player.c sensors, update.c visibility, frame.c LOCK_VISIBLE)")
class LocksTest {
    static final int SENSOR = Item.SENSOR.ordinal();
    static final int CLOAK = Item.CLOAK.ordinal();

    Simulation s;
    World w;
    Locks l;
    Player p;

    /** 60x60 open blocks (2100 px, hypotenuse 2969); P at (1000, 1000). */
    void setup(String extra, String bases) {
        s = SimFixture.sim(FRAME + extra, 60, 60, bases);
        w = s.world;
        l = s.locks;
        p = ready(s, "P", team(bases, 0), 1000, 1000);
    }

    void setup(String extra) {
        setup(extra, "_____");
    }

    static int team(String bases, int k) {
        char c = bases.charAt(k);
        return c == '_' ? 0 : c - '0';
    }

    Player add(String name, int team, double dx, double dy) {
        return ready(s, name, team, 1000 + dx, 1000 + dy);
    }

    boolean locked(Player on) {
        return (p.lockTagged & Player.LOCK_PLAYER) != 0 && p.lockPlId == on.id;
    }

    @Nested
    @DisplayName("sensorRange (Compute_sensor_range)")
    class SensorRange {
        float range(Locks locks, double fuel, int sensors) {
            p.fuel = fuel;
            p.items[SENSOR] = sensors;
            locks.sensorRange(p);
            return p.sensorRange;
        }

        @Test
        @DisplayName("defaults: fuel * minVis / (initialFuel * 256), +25% per sensor, within [VISIBILITY_DISTANCE, hypotenuse]")
        void defaults() {
            setup("");
            assertAll(
                () -> assertEquals(2969.0, w.params.hypotenuse, 1e-9, "the world diagonal is 2969"),
                () -> assertEquals(1000f, range(l, 1000 * 256, 0), "the starting fuel reaches exactly 1000"),
                () -> assertEquals(1500f, range(l, 1500 * 256, 0), "1500 fuel: 1500"),
                () -> assertEquals(1875f, range(l, 1500 * 256, 1), "1500 fuel, 1 sensor: 1500 * 1.25"),
                () -> assertEquals(1000f, range(l, 500 * 256, 0), "500 fuel: raised to the minimum 1000"),
                () -> assertEquals(2969f, range(l, 2000 * 256, 2), "2000 fuel, 2 sensors (3000): cut to the diagonal"));
        }

        @Test
        @DisplayName("min/maxVisibilityDistance are in blocks; the factor follows the minimum")
        void options() {
            setup("minVisibilityDistance: 10\nmaxVisibilityDistance: 40\n");
            assertAll(
                () -> assertEquals(350f, range(l, 1000 * 256, 0), "starting fuel: exactly 10 blocks"),
                () -> assertEquals(1050f, range(l, 3000 * 256, 0), "3000 fuel: 3 * 350"),
                () -> assertEquals(350f, range(l, 100 * 256, 0), "little fuel: the 350 minimum"),
                () -> assertEquals(1400f, range(l, 9000 * 256, 0), "much fuel: the 40-block maximum"));
        }

        @Test
        @DisplayName("initial sensors are part of the factor; with no initial fuel ENERGY_RANGE_FACTOR (2.5 / 256) is used")
        void factor() {
            setup("initialSensors: 2\n");
            float withSensors = range(l, 1000 * 256, 2);
            float moreFuel = range(l, 2000 * 256, 2);
            setup("initialFuel: 0\n");
            Locks noFuel = l; // C initialises once per process: the simulation's own Locks
            assertAll(
                () -> assertEquals(1000f, withSensors, 1e-3f, "initial fuel and 2 sensors reach 1000 (float rounding)"),
                () -> assertEquals(2000f, moreFuel, 2e-3f, "twice the fuel: 2000"),
                () -> assertEquals(2500f, range(noFuel, 1000 * 256, 0), "no initial fuel: 1000 * 256 * 2.5 / 256"));
        }
    }

    @Nested
    @DisplayName("updateVisibility (update.c:938-956)")
    class Visibility {
        Player o;

        void two() {
            setup("");
            o = add("O", 0, 100, 0);
            p.updateVisibility = false;
            o.updateVisibility = false;
        }

        @Test
        @DisplayName("everyone is seen without a cloak; a forced-visible player is seen by all")
        void uncloaked() {
            two();
            p.canSee[o.ind] = false;
            p.canSee[p.ind] = false;
            o.canSee[p.ind] = false;
            int before = w.rng.state();
            l.updateVisibility(p);
            boolean oSeesP = o.canSee[p.ind];
            p.forceVisible = 3;
            p.used |= Has.CLOAKING_DEVICE;
            l.updateVisibility(p);
            assertAll(
                () -> assertTrue(p.canSee[o.ind], "P sees the uncloaked O"),
                () -> assertTrue(p.canSee[p.ind], "P sees itself"),
                () -> assertFalse(oSeesP, "O's view of P is not P's update"),
                () -> assertTrue(o.canSee[p.ind], "a forced-visible P is seen by O"),
                () -> assertEquals(before, w.rng.state(), "nothing is drawn for uncloaked players"));
        }

        @Test
        @DisplayName("a cloaked player is re-decided on request: seen iff rfrac() * (sensors + 1) > rfrac() * (cloaks + 1)")
        void requested() {
            two();
            o.used |= Has.CLOAKING_DEVICE;
            o.items[CLOAK] = 2;
            p.items[SENSOR] = 1;
            boolean[] got = new boolean[20];
            boolean[] want = new boolean[20];
            for (int sd = 0; sd < 20; sd++) {
                p.updateVisibility = (sd & 1) == 0;
                o.updateVisibility = !p.updateVisibility;
                w.rng.seed(sd);
                l.updateVisibility(p);
                got[sd] = p.canSee[o.ind];
                Rng r = new Rng(sd);
                want[sd] = (r.nextFloat() * 2.0) > (r.nextFloat() * 3.0);
            }
            boolean any = false;
            boolean none = true;
            for (boolean b : want) {
                any |= b;
                none &= b;
            }
            boolean anyTrue = any;
            boolean allTrue = none;
            assertAll(
                () -> assertEquals(java.util.Arrays.toString(want), java.util.Arrays.toString(got),
                    "each decision follows the two rolls (P's flag or O's flag)"),
                () -> assertTrue(anyTrue && !allTrue, "both outcomes occur over the seeds"),
                () -> assertEquals(w.frames, p.visLastChange[o.ind], "the change time is now"));
        }

        @Test
        @DisplayName("without a request it re-decides iff (int)(rfrac() * 100) < |frames - lastChange|")
        void chance() {
            two();
            o.used |= Has.CLOAKING_DEVICE;
            w.frames = 51;
            p.visLastChange[o.ind] = 1;                      // 50 frames since
            int redo = -1;
            int keep = -1;
            for (int sd = 0; redo < 0 || keep < 0; sd++) {
                int roll = (int) (new Rng(sd).nextFloat() * 100);
                if (roll == 49 && redo < 0) {
                    redo = sd;
                } else if (roll == 50 && keep < 0) {
                    keep = sd;
                }
            }
            int redoSeed = redo;
            int keepSeed = keep;
            p.canSee[o.ind] = true;
            w.rng.seed(keepSeed);
            l.updateVisibility(p);
            long keptChange = p.visLastChange[o.ind];
            Rng afterKeep = new Rng(keepSeed);
            afterKeep.nextFloat();
            int keptState = w.rng.state();
            w.rng.seed(redoSeed);
            l.updateVisibility(p);
            assertAll(
                () -> assertEquals(1, keptChange, "roll 50 (not < 50): no change, the time stays"),
                () -> assertEquals(afterKeep.state(), keptState, "only the chance roll was drawn"),
                () -> assertEquals(51, p.visLastChange[o.ind], "roll 49 < 50: re-decided now"));
        }

        @Test
        @DisplayName("the elapsed time is absolute (|frames - lastChange|)")
        void absolute() {
            two();
            o.used |= Has.CLOAKING_DEVICE;
            w.frames = 1;
            p.visLastChange[o.ind] = 51;                     // a change "in the future": still 50
            int sd = 0;
            while ((int) (new Rng(sd).nextFloat() * 100) != 49) {
                sd++;
            }
            w.rng.seed(sd);
            l.updateVisibility(p);
            assertEquals(1, p.visLastChange[o.ind], "roll 49 < |1 - 51|: re-decided");
        }

        @Test
        @DisplayName("players who left the game are not looked at")
        void departed() {
            two();
            Player g = add("G", 0, 50, 0);
            int gi = g.ind;
            w.removePlayer(g);
            p.canSee[gi] = false;
            p.visLastChange[gi] = 5;
            l.updateVisibility(p);
            boolean seen = p.canSee[gi];
            l.resetVisibility(p);
            assertAll(
                () -> assertEquals(-1, g.ind, "G has no player index any more"),
                () -> assertFalse(seen, "the departed G's old index is not marked seen"),
                () -> assertEquals(5, p.visLastChange[gi], "nor its change time reset"));
        }

        @Test
        @DisplayName("resetVisibility asks for an update and zeroes the change times to and from the player")
        void reset() {
            two();
            p.visLastChange[o.ind] = 5;
            o.visLastChange[p.ind] = 6;
            o.visLastChange[o.ind] = 7;
            l.resetVisibility(p);
            assertAll(
                () -> assertTrue(p.updateVisibility, "an update is requested"),
                () -> assertEquals(0, p.visLastChange[o.ind], "P's time for O is 0"),
                () -> assertEquals(0, o.visLastChange[p.ind], "O's time for P is 0"),
                () -> assertEquals(7, o.visLastChange[o.ind], "O's other times stay"));
        }

        @Test
        @DisplayName("sees: canSee, oneself, team mates and own tanks; ownsTank needs a tank locked on the owner")
        void sees() {
            setup("teamPlay: yes\n", "1122");
            Player mate = add("M", 1, 10, 0);
            Player foe = add("F", 2, 20, 0);
            Player tank = add("T", 2, 30, 0);
            tank.tank = true;
            tank.lockPlId = p.id;
            p.canSee[mate.ind] = false;
            p.canSee[foe.ind] = false;
            p.canSee[tank.ind] = false;
            p.canSee[p.ind] = false;
            boolean foeUnseen = l.sees(p, foe);
            p.canSee[foe.ind] = true;
            assertAll(
                () -> assertTrue(l.sees(p, mate), "a team mate"),
                () -> assertFalse(foeUnseen, "an unseen foe is not"),
                () -> assertTrue(l.sees(p, foe), "a seen foe is"),
                () -> assertTrue(l.sees(p, p), "oneself"),
                () -> assertTrue(l.sees(p, tank), "an own tank"),
                () -> assertFalse(l.ownsTank(foe, tank), "another's tank is not owned"),
                () -> assertFalse(l.ownsTank(p, mate), "a non-tank is no tank"));
        }

        @Test
        @DisplayName("ownsTank: a tank with lock -1 is nobody's")
        void ownsTankNoLock() {
            setup("");
            Player t = add("T", 0, 10, 0);
            t.tank = true;
            t.lockPlId = -1;
            assertFalse(l.ownsTank(p, t), "lock -1: not owned");
        }
    }

    @Nested
    @DisplayName("teamDead / lockAllowed (team_dead, Player_lock_allowed)")
    class Allowed {
        @Test
        @DisplayName("teamDead iff nobody of the team is playing without game over")
        void teamDead() {
            setup("teamPlay: yes\n", "1122");
            Player foe = add("F", 2, 10, 0);
            boolean alive = l.teamDead(2);
            foe.status |= Obj.GAME_OVER;
            boolean over = l.teamDead(2);
            foe.status = 0;
            assertAll(
                () -> assertFalse(alive, "a playing member: alive"),
                () -> assertTrue(over, "the only member game over: dead"),
                () -> assertTrue(l.teamDead(2), "the only member not playing: dead"),
                () -> assertTrue(l.teamDead(3), "an empty team: dead"));
        }

        @Test
        @DisplayName("teamDead ignores players who left the game")
        void teamDeadDeparted() {
            setup("teamPlay: yes\n", "1122");
            Player g = add("G", 2, 10, 0);
            w.removePlayer(g);
            assertTrue(l.teamDead(2), "the departed G does not keep team 2 alive");
        }

        @Test
        @DisplayName("never oneself or nobody; anyone while actively playing (event.c:139-141)")
        void playing() {
            setup("teamPlay: yes\nlockOtherTeam: no\n", "1122");
            Player foe = add("F", 2, 10, 0);
            assertAll(
                () -> assertFalse(l.lockAllowed(p, p), "not oneself"),
                () -> assertFalse(l.lockAllowed(p, null), "not nobody"),
                () -> assertTrue(l.lockAllowed(p, foe), "a playing player may lock on another team"));
        }

        @Test
        @DisplayName("not actively playing in team play: mates, others with lockOtherTeam or when the own team is dead")
        void waiting() {
            setup("teamPlay: yes\nlockOtherTeam: no\n", "1122");
            Player mate = add("M", 1, 10, 0);
            Player foe = add("F", 2, 20, 0);
            p.status = Obj.PAUSE;                             // Pause_player clears PLAYING
            boolean mateOk = l.lockAllowed(p, mate);
            boolean foeNo = l.lockAllowed(p, foe);
            w.items.lockOtherTeam = true;
            boolean foeOther = l.lockAllowed(p, foe);
            w.items.lockOtherTeam = false;
            mate.status |= Obj.GAME_OVER;
            boolean foeDead = l.lockAllowed(p, foe);
            p.status = Obj.PLAYING | Obj.GAME_OVER;
            boolean gameOver = l.lockAllowed(p, foe);
            assertAll(
                () -> assertTrue(mateOk, "paused: a team mate"),
                () -> assertFalse(foeNo, "paused: not another team"),
                () -> assertTrue(foeOther, "paused, lockOtherTeam: another team"),
                () -> assertTrue(foeDead, "paused, own team dead (the mate is game over, P paused): another team"),
                () -> assertTrue(gameOver, "game over and the own team dead: another team"));
        }

        @Test
        @DisplayName("not actively playing outside team play: anyone")
        void waitingNoTeams() {
            setup("");
            Player q = add("Q", 0, 10, 0);
            p.status = 0;
            assertTrue(l.lockAllowed(p, q), "anyone");
        }
    }

    @Nested
    @DisplayName("lockClosest (Player_lock_closest)")
    class Closest {
        @Test
        @DisplayName("locks the closest playing player; ties go to the first in order")
        void closest() {
            setup("");
            Player far = add("F", 0, 300, 0);
            Player tieA = add("A", 0, 0, 200);
            Player tieB = add("B", 0, -200, 0);
            boolean ok = l.lockClosest(p, false);
            assertAll(
                () -> assertTrue(ok, "returns true"),
                () -> assertTrue(locked(tieA), "the first of the two at 200"),
                () -> assertFalse(locked(tieB), "not the second"),
                () -> assertFalse(locked(far), "not the farther one"));
        }

        @Test
        @DisplayName("skips paused, game over, not playing players, own tanks and team mates")
        void skips() {
            setup("teamPlay: yes\n", "1122222");
            Player paused = add("P1", 2, 10, 0);
            paused.status |= Obj.PAUSE;
            Player over = add("P2", 2, 20, 0);
            over.status |= Obj.GAME_OVER;
            Player waiting = add("P3", 2, 30, 0);
            waiting.status = 0;
            Player mate = add("M", 1, 40, 0);
            Player tank = add("T", 2, 50, 0);
            tank.tank = true;
            tank.lockPlId = p.id;
            Player foe = add("F", 2, 60, 0);
            l.lockClosest(p, false);
            assertTrue(locked(foe), "the closest eligible player is F at 60");
        }

        @Test
        @DisplayName("with nobody eligible it returns false and the lock is cleared")
        void nobody() {
            setup("");
            Player q = add("Q", 0, 10, 0);
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = q.id;
            q.status |= Obj.PAUSE;
            boolean ok = l.lockClosest(p, false);
            assertAll(
                () -> assertFalse(ok, "returns false"),
                () -> assertEquals(0, p.lockTagged & Player.LOCK_PLAYER, "the lock is cleared"));
        }

        @Test
        @DisplayName("players who left the game are not locked")
        void departed() {
            setup("");
            Player g = add("G", 0, 10, 0);
            w.removePlayer(g);
            assertAll(
                () -> assertFalse(l.lockClosest(p, false), "nobody to lock"),
                () -> assertEquals(-1, l.order(g), "the departed G has no order position"));
        }

        @Test
        @DisplayName("next: the closest not closer than the current lock (equal distance counts), not the lock itself")
        void next() {
            setup("");
            Player a = add("A", 0, 100, 0);
            Player b = add("B", 0, 0, 100);
            Player c = add("C", 0, 300, 0);
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = a.id;
            boolean ok = l.lockClosest(p, true);
            boolean onB = locked(b);
            boolean again = l.lockClosest(p, true);
            boolean onA = locked(a);
            assertAll(
                () -> assertTrue(ok && onB, "from A (100): B at the same 100"),
                () -> assertTrue(again, "from B: found again"),
                () -> assertTrue(onA, "A at 100 again (equal distance, not the lock)"),
                () -> assertFalse(locked(c), "not the farther C"));
        }

        @Test
        @DisplayName("next from the farthest finds nobody and keeps the lock; a departed lock counts as distance 0")
        void nextNone() {
            setup("");
            Player a = add("A", 0, 100, 0);
            Player c = add("C", 0, 300, 0);
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = c.id;
            boolean ok = l.lockClosest(p, true);
            boolean keeps = locked(c);
            p.lockPlId = 200;
            l.lockClosest(p, true);
            assertAll(
                () -> assertFalse(ok, "returns false"),
                () -> assertTrue(keeps, "C stays locked"),
                () -> assertTrue(locked(a), "from a departed lock: the closest"));
        }

        @Test
        @DisplayName("lockNextClose: the next farther if any, else the closest")
        void nextClose() {
            setup("");
            Player a = add("A", 0, 100, 0);
            Player c = add("C", 0, 300, 0);
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = a.id;
            l.lockNextClose(p);
            boolean onC = locked(c);
            l.lockNextClose(p);
            assertAll(
                () -> assertTrue(onC, "from A: C"),
                () -> assertTrue(locked(a), "from C (farthest): back to the closest A"));
        }
    }

    @Nested
    @DisplayName("lockNext (KEY_LOCK_NEXT / KEY_LOCK_PREV)")
    class Next {
        Player a;
        Player b;
        Player c;

        /** Order P, A, B, C; A is the closest. */
        void four(String extra, String bases) {
            setup(extra, bases);
            a = add("A", team(bases, 1), 100, 0);
            b = add("B", team(bases, 2), 300, 0);
            c = add("C", team(bases, 3), 200, 0);
        }

        void lockOn(Player t) {
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = t.id;
        }

        @Test
        @DisplayName("steps through the player order, wrapping past oneself")
        void order() {
            four("", "_____");
            lockOn(a);
            l.lockNext(p, false);
            boolean b1 = locked(b);
            l.lockNext(p, false);
            boolean c1 = locked(c);
            l.lockNext(p, false);
            boolean a1 = locked(a);
            l.lockNext(p, true);
            boolean prevC = locked(c);
            l.lockNext(p, true);
            assertAll(
                () -> assertTrue(b1, "A -> B"),
                () -> assertTrue(c1, "B -> C"),
                () -> assertTrue(a1, "C -> A (past P)"),
                () -> assertTrue(prevC, "prev from A -> C (past P)"),
                () -> assertTrue(locked(b), "prev from C -> B"));
        }

        @Test
        @DisplayName("without a lock (or with a departed one) it locks on the closest")
        void noLock() {
            four("", "_____");
            p.lockPlId = b.id;                             // an id without LOCK_PLAYER is no lock
            l.lockNext(p, false);
            boolean closest = locked(a);
            lockOn(b);
            p.lockPlId = 200;
            l.lockNext(p, true);
            assertAll(
                () -> assertTrue(closest, "no lock: the closest A"),
                () -> assertTrue(locked(a), "departed lock: the closest A"));
        }

        /** Order X (index 0), P, Y, Z (the closest), and a departed G behind them. */
        Player x;
        Player y;
        Player z;

        void ring() {
            s = SimFixture.sim(FRAME, 60, 60, "_____");
            w = s.world;
            l = s.locks;
            x = ready(s, "X", 0, 1400, 1000);
            p = ready(s, "P", 0, 1000, 1000);
            y = ready(s, "Y", 0, 1300, 1000);
            z = ready(s, "Z", 0, 1100, 1000);
            Player g = ready(s, "G", 0, 1050, 1000);
            w.removePlayer(g);
        }

        @Test
        @DisplayName("stepping onto and from the player at index 0, and past the end of the order")
        void indexZero() {
            ring();
            lockOn(x);
            l.lockNext(p, false);
            boolean fromX = locked(y);
            lockOn(y);
            l.lockNext(p, true);
            boolean ontoX = locked(x);
            lockOn(x);
            l.lockNext(p, true);
            boolean prevFromX = locked(z);
            lockOn(z);
            l.lockNext(p, false);
            assertAll(
                () -> assertTrue(fromX, "next from X (index 0): Y (P skipped)"),
                () -> assertTrue(ontoX, "prev from Y: X at index 0 (P skipped)"),
                () -> assertTrue(prevFromX, "prev from X: wraps to the last, Z"),
                () -> assertTrue(locked(x), "next from Z (last): wraps to X, not the departed G"));
        }

        @Test
        @DisplayName("skips game over and paused players")
        void skips() {
            four("", "_____");
            b.status |= Obj.GAME_OVER;
            c.status |= Obj.PAUSE;
            lockOn(a);
            l.lockNext(p, false);
            assertTrue(locked(a), "from A past B, C and P back to A");
        }

        @Test
        @DisplayName("skips players the lock is not allowed on (paused in team play)")
        void notAllowed() {
            four("teamPlay: yes\nlockOtherTeam: no\n", "11222");
            p.status = Obj.PAUSE;
            lockOn(a);
            l.lockNext(p, false);
            assertTrue(locked(a), "only the mate A is allowed: it stays");
        }

        @Test
        @DisplayName("locked on oneself: the next allowed player, or the lock is cleared when there is none")
        void self() {
            four("", "_____");
            lockOn(p);
            l.lockNext(p, false);
            boolean onA = locked(a);
            lockOn(p);
            a.status |= Obj.PAUSE;
            b.status |= Obj.PAUSE;
            c.status |= Obj.PAUSE;
            l.lockNext(p, false);
            assertAll(
                () -> assertTrue(onA, "from oneself: A"),
                () -> assertEquals(0, p.lockTagged & Player.LOCK_PLAYER, "nobody allowed: back at oneself, cleared"));
        }
    }

    @Nested
    @DisplayName("toggleCompass (KEY_TOGGLE_COMPASS)")
    class Compass {
        Player x;
        Player y;

        /** Order X (index 0, far), P (index 1), Y (close). */
        void three() {
            s = SimFixture.sim(FRAME, 60, 60, "_____");
            w = s.world;
            l = s.locks;
            x = ready(s, "X", 0, 1500, 1000);
            p = ready(s, "P", 0, 1000, 1000);
            y = ready(s, "Y", 0, 1100, 1000);
            p.used &= ~Has.COMPASS;
        }

        @Test
        @DisplayName("without a compass nothing changes")
        void noCompass() {
            three();
            p.have &= ~Has.COMPASS;
            l.toggleCompass(p);
            assertAll(
                () -> assertEquals(0, p.used & Has.COMPASS, "still off"),
                () -> assertEquals(0, p.lockTagged & Player.LOCK_PLAYER, "no lock"));
        }

        @Test
        @DisplayName("on without a lock locks the closest; off keeps the lock; an untagged lock id counts as none")
        void onOff() {
            three();
            l.toggleCompass(p);
            boolean on = (p.used & Has.COMPASS) != 0;
            boolean closest = locked(y);
            p.lockPlId = x.id;
            l.toggleCompass(p);
            boolean offKeeps = locked(x);
            boolean off = (p.used & Has.COMPASS) == 0;
            Player z = ready(s, "Z", 0, 1500, 1500);
            p.lockTagged = 0;
            p.lockPlId = z.id;
            l.toggleCompass(p);
            assertAll(
                () -> assertTrue(off, "the second toggle turns it off"),
                () -> assertTrue(offKeeps, "turning it off keeps the lock"),
                () -> assertTrue(locked(y), "an untagged lock id is no lock: the closest"),
                () -> assertTrue(on, "on"),
                () -> assertTrue(closest, "locked on the closest Y"),
                () -> assertEquals(Has.COMPASS, p.used & Has.COMPASS, "on again"));
        }

        @Test
        @DisplayName("a valid lock is kept, except on the player at index 0 (C's i > 0), oneself or a departed id")
        void keeps() {
            three();
            p.lockTagged = Player.LOCK_PLAYER;
            Player z = ready(s, "Z", 0, 1500, 1500);
            p.lockPlId = z.id;
            l.toggleCompass(p);
            boolean keptZ = locked(z);
            p.used &= ~Has.COMPASS;
            p.lockPlId = x.id;
            l.toggleCompass(p);
            boolean index0 = locked(y);
            p.used &= ~Has.COMPASS;
            p.lockPlId = p.id;
            l.toggleCompass(p);
            boolean self = locked(y);
            p.used &= ~Has.COMPASS;
            p.lockPlId = 200;
            l.toggleCompass(p);
            assertAll(
                () -> assertTrue(keptZ, "a lock on Z (index 3) is kept"),
                () -> assertTrue(index0, "a lock on X (index 0) is replaced by the closest"),
                () -> assertTrue(self, "a lock on oneself is replaced"),
                () -> assertTrue(locked(y), "a departed lock is replaced"));
        }

        @Test
        @DisplayName("alone in the game the compass turns on and no lock is made")
        void alone() {
            s = SimFixture.sim(FRAME, 60, 60, "_____");
            w = s.world;
            l = s.locks;
            p = ready(s, "P", 0, 1000, 1000);
            p.used &= ~Has.COMPASS;
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = 200;
            l.toggleCompass(p);
            assertAll(
                () -> assertEquals(Has.COMPASS, p.used & Has.COMPASS, "on"),
                () -> assertEquals(0, p.lockTagged & Player.LOCK_PLAYER, "the stale lock is cleared"));
        }
    }

    @Nested
    @DisplayName("loadLock (KEY_LOAD_LOCK_n)")
    class Banks {
        @Test
        @DisplayName("with REPROGRAM held the current lock is stored; without a lock the bank stays")
        void store() {
            setup("");
            Player q = add("Q", 0, 10, 0);
            p.status |= Obj.REPROGRAM;
            p.lockBank[2] = -1;
            l.loadLock(p, 2);
            int noLock = p.lockBank[2];
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = q.id;
            l.loadLock(p, 2);
            assertAll(
                () -> assertEquals(-1, noLock, "no lock: the bank stays empty"),
                () -> assertEquals(q.id, p.lockBank[2], "the lock is stored in bank 2"));
        }

        @Test
        @DisplayName("without REPROGRAM a stored allowed lock is loaded; empty or disallowed banks do nothing")
        void load() {
            setup("");
            Player q = add("Q", 0, 10, 0);
            p.lockBank[0] = q.id;
            p.lockBank[1] = -1;
            p.lockBank[3] = p.id;
            l.loadLock(p, 1);
            int afterEmpty = p.lockTagged;
            l.loadLock(p, 3);
            int afterSelf = p.lockTagged;
            l.loadLock(p, 0);
            assertAll(
                () -> assertEquals(0, afterEmpty & Player.LOCK_PLAYER, "an empty bank: no lock"),
                () -> assertEquals(0, afterSelf & Player.LOCK_PLAYER, "a bank on oneself: no lock"),
                () -> assertTrue(locked(q), "bank 0: locked on Q"));
        }
    }

    @Nested
    @DisplayName("hudLock (Frame_status LOCK_VISIBLE)")
    class Hud {
        Player t;

        /** P locked on T 100 px away with the compass on, T seen. */
        void hud(String extra, double dx, double dy) {
            setup(extra);
            t = add("T", 0, dx, dy);
            p.used |= Has.COMPASS;
            p.lockTagged = Player.LOCK_PLAYER | Player.LOCK_VISIBLE;
            p.lockPlId = t.id;
            p.lockDistance = (float) Math.hypot(dx, dy);
            p.sensorRange = 1000;
            p.canSee[t.ind] = true;
        }

        boolean visible() {
            l.hudLock(p);
            return (p.lockTagged & Player.LOCK_VISIBLE) != 0;
        }

        @Test
        @DisplayName("visible when locked, compass on, seen, playing, on radar and not at distance 0")
        void visibleCase() {
            hud("", 100, 0);
            assertTrue(visible(), "LOCK_VISIBLE is set");
        }

        @Test
        @DisplayName("not visible without the lock or the compass, or with a departed target")
        void prerequisites() {
            hud("", 100, 0);
            p.lockTagged = Player.LOCK_VISIBLE;
            boolean noLock = visible();
            hud("", 100, 0);
            p.used &= ~Has.COMPASS;
            boolean noCompass = visible();
            hud("", 100, 0);
            p.lockPlId = 200;
            assertAll(
                () -> assertFalse(noLock, "no LOCK_PLAYER: cleared"),
                () -> assertFalse(noCompass, "compass off: cleared"),
                () -> assertFalse(visible(), "departed target: cleared"));
        }

        @Test
        @DisplayName("limited visibility: visible iff lock distance <= sensor range")
        void limited() {
            hud("limitedVisibility: yes\n", 100, 0);
            p.sensorRange = 100;
            boolean at = visible();
            p.sensorRange = 99.9f;
            boolean beyond = visible();
            w.items.limitedVisibility = false;
            assertAll(
                () -> assertTrue(at, "distance = sensor range: visible"),
                () -> assertFalse(beyond, "beyond the sensor range: not"),
                () -> assertTrue(visible(), "unlimited visibility: any range"));
        }

        @Test
        @DisplayName("an unseen target is visible only as a team mate or own tank")
        void unseen() {
            hud("", 100, 0);
            p.canSee[t.ind] = false;
            boolean unseen = visible();
            t.tank = true;
            t.lockPlId = p.id;
            boolean tank = visible();
            setup("teamPlay: yes\n", "11222");
            t = add("T", 1, 100, 0);
            p.used |= Has.COMPASS;
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = t.id;
            p.lockDistance = 100;
            p.canSee[t.ind] = false;
            assertAll(
                () -> assertFalse(unseen, "unseen: not visible"),
                () -> assertTrue(tank, "an unseen own tank: visible"),
                () -> assertTrue(visible(), "an unseen team mate: visible"));
        }

        @Test
        @DisplayName("the target must be playing and not game over")
        void playing() {
            hud("", 100, 0);
            t.status |= Obj.GAME_OVER;
            boolean over = visible();
            t.status = 0;
            assertAll(
                () -> assertFalse(over, "game over: not visible"),
                () -> assertFalse(visible(), "not playing: not visible"));
        }

        /** {@link #hud} without playersOnRadar, the view {@code vw x vh}, the target at (dx, dy): LOCK_VISIBLE? */
        boolean inView(int vw, int vh, double dx, double dy) {
            hud("playersOnRadar: no\n", dx, dy);
            p.viewWidth = vw;
            p.viewHeight = vh;
            return visible();
        }

        @Test
        @DisplayName("without playersOnRadar the target must be in the client's view: C inview, offsets in (-(w/2), w - w/2)")
        void inView() {
            hud("playersOnRadar: no\n", 383, 383);
            boolean defaultIn = visible();
            hud("playersOnRadar: no\n", 384, 0);
            boolean defaultOut = visible();
            hud("", 900, 900);
            boolean onRadar = visible();
            assertAll(
                () -> assertTrue(defaultIn, "default 768x768 (C DEF_VIEW_SIZE): (383, 383) is in view"),
                () -> assertFalse(defaultOut, "default 768x768: dx 384 is out"),
                () -> assertTrue(onRadar, "with playersOnRadar the distance does not matter"),
                // a small view: 384 x 400
                () -> assertTrue(inView(384, 400, 191, 199), "384x400: (191, 199) is in"),
                () -> assertTrue(inView(384, 400, -191, -199), "384x400: (-191, -199) is in"),
                () -> assertFalse(inView(384, 400, 192, 0), "384x400: dx 192 is out"),
                () -> assertFalse(inView(384, 400, -192, 0), "384x400: dx -192 is out"),
                () -> assertFalse(inView(384, 400, 0, 200), "384x400: dy 200 is out"),
                () -> assertFalse(inView(384, 400, 0, -200), "384x400: dy -200 is out"),
                // a large view: 1024 x 1000 (dy is limited to the 2100 px map)
                () -> assertTrue(inView(1024, 1000, 511, 499), "1024x1000: (511, 499) is in"),
                () -> assertTrue(inView(1024, 1000, -511, -499), "1024x1000: (-511, -499) is in"),
                () -> assertFalse(inView(1024, 1000, 512, 0), "1024x1000: dx 512 is out"),
                () -> assertFalse(inView(1024, 1000, 0, -500), "1024x1000: dy -500 is out"),
                // an odd size: the view starts w/2 = 192 before the player and ends 193 after it
                () -> assertTrue(inView(385, 385, 192, 192), "385x385: +192 is in (the far side is w - w/2 = 193)"),
                () -> assertFalse(inView(385, 385, 193, 0), "385x385: dx +193 is out"),
                () -> assertFalse(inView(385, 385, 0, 193), "385x385: dy +193 is out"),
                () -> assertFalse(inView(385, 385, -192, 0), "385x385: dx -192 is out (the near side is w/2)"),
                () -> assertFalse(inView(385, 385, 0, -192), "385x385: dy -192 is out"));
        }

        @Test
        @DisplayName("a lock at distance 0 is not visible")
        void zero() {
            hud("", 0, 0);
            p.lockDistance = 0;
            assertFalse(visible(), "distance 0: not visible");
        }
    }
}
