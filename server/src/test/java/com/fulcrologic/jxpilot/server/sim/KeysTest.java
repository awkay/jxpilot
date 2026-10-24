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
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.common.net.ControlKey.CHANGE_HOME;
import static com.fulcrologic.jxpilot.common.net.ControlKey.DECREASE_POWER;
import static com.fulcrologic.jxpilot.common.net.ControlKey.DECREASE_TURNSPEED;
import static com.fulcrologic.jxpilot.common.net.ControlKey.FIRE_SHOT;
import static com.fulcrologic.jxpilot.common.net.ControlKey.INCREASE_POWER;
import static com.fulcrologic.jxpilot.common.net.ControlKey.INCREASE_TURNSPEED;
import static com.fulcrologic.jxpilot.common.net.ControlKey.REFUEL;
import static com.fulcrologic.jxpilot.common.net.ControlKey.SHIELD;
import static com.fulcrologic.jxpilot.common.net.ControlKey.SWAP_SETTINGS;
import static com.fulcrologic.jxpilot.common.net.ControlKey.THRUST;
import static com.fulcrologic.jxpilot.common.net.ControlKey.TURN_LEFT;
import static com.fulcrologic.jxpilot.common.net.ControlKey.TURN_RIGHT;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.C_FRAMES;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.ROOM;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.events;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.keys;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keys: C Handle_keyboard (event.c) press/release edges for the supported keys. */
class KeysTest {
    static Simulation room() {
        return sim(C_FRAMES, ROOM);
    }

    @Nested
    @DisplayName("pointerMove (C Receive_pointer_move)")
    class PointerMove {
        @Test
        @DisplayName("turnVel -= dir * movement * turnSpeed / 64, limited to 4..64 with turn resistance")
        void withResistance() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            p.turnSpeed = 32;
            p.turnResistance = 0.12f;
            p.turnVel = 0;
            s.keys.pointerMove(p, 20);
            float right = p.turnVel;
            p.turnVel = 0;
            s.keys.pointerMove(p, -20);
            float left = p.turnVel;
            p.turnVel = 0;
            s.keys.pointerMove(p, 2);
            float small = p.turnVel;
            p.turnVel = 0;
            s.keys.pointerMove(p, 500);
            float big = p.turnVel;
            p.turnVel = 5;
            s.keys.pointerMove(p, 20);
            assertAll(
                () -> assertEquals(-10f, right, "20 px at turnSpeed 32: 10, subtracted"),
                () -> assertEquals(10f, left, "the other way adds"),
                () -> assertEquals(-4f, small, "at least MIN_PLAYER_TURNSPEED (C's odd minimum)"),
                () -> assertEquals(-64f, big, "at most MAX_PLAYER_TURNSPEED"),
                () -> assertEquals(-5f, p.turnVel, "it adds to the current turn velocity"));
        }

        @Test
        @DisplayName("without turn resistance: 0..5*RES; hover pause ignores it; the autopilot is switched off")
        void noResistanceHoverAutopilot() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            p.turnSpeed = 64;
            p.turnResistance = 0;
            p.turnVel = 0;
            s.keys.pointerMove(p, 2);
            float small = p.turnVel;
            p.turnVel = 0;
            s.keys.pointerMove(p, 1000);
            float big = p.turnVel;
            p.turnVel = 0;
            p.status |= Obj.HOVERPAUSE;
            s.keys.pointerMove(p, 50);
            float hovering = p.turnVel;
            p.status &= ~Obj.HOVERPAUSE;
            s.devices.autopilot(p, true);
            boolean engaged = (p.used & Has.AUTOPILOT) != 0;
            s.keys.pointerMove(p, 3);
            assertAll(
                () -> assertEquals(-2f, small, "no minimum without turn resistance"),
                () -> assertEquals(-5f * 128, big, "at most 5 * RES"),
                () -> assertEquals(0f, hovering, "ignored in hover pause"),
                () -> assertTrue(engaged, "the autopilot was on"),
                () -> assertEquals(0, p.used & Has.AUTOPILOT, "a pointer move switches it off (C Autopilot(ind, 0))"));
        }
    }

    @Nested
    @DisplayName("apply: edges, order, dead ships")
    class Apply {
        @Test
        void onlyKeyChangesActAndTheMaskIsRemembered() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            s.keys.apply(p, keys(THRUST));
            int pressed = p.status & Obj.THRUSTING;
            p.status &= ~Obj.THRUSTING;
            s.keys.apply(p, keys(THRUST));
            int held = p.status & Obj.THRUSTING;
            p.status |= Obj.THRUSTING;
            s.keys.apply(p, 0);
            assertAll(
                () -> assertEquals(Obj.THRUSTING, pressed, "pressing THRUST starts thrusting"),
                () -> assertEquals(0, held, "holding it does not act again"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "releasing it stops thrusting"),
                () -> assertEquals(0L, p.prevKeys, "the handled mask is remembered"));
        }

        @Test
        void keysActInAscendingKeyOrderSoShieldBlocksAFireInTheSameTick() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            p.used &= ~Has.SHIELD;
            s.keys.apply(p, keys(SHIELD, FIRE_SHOT));
            int bothDown = p.used & (Has.SHIELD | Has.SHOT);
            s.keys.apply(p, keys(FIRE_SHOT));
            int shieldUp = p.used & (Has.SHIELD | Has.SHOT);
            s.keys.apply(p, 0);
            s.keys.apply(p, keys(SHIELD));
            s.keys.apply(p, keys(FIRE_SHOT));
            assertAll(
                () -> assertEquals(Has.SHIELD, bothDown, "SHIELD (key 5) is handled before FIRE_SHOT (key 6)"),
                () -> assertEquals(0, shieldUp, "releasing SHIELD lowers it; held FIRE does not re-trigger"),
                () -> assertEquals(Has.SHOT, p.used & (Has.SHIELD | Has.SHOT),
                    "shield released before fire pressed in one tick: firing starts"));
        }

        @Test
        void aDeadShipOnlyHandlesTheKeysCAllowsButRemembersAllOfThem() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            p.status &= ~Obj.PLAYING;
            long mask = keys(THRUST, TURN_LEFT, INCREASE_POWER, REFUEL);
            s.keys.apply(p, mask);
            Player q = sim(C_FRAMES, ROOM).ships.join("b", 0);
            q.status &= ~Obj.PLAYING;
            s.keys.apply(q, keys(SWAP_SETTINGS));
            assertAll(
                () -> assertEquals(0, p.status & Obj.THRUSTING, "THRUST is ignored while dead"),
                () -> assertEquals(0, p.used & Has.REFUEL, "REFUEL is ignored while dead"),
                () -> assertEquals((float) (45f * 1.10), p.power, "power keys work while dead"),
                () -> assertEquals(35.0f, p.turnAcc, "turn keys keep turnacc in sync while dead (client default 35)"),
                () -> assertEquals(mask, p.prevKeys, "ignored keys are still consumed"),
                () -> assertEquals(35.0, q.power, "SWAP_SETTINGS works while dead"));
        }

        @Test
        void gameOverAndPausedShipsCountAsDead() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            p.status |= Obj.GAME_OVER;
            s.keys.apply(p, keys(THRUST));
            int over = p.status & Obj.THRUSTING;
            p.status &= ~Obj.GAME_OVER;
            p.status |= Obj.PAUSE;
            s.keys.apply(p, 0);
            s.keys.apply(p, keys(THRUST));
            int paused = p.status & Obj.THRUSTING;
            p.status &= ~Obj.PAUSE;
            p.status |= Obj.HOVERPAUSE;
            s.keys.apply(p, 0);
            s.keys.apply(p, keys(THRUST));
            assertAll(
                () -> assertEquals(0, over, "GAME_OVER: no thrust"),
                () -> assertEquals(0, paused, "PAUSE: no thrust"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "HOVERPAUSE: no thrust"));
        }
    }

    @Nested
    @DisplayName("pressed / released")
    class PressRelease {
        @Test
        void shieldPressRaisesItAndDropsTheLaserOnlyWithTheAbility() {
            Simulation s = room();
            World w = s.world;
            Player p = s.ships.join("a", 0);
            p.used = Has.LASER;
            s.keys.pressed(p, SHIELD);
            int with = p.used;
            p.used = 0;
            p.have &= ~Has.SHIELD;
            s.keys.pressed(p, SHIELD);
            assertAll(
                () -> assertEquals(Has.SHIELD, with, "shield up, laser off"),
                () -> assertEquals(0, p.used, "without HAS_SHIELD nothing happens"));
        }

        @Test
        void shieldReleaseLowersItAndRestartsTheFireTimerOnlyWhenRaised() {
            Simulation s = room();
            World w = s.world;
            Player p = s.ships.join("a", 0);
            w.frames = 77;
            p.used = Has.SHIELD | Has.LASER | Has.COMPASS;
            s.keys.released(p, SHIELD);
            double t1 = p.shotTime;
            int u1 = p.used;
            w.frames = 99;
            s.keys.released(p, SHIELD);
            assertAll(
                () -> assertEquals(Has.COMPASS, u1, "shield and laser are lowered"),
                () -> assertEquals(77.0, t1, "shot_time = frame_loops: no instant shot after dropping shields"),
                () -> assertEquals(77.0, p.shotTime, "releasing an unraised shield leaves the timer"));
        }

        @Test
        void fireStartsAutofireWhenAllowedAndReleaseStopsIt() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            p.used = 0;
            s.world.frames = 100; // past the fire repeat delay
            int objs = s.world.objects.count();
            s.keys.pressed(p, FIRE_SHOT);
            int fired = s.world.objects.count() - objs;
            int auto = p.used & Has.SHOT;
            s.keys.released(p, FIRE_SHOT);
            int stopped = p.used & Has.SHOT;
            p.used = Has.SHIELD;
            s.keys.pressed(p, FIRE_SHOT);
            int shielded = p.used & Has.SHOT;
            p.used = 0;
            p.have &= ~Has.SHOT;
            s.keys.pressed(p, FIRE_SHOT);
            assertAll(
                () -> assertEquals(1, fired, "a shot is fired"),
                () -> assertEquals(Has.SHOT, auto, "and autofire starts"),
                () -> assertEquals(0, stopped, "release stops autofire"),
                () -> assertEquals(0, shielded, "a shielded ship cannot fire"),
                () -> assertEquals(0, p.used & Has.SHOT, "without HAS_SHOT nothing happens"));
        }

        @Test
        void fireWhileAlreadyFiringDoesNotFireAgain() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            p.used = Has.SHOT;
            s.world.frames = 100;
            int objs = s.world.objects.count();
            s.keys.pressed(p, FIRE_SHOT);
            assertEquals(objs, s.world.objects.count(), "HAS_SHOT already used: no extra shot");
        }

        @Test
        void turnKeysSetTurnAccFromTheCurrentMask() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            s.keys.apply(p, keys(TURN_LEFT));
            double left = p.turnAcc;
            s.keys.apply(p, keys(TURN_LEFT, TURN_RIGHT));
            double both = p.turnAcc;
            s.keys.apply(p, keys(TURN_RIGHT));
            double right = p.turnAcc;
            s.keys.apply(p, 0);
            assertAll(
                () -> assertEquals(35.0, left, "left: +turnspeed"),
                () -> assertEquals(0.0, both, "both: cancel"),
                () -> assertEquals(-35.0, right, "releasing left with right held: -turnspeed"),
                () -> assertEquals(0.0, p.turnAcc, "all released: 0"));
        }

        @Test
        void refuelPressPicksAStationAndReleaseStops() {
            Simulation s = sim(C_FRAMES, ShipsFuelTest.STATIONS);
            Player p = s.ships.join("a", 0);
            s.keys.pressed(p, REFUEL);
            int on = p.used & Has.REFUEL;
            s.keys.released(p, REFUEL);
            assertAll(
                () -> assertEquals(Has.REFUEL, on, "REFUEL starts refuelling at the nearest station"),
                () -> assertEquals(0, p.used & Has.REFUEL, "release stops"));
        }

        @Test
        void selfDestructTogglesAnd150FrameCountdown() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            s.keys.pressed(p, ControlKey.SELF_DESTRUCT);
            int armed = p.status & Obj.SELF_DESTRUCT;
            double c = p.count;
            p.count = 20;
            s.keys.pressed(p, ControlKey.SELF_DESTRUCT);
            assertAll(
                () -> assertEquals(Obj.SELF_DESTRUCT, armed, "first press arms"),
                () -> assertEquals(150.0, c, "the countdown is 150 frames"),
                () -> assertEquals(0, p.status & Obj.SELF_DESTRUCT, "second press disarms"),
                () -> assertEquals(20.0, p.count, "disarming leaves the count"));
        }

        @Test
        void swapSettingsSwapsOnlyWhenNotTurning() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            p.turnResistance = 0.5f;
            s.keys.pressed(p, SWAP_SETTINGS);
            double pw = p.power;
            double ts = p.turnSpeed;
            double tr = p.turnResistance;
            double pwS = p.powerS;
            double tsS = p.turnSpeedS;
            double trS = p.turnResistanceS;
            p.turnAcc = 1;
            s.keys.pressed(p, SWAP_SETTINGS);
            assertAll(
                () -> assertEquals(35.0, pw, "power swaps"),
                () -> assertEquals(25.0, ts, "turnspeed swaps"),
                () -> assertEquals(0.12f, tr, "turnresistance swaps"),
                () -> assertEquals(45.0, pwS, "the old power is the alternate"),
                () -> assertEquals(35.0, tsS, "the old turnspeed is the alternate"),
                () -> assertEquals(0.5, trS, "the old turnresistance is the alternate"),
                () -> assertEquals(35.0, p.power, "turning: no swap"));
        }

        @Test
        void powerKeysScaleByTenPercentWithinLimits() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            s.keys.pressed(p, INCREASE_POWER);
            double up = p.power;
            p.power = 52;
            s.keys.pressed(p, INCREASE_POWER);
            double capped = p.power;
            p.power = 45;
            s.keys.pressed(p, DECREASE_POWER);
            double down = p.power;
            p.power = 5.2f;
            s.keys.pressed(p, DECREASE_POWER);
            assertAll(
                () -> assertEquals((float) (45f * 1.10), up, "power *= 1.10 (computed in double, stored as a C float)"),
                () -> assertEquals(55.0, capped, "at most MAX_PLAYER_POWER"),
                () -> assertEquals((float) (45f * 0.90), down, "power *= 0.90"),
                () -> assertEquals(5.0, p.power, "at least MIN_PLAYER_POWER"));
        }

        @Test
        void turnspeedKeysScaleByFivePercentWhenNotTurningWithinLimits() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            s.keys.pressed(p, INCREASE_TURNSPEED);
            double up = p.turnSpeed;
            p.turnSpeed = 63;
            s.keys.pressed(p, INCREASE_TURNSPEED);
            double capped = p.turnSpeed;
            p.turnSpeed = 30;
            s.keys.pressed(p, DECREASE_TURNSPEED);
            double down = p.turnSpeed;
            p.turnSpeed = 4.1f;
            s.keys.pressed(p, DECREASE_TURNSPEED);
            double floor = p.turnSpeed;
            p.turnAcc = 1;
            p.turnSpeed = 70;
            s.keys.pressed(p, INCREASE_TURNSPEED);
            double turningUp = p.turnSpeed;
            p.turnSpeed = 2;
            s.keys.pressed(p, DECREASE_TURNSPEED);
            assertAll(
                () -> assertEquals((float) (35f * 1.05), up, "turnspeed *= 1.05 (from the default 35)"),
                () -> assertEquals(64.0, capped, "at most MAX_PLAYER_TURNSPEED"),
                () -> assertEquals((float) (30f * 0.95), down, "turnspeed *= 0.95"),
                () -> assertEquals(4.0, floor, "at least MIN_PLAYER_TURNSPEED"),
                () -> assertEquals(64.0, turningUp, "turning: no scaling, but still limited"),
                () -> assertEquals(4.0, p.turnSpeed, "turning: no scaling, but still raised to the minimum"));
        }

        @Test
        void otherKeysDoNothing() {
            Simulation s = room();
            Player p = s.ships.join("a", 0);
            int used = p.used;
            int status = p.status;
            s.keys.pressed(p, ControlKey.TALK);
            s.keys.released(p, ControlKey.TALK);
            assertAll(
                () -> assertEquals(used, p.used, "abilities unchanged"),
                () -> assertEquals(status, p.status, "status unchanged"));
        }
    }

    @Nested
    @DisplayName("changeHome")
    class ChangeHome {
        static final String[] TWO = {
            "xxxxxxxxx",
            "x       x",
            "x _   _ x",
            "x       x",
            "xxxxxxxxx",
        };

        @Test
        void onAFreeBaseItBecomesHome() {
            Simulation s = sim(C_FRAMES, ShipsJoinTest.THREE);
            World w = s.world;
            Player a = s.ships.join("ann", 0);
            a.homeBase = 0;
            Player b = s.ships.join("bob", 0);
            b.homeBase = 2;
            a.initPixels(w.map.bases().get(1).x(), w.map.bases().get(1).y());
            long seq = w.events.seq();
            s.keys.pressed(a, CHANGE_HOME);
            assertAll(
                () -> assertEquals(1, a.homeBase, "the base under the ship is home"),
                () -> assertEquals(List.of("BASE_OWNER 0 -1 0", "BASE_OWNER 1 1 0",
                    "MESSAGE " + Msg.Text.KIND_GAME + " -1 ann has changed home base."),
                    events(w, seq), "announced"),
                () -> assertEquals(2, b.homeBase, "others are not affected"));
        }

        @Test
        void onAnotherPlayersHomeItIsTakenOverAndTheyGetANewBase() {
            Simulation s = sim(C_FRAMES, TWO);
            World w = s.world;
            Player a = s.ships.join("ann", 0);
            a.homeBase = 0;
            Player b = s.ships.join("bob", 0);
            b.homeBase = 1;
            a.initPixels(w.map.bases().get(1).x(), w.map.bases().get(1).y());
            long seq = w.events.seq();
            s.keys.pressed(a, CHANGE_HOME);
            assertAll(
                () -> assertEquals(1, a.homeBase, "ann takes base 1"),
                () -> assertEquals(0, b.homeBase, "bob gets the free base 0"),
                () -> assertEquals(List.of("BASE_OWNER 0 -1 0", "BASE_OWNER 1 1 0", "BASE_OWNER 0 2 0",
                        "MESSAGE " + Msg.Text.KIND_GAME + " -1 ann has taken over bob's home base."),
                    events(w, seq), "both owners and the C takeover message"),
                () -> assertEquals(-1.0, b.count, "a living displaced player keeps flying"));
        }

        @Test
        void aDeadDisplacedPlayerRestartsItsRecoveryAndAGameOverOneIsMovedHome() {
            Simulation s = sim(C_FRAMES, TWO);
            World w = s.world;
            Player a = s.ships.join("ann", 0);
            a.homeBase = 0;
            Player b = s.ships.join("bob", 0);
            b.homeBase = 1;
            b.status &= ~Obj.PLAYING;
            b.count = 10;
            a.initPixels(w.map.bases().get(1).x(), w.map.bases().get(1).y());
            s.keys.pressed(a, CHANGE_HOME);
            double deadCount = b.count;
            Simulation s2 = sim(C_FRAMES, TWO);
            Player c = s2.ships.join("c", 0);
            c.homeBase = 0;
            Player d = s2.ships.join("d", 0);
            d.homeBase = 1;
            d.status |= Obj.GAME_OVER;
            c.initPixels(s2.world.map.bases().get(1).x(), s2.world.map.bases().get(1).y());
            s2.keys.pressed(c, CHANGE_HOME);
            assertAll(
                () -> assertEquals(180.0, deadCount, "C Pick_startpos: count = RECOVERY_DELAY for a dead player"),
                () -> assertEquals((int) (s2.world.map.bases().get(0).x() * 64), d.cx,
                    "a GAME_OVER player is put on the new base (Go_home)"));
        }

        @Test
        void offABaseOnTheOwnHomeOrOnAnotherTeamsBaseNothingHappens() {
            Simulation s = sim(C_FRAMES, ROOM);
            World w = s.world;
            Player a = s.ships.join("ann", 0);
            long seq = w.events.seq();
            s.keys.pressed(a, CHANGE_HOME);
            a.initPixels(100, 100);
            s.keys.pressed(a, CHANGE_HOME);
            Simulation t = sim(C_FRAMES + "teamPlay: yes\n", ShipsJoinTest.TEAMS);
            Player one = t.ships.join("one", 1);
            one.initPixels(t.world.map.bases().get(2).x(), t.world.map.bases().get(2).y());
            long tseq = t.world.events.seq();
            t.keys.pressed(one, CHANGE_HOME);
            assertAll(
                () -> assertEquals(seq, w.events.seq(), "own home or off a base: no event"),
                () -> assertEquals(0, a.homeBase, "home unchanged"),
                () -> assertEquals(tseq, t.world.events.seq(), "another team's base: no event"),
                () -> assertTrue(one.homeBase != 2, "another team's base is not taken"));
        }
    }

    @Nested
    @DisplayName("power change events")
    class PowerEvents {
        @Test
        @DisplayName("a key that changes power emits THRUST(id, afterburners, power); other keys do not")
        void thrustEvent() {
            Simulation s = com.fulcrologic.jxpilot.server.TestWorlds.sim("framesPerSecond: 60",
                "xxxxxx", "x _  x", "x    x", "xxxxxx");
            Player p = com.fulcrologic.jxpilot.server.TestWorlds.join(s, "a", 0);
            World w = s.world;
            long from = w.events.seq();
            s.keys.apply(p, INCREASE_POWER.mask());
            s.keys.apply(p, 0);
            s.keys.apply(p, THRUST.mask() | SHIELD.mask());
            long count = 0;
            int last = -1;
            for (long q = from; q < w.events.seq(); q++) {
                int i = com.fulcrologic.jxpilot.server.world.Events.index(q);
                if (w.events.kind[i] == com.fulcrologic.jxpilot.server.world.Events.THRUST) {
                    count++;
                    last = i;
                }
            }
            long n = count;
            int i = last;
            assertAll(
                () -> assertEquals(1, n, "only the power key emitted THRUST"),
                () -> assertEquals(p.id, w.events.a[i], "for the player"),
                () -> assertEquals(0, w.events.b[i], "with its afterburners"),
                () -> assertEquals((float) (45.0 * 1.10), w.events.value[i], "and the new power 45 * 1.1"));
        }
    }
}
