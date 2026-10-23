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
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ShipFx.C_FRAMES;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.FPS14;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.ROOM;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.events;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.keys;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.sim;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.tick;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.ticks;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Death and rebirth: Kill_player/Player_death_reset (player.c), Transport_to_home and self-destruct (update.c). */
class ShipsDeathTest {
    static final long SELF_DESTRUCT = keys(ControlKey.SELF_DESTRUCT);

    @Nested
    @DisplayName("kill")
    class Kill {
        @Test
        void deathResetsMotionStatusItemsAbilitiesAndStartsTheRecoveryCount() {
            Simulation s = sim(C_FRAMES + "initialArmor: 1\n", ROOM);
            World w = s.world;
            Player p = s.ships.join("a", 0);
            p.vx = 4;
            p.vy = 5;
            p.accX = 1;
            p.accY = 1;
            p.mass = 99;
            p.emptyMass = 50;
            p.status |= Obj.THRUSTING | Obj.KILLED | Obj.WARPED | Obj.SELF_DESTRUCT;
            p.items[Item.ARMOR.ordinal()] = 0;
            p.items[Item.MINE.ordinal()] = 4;
            p.items[Item.TANK.ordinal()] = 2;
            p.shotMax = 3;
            p.damaged = 4;
            p.stunned = 4;
            p.fuel = 600000;
            p.have = Has.SHOT;
            p.used = Has.SHOT | Has.REFUEL;
            p.scoreDirty = false;
            s.ships.kill(p);
            // player.c Player_death_reset
            assertAll(
                () -> assertEquals(0.0, p.vx + p.vy, "velocity is zeroed"),
                () -> assertEquals(0.0, p.accX + p.accY, "acceleration is zeroed"),
                () -> assertEquals(20.0, p.emptyMass, "empty mass is ShipMass"),
                () -> assertEquals(Obj.GRAVITY, p.status, "KILL_BITS cleared: not PLAYING, KILLED, THRUSTING..."),
                () -> assertEquals(1, p.items[Item.ARMOR.ordinal()], "items go back to their initial count"),
                () -> assertEquals(0, p.items[Item.MINE.ordinal()], "including mines"),
                () -> assertEquals(0, p.items[Item.TANK.ordinal()], "Player_init_fuel: no extra tanks (single tank)"),
                () -> assertEquals(256, p.shotMax, "shot max is maxPlayerShots"),
                () -> assertEquals(180.0, p.count, "RECOVERY_DELAY = 3*FPS frames"),
                () -> assertEquals(0.0, p.damaged + p.stunned, "damage and stun cleared"),
                () -> assertEquals(540000.0, p.fuel, "10% of the fuel is lost (above the minimum)"),
                () -> assertEquals(w.params.defHave, p.have, "abilities are DEF_HAVE"),
                () -> assertEquals(Has.SHIELD | Has.COMPASS, p.used, "used |= DEF_USED, minus USED_KILL, within have"),
                () -> assertEquals(1, p.deaths, "a death is counted"),
                () -> assertEquals(1, p.life, "without limited lives, life counts up (C)"),
                () -> assertTrue(p.scoreDirty, "the score line changes"),
                () -> assertTrue(w.objects.count() > 0, "the ship explodes into debris"));
        }

        @Test
        void aLongerCountIsKeptAndLowFuelIsRaisedToAboutInitialFuel() {
            double min = 1000 * 256.0;
            double lowest = Double.MAX_VALUE;
            double highest = 0;
            for (int seed = 1; seed <= 20; seed++) {
                Simulation s = sim(C_FRAMES, ROOM);
                Player p = s.ships.join("a", 0);
                s.world.rng.seed(seed);
                p.count = 200;
                p.fuel = 1000;
                s.ships.kill(p);
                // player.c: minfuel = initialFuel*256 + (int)(rfrac() * (1 + minfuel) * 0.2f)
                double f = p.fuel;
                assertAll(
                    () -> assertEquals(200.0, p.count, "count = MAX(RECOVERY_DELAY, count)"),
                    () -> assertTrue(f >= min && f <= min + (int) ((1 + min) * 0.2f),
                        "fuel is initialFuel plus up to 20%: " + f),
                    () -> assertEquals(Math.floor(f), f, "a whole fixed-point amount"));
                lowest = Math.min(lowest, f);
                highest = Math.max(highest, f);
            }
            double lo = lowest;
            double hi = highest;
            assertAll(
                () -> assertTrue(lo < min + 51200 * 0.3, "some seeds give little extra: " + lo),
                () -> assertTrue(hi > min + 51200 * 0.7, "some seeds give nearly 20% extra: " + hi));
        }

        @Test
        void withLimitedLivesTheLastDeathEndsTheGame() {
            Simulation s = sim(C_FRAMES + "limitedLives: yes\nworldLives: 1\n", ROOM);
            Player p = s.ships.join("a", 0);
            s.ships.kill(p);
            int life1 = p.life;
            int over1 = p.status & Obj.GAME_OVER;
            s.ships.kill(p);
            assertAll(
                () -> assertEquals(0, life1, "the first death uses the only extra life"),
                () -> assertEquals(0, over1, "the player is still in the game"),
                () -> assertEquals(0, p.life, "the second death leaves 0 lives"),
                () -> assertEquals(Obj.GAME_OVER, p.status & Obj.GAME_OVER, "and ends the game"),
                () -> assertEquals(2, p.deaths, "both deaths count"));
        }

        @Test
        void aPausedPlayerDiesWithoutCountingADeath() {
            Simulation s = sim(C_FRAMES + "limitedLives: yes\nworldLives: 2\n", ROOM);
            Player p = s.ships.join("a", 0);
            p.status |= Obj.PAUSE;
            s.ships.kill(p);
            assertAll(
                () -> assertEquals(0, p.deaths, "no death"),
                () -> assertEquals(2, p.life, "no life lost"));
        }

        @Test
        void aKilledStatusMakesTheSimulationKillTheShip() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            p.status |= Obj.KILLED;
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(0, p.status & Obj.PLAYING, "the ship is dead"),
                () -> assertEquals(180.0, p.count, "and counting down"),
                () -> assertEquals(1, p.deaths, "once"));
        }
    }

    @Nested
    @DisplayName("respawn and transportToHome")
    class Respawn {
        @Test
        void aDeadShipIsCarriedHomeAndRespawnsAfter3SecondsOfFrames() {
            Simulation s = sim(C_FRAMES, ROOM);
            World w = s.world;
            Player p = s.ships.join("a", 0);
            p.initPixels(100.5, 300.5);
            s.ships.kill(p);
            long seq = w.events.seq();
            tick(s, p, 0);
            // update.c Transport_to_home, T = 180: count 179 -> t = 179.5, 2t > T: t = 0.5,
            // m = 4*0.5 / (180^2 - 2*0.25); velocity = (base - pos) * m
            float t1 = 180 - (179 + 0.5f);
            float m1 = (4 * t1) / (180 * 180 - 2 * t1 * t1);
            double vx1 = p.vx;
            double vy1 = p.vy;
            float dx = 192.5f - 100;
            float dy = 192.5f - 300;
            ticks(s, p, 179, 0);
            int alive180 = p.status & Obj.PLAYING;
            double count180 = p.count;
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(dx * m1, vx1, "x: distance * m (accelerating phase, C floats)"),
                () -> assertEquals(dy * m1, vy1, "y: distance * m"),
                () -> assertEquals(0, alive180, "after 180 frames the ship is still dead"),
                () -> assertEquals(0.0, count180, "with count 0"),
                () -> assertEquals(Obj.PLAYING, p.status & Obj.PLAYING, "frame 181: PLAYING again"),
                () -> assertEquals(12320, p.cx, "on the base (x)"),
                () -> assertEquals(12320, p.cy, "on the base (y)"),
                () -> assertEquals(0.0, p.vx, "at rest"),
                () -> assertEquals(-1.0, p.count, "the count is finished"),
                () -> assertTrue(events(w, seq).contains("GAME_EVENT " + Msg.GameEvent.SPAWN + " 1 0"),
                    "a SPAWN event is emitted"));
        }

        @Test
        void inTheSecondHalfTheTransportBrakesWithTwoOverT() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            p.initPixels(100.5, 300.5);
            s.ships.kill(p);
            p.count = 90;
            tick(s, p, 0);
            float t = 89 + 0.5f;
            // 2t = 179 <= 180: m = 2 / t
            double vx = p.vx;
            assertAll(
                () -> assertEquals(89.0, p.count, "count went down one frame"),
                () -> assertEquals((192.5f - 100) * (2 / t), vx, "m = 2/t near the end (C floats)"));
        }

        @Test
        void at14FpsTheRecoveryTakes42Ticks() {
            Simulation s = sim(FPS14, ROOM);
            Player p = s.ships.join("a", 0);
            p.initPixels(100.5, 300.5);
            s.ships.kill(p);
            double count0 = p.count;
            ticks(s, p, 42, 0);
            int alive42 = p.status & Obj.PLAYING;
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(42.0, count0, "3*14 = 42 frames"),
                () -> assertEquals(0, alive42, "dead for 42 ticks (one tick is one frame)"),
                () -> assertEquals(Obj.PLAYING, p.status & Obj.PLAYING, "alive on tick 43"),
                () -> assertEquals(12320, p.cx, "on the base"));
        }

        @Test
        void aDeadShipArrivesNearItsBaseBeforeRespawning() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            p.initPixels(100.5, 300.5);
            s.ships.kill(p);
            ticks(s, p, 180, 0);
            // C (integer pixels, truncated clicks) lands at (191, 191): the last frame's m = 4 amplifies the
            // sub-pixel error; Go_home then puts the ship exactly on the base. Tolerance: 2 px.
            assertAll(
                () -> assertEquals(192, p.px, 2, "x is within 2 px of the base centre"),
                () -> assertEquals(192, p.py, 2, "y is within 2 px of the base centre"));
        }
    }

    @Nested
    @DisplayName("self-destruct")
    class SelfDestruct {
        @Test
        void selfDestructKills150FramesAfterThePress() {
            Simulation s = sim(C_FRAMES, ROOM);
            World w = s.world;
            Player p = s.ships.join("a", 0);
            tick(s, p, SELF_DESTRUCT);
            double c1 = p.count;
            int sd = p.status & Obj.SELF_DESTRUCT;
            ticks(s, p, 148, SELF_DESTRUCT);
            int alive149 = p.status & Obj.PLAYING;
            long seq = w.events.seq();
            tick(s, p, SELF_DESTRUCT);
            List<String> ev = events(w, seq);
            assertAll(
                () -> assertEquals(Obj.SELF_DESTRUCT, sd, "the key arms self-destruct"),
                () -> assertEquals(149.0, c1, "count 150 frames, one already gone"),
                () -> assertEquals(Obj.PLAYING, alive149, "alive after 149 frames"),
                () -> assertEquals(0, p.status & (Obj.PLAYING | Obj.SELF_DESTRUCT), "dead after 150, not armed"),
                () -> assertEquals(1, p.deaths, "a death"),
                () -> assertEquals(0.0, p.score, "selfDestructScoreMult 0: no score change"),
                () -> assertEquals(List.of("GAME_EVENT " + Msg.GameEvent.KILL + " 1 1",
                        "MESSAGE " + Msg.Text.KIND_GAME + " -1 a has committed suicide."), ev.subList(0, 2),
                    "the self kill is reported (before any score it causes) and announced"));
        }

        @Test
        void selfDestructCostsRateTimesTheMultiplier() {
            Simulation s = sim(C_FRAMES + "selfDestructScoreMult: 2\n", ROOM);
            Player p = s.ships.join("a", 0);
            p.score = 100;
            p.status |= Obj.SELF_DESTRUCT;
            p.count = 1;
            tick(s, p, 0);
            // score.c Rate(0, 100) = 20 - 10240/1124
            double rate = 20 - (10.0 * 1024) / (100 + 1024);
            assertEquals(100 - rate * 2, p.score, 1e-12, "score -= Rate(0, score) * selfDestructScoreMult");
        }

        @Test
        void theUpdateItselfKillsTheSelfDestructingShip() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            p.status |= Obj.SELF_DESTRUCT;
            p.count = 1;
            s.ships.update(p);
            assertAll(
                () -> assertEquals(0, p.status & (Obj.PLAYING | Obj.KILLED), "Kill_player ran: dead, KILLED cleared"),
                () -> assertEquals(180.0, p.count, "the recovery count started"));
        }

        @Test
        void pressingAgainDisarmsAndTheCountJustRunsOut() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("a", 0);
            tick(s, p, SELF_DESTRUCT);
            tick(s, p, 0);
            tick(s, p, SELF_DESTRUCT);
            ticks(s, p, 200, 0);
            assertAll(
                () -> assertEquals(0, p.status & Obj.SELF_DESTRUCT, "disarmed"),
                () -> assertEquals(Obj.PLAYING, p.status & Obj.PLAYING, "still alive"),
                () -> assertEquals(-1.0, p.count, "the count ran out"),
                () -> assertEquals(0, p.deaths, "no death"));
        }
    }
}
