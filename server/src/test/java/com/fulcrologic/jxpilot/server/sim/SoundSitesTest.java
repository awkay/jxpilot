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

import com.fulcrologic.jxpilot.common.sim.MoveState;
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.CrashesTest.ms;
import static com.fulcrologic.jxpilot.common.sim.MoveState.*;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every C {@code sound_play_*} call site the Java server ports (saudio.c via {@link Sounds}): which event, at
 * which place, heard at which volume. Volumes: {@code (int) max(100 - 100 * dist / 525, 10)} within 525 px.
 */
@DisplayName("sound emit sites")
class SoundSitesTest {
    /** A player's queued sounds as "EVENT:volume", in queue order. */
    static List<String> heard(Player p) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < p.audio.count(); i++) {
            out.add(SoundEvent.of(p.audio.event(i)) + ":" + p.audio.volume(i));
        }
        return out;
    }

    static void quiet(Player... ps) {
        for (Player p : ps) {
            p.audio.clear();
        }
    }

    @Nested
    @DisplayName("ships and weapons")
    class Ships {
        @Test
        @DisplayName("thrust, explosion and firing a shot play at the ship (ship.c Thrust, Explode_fighter; shot.c)")
        void ship() {
            Simulation s = sim(FRAME, 20, 20, "__");
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 410, 200);
            quiet(a, b);
            s.debris.thrust(a);
            List<String> thrustA = heard(a);
            List<String> thrustB = heard(b);
            quiet(a, b);
            s.debris.explodeShip(a);
            List<String> explode = heard(a);
            quiet(a, b);
            s.shots.fire(a, 0, false, 200, 200, Obj.SHOT, 0, 0);
            List<String> fired = heard(a);
            quiet(a, b);
            s.shots.fire(null, 0, true, 200, 200, Obj.CANNON_SHOT, 0, 0);
            List<String> noOwner = heard(a);
            a.fuel = 0;
            s.shots.fire(a, 0, false, 200, 200, Obj.SHOT, 0, 0);
            List<String> noFuel = heard(a);
            assertAll(
                () -> assertEquals(List.of("THRUST:100"), thrustA, "the thruster hears its thrust at full volume"),
                // 210 px away: 100 - 100*210/525 = 60
                () -> assertEquals(List.of("THRUST:60"), thrustB, "a ship 210 px away hears it at 60"),
                () -> assertEquals(List.of("PLAYER_EXPLOSION:100"), explode, "an exploding ship plays PLAYER_EXPLOSION"),
                () -> assertEquals(List.of("FIRE_SHOT:100"), fired, "a player's shot plays FIRE_SHOT"),
                () -> assertEquals(List.of(), noOwner, "a shot without a player (cannon) plays no FIRE_SHOT"),
                () -> assertEquals(List.of(), noFuel, "a shot refused for lack of fuel plays nothing"));
        }

        @Test
        @DisplayName("a cannon shot's explosion plays OBJECT_EXPLOSION at the shot, unless it is spent (shot.c Delete_shot)")
        void shotExplodes() {
            Simulation s = sim(FRAME, 20, 20, "__");
            World w = s.world;
            Player a = ready(s, "A", 0, 300, 300);
            quiet(a);
            int h = obj(w, Obj.CANNON_SHOT, Obj.NO_ID, 300, 300, 0, 0, 0.4, 1);
            s.shots.delete(h);
            List<String> boom = heard(a);
            quiet(a);
            int spent = obj(w, Obj.CANNON_SHOT, Obj.NO_ID, 300, 300, 0, 0, 0, 1);
            s.shots.delete(spent);
            assertAll(
                () -> assertEquals(List.of("OBJECT_EXPLOSION:100"), boom, "an exploding cannon shot plays OBJECT_EXPLOSION"),
                () -> assertEquals(List.of(), heard(a), "a massless shot does not explode, so plays nothing"));
        }

        @Test
        @DisplayName("cannon fire plays CANNON_FIRE at the cannon (cannon.c Cannon_fire)")
        void cannonFire() {
            Simulation s = CannonsTest.sim(FRAME, "__");
            Player p = ready(s, "P", 0, CannonsTest.CX, CannonsTest.CY);
            quiet(p);
            s.cannons.fire(0, 40);
            assertEquals(List.of("CANNON_FIRE:100"), heard(p), "a player at the cannon hears it at full volume");
        }
    }

    @Nested
    @DisplayName("collisions (collision.c)")
    class Hits {
        @Test
        @DisplayName("a ship collision plays PLAYER_HIT_PLAYER at the first ship and PLAYER_RAN_OVER_PLAYER at the victim")
        void runOver() {
            Simulation s = sim(FRAME, 20, 20, "__");
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 210, 200);
            b.used |= Has.SHIELD;
            quiet(a, b);
            CollisionsTest.check(s);
            List<String> victimA = heard(a);
            List<String> survivorB = heard(b);
            Simulation s2 = sim(FRAME, 20, 20, "__");
            Player c = ready(s2, "C", 0, 200, 200);
            Player d = ready(s2, "D", 0, 210, 200);
            c.used |= Has.SHIELD;
            quiet(c, d);
            CollisionsTest.check(s2);
            assertAll(
                () -> assertEquals(List.of("PLAYER_HIT_PLAYER:100", "PLAYER_RAN_OVER_PLAYER:100"), victimA,
                    "the first ship (and victim) hears both at its own place"),
                // 10 px: 100 - 100*10/525 = 98.1
                () -> assertEquals(List.of("PLAYER_HIT_PLAYER:98", "PLAYER_RAN_OVER_PLAYER:98"), survivorB,
                    "the other ship hears both 10 px away"),
                () -> assertEquals(List.of("PLAYER_HIT_PLAYER:100", "PLAYER_RAN_OVER_PLAYER:98"), heard(c),
                    "when the first ship survives, the run-over sound is at the second ship"),
                () -> assertEquals(List.of("PLAYER_HIT_PLAYER:98", "PLAYER_RAN_OVER_PLAYER:100"), heard(d),
                    "and the second ship, the victim, hears it at its own place"));
        }

        @Test
        @DisplayName("leaving the world in the collision check plays nothing (collision.c PlayerCollision; the "
            + "CrashUniverse sound is Player_crash's, see crashes())")
        void universe() {
            Simulation s = sim(FRAME, 20, 20, "__");
            Player a = ready(s, "A", 0, -5, 200);
            Player b = ready(s, "B", 0, 30, 200);
            quiet(a, b);
            CollisionsTest.check(s);
            assertAll(
                () -> assertTrue((a.status & Obj.KILLED) != 0, "(the ship left the world and is killed)"),
                () -> assertEquals(List.of(), heard(b), "a ship 35 px away hears nothing"),
                () -> assertEquals(List.of(), heard(a), "nor does the ship itself"));
        }

        @Test
        @DisplayName("shots: eaten by shields, cannon fire and self kills have sounds; other kills none (Player_collides_with_killing_shot)")
        void shots() {
            Simulation s = sim(FRAME, 20, 20, "__");
            World w = s.world;
            Player v = ready(s, "V", 0, 200, 200);
            Player k = ready(s, "K", 0, 400, 400);
            List<List<String>> got = new ArrayList<>();
            int[][] cases = {
                // owner, type, status, shielded
                {k.id, Obj.SHOT, 0, 1},
                {Obj.NO_ID, Obj.CANNON_SHOT, Obj.FROMCANNON, 1},
                {k.id, Obj.MINE, 0, 1},
                {Obj.NO_ID, Obj.CANNON_SHOT, Obj.FROMCANNON, 0},
                {v.id, Obj.SHOT, 0, 0},
                {k.id, Obj.SHOT, 0, 0},
                {Obj.NO_ID, Obj.SHOT, 0, 0},
            };
            for (int[] c : cases) {
                v.used = c[3] == 1 ? Has.SHIELD : 0;
                v.fuel = 1e6;
                int h = obj(w, c[1], c[0], 200, 200, 0, 0, 0.1, 10);
                w.objects.status[h] = c[2];
                quiet(v);
                s.collisions.killingShot(v, h);
                got.add(heard(v));
            }
            assertAll(
                () -> assertEquals(List.of("PLAYER_EAT_SHOT:100"), got.get(0), "a shield eats a player's shot"),
                () -> assertEquals(List.of("PLAYER_EAT_SHOT:100"), got.get(1), "a shield eats a cannon shot"),
                () -> assertEquals(List.of(), got.get(2), "eating another kind of object is not PLAYER_EAT_SHOT"),
                () -> assertEquals(List.of("PLAYER_HIT_CANNONFIRE:100"), got.get(3), "unshielded cannon fire"),
                () -> assertEquals(List.of("PLAYER_SHOT_THEMSELF:100"), got.get(4), "an own shot"),
                () -> assertEquals(List.of(), got.get(5), "another player's shot kills without a sound (as C)"),
                () -> assertEquals(List.of(), got.get(6), "an unowned shot kills without a sound (as C)"));
        }
    }

    @Nested
    @DisplayName("crashes (walls.c Player_crash, Cannon_dies, Move_player)")
    class Crashes {
        @Test
        @DisplayName("wall-like crashes play PLAYER_HIT_WALL; a cannon PLAYER_HIT_CANNON unless shielded, then CANNON_EXPLOSION")
        void crashes() {
            Simulation s = CannonsTest.sim(FRAME, "__");
            Player a = ready(s, "A", 0, CannonsTest.CX, CannonsTest.CY);
            List<List<String>> got = new ArrayList<>();
            for (int kind : new int[] {CRASH_WALL, CRASH_WALL_SPEED, CRASH_WALL_NO_FUEL, CRASH_WALL_ANGLE,
                CRASH_TARGET, CRASH_TREASURE, CRASH_UNIVERSE, CRASH_UNKNOWN, CRASH_WORMHOLE, NOT_A_CRASH}) {
                a.status &= ~Obj.KILLED;
                quiet(a);
                s.crashes.playerCrashed(a, ms(kind), 1);
                got.add(heard(a));
            }
            a.status &= ~Obj.KILLED;
            quiet(a);
            MoveState m = ms(CRASH_CANNON);
            m.cannon = 0;
            s.crashes.playerCrashed(a, m, 1);
            List<String> cannon = heard(a);
            Simulation s2 = CannonsTest.sim(FRAME, "__");
            Player b = ready(s2, "B", 0, CannonsTest.CX, CannonsTest.CY);
            b.used |= Has.SHIELD | Has.EMERGENCY_SHIELD;
            quiet(b);
            s2.crashes.playerCrashed(b, m, 1);
            List<String> shielded = heard(b);
            quiet(b);
            s2.crashes.cannonDies(0, Obj.NO_ID);
            assertAll(
                () -> assertEquals(List.of(List.of("PLAYER_HIT_WALL:100"), List.of("PLAYER_HIT_WALL:100"),
                    List.of("PLAYER_HIT_WALL:100"), List.of("PLAYER_HIT_WALL:100"), List.of("PLAYER_HIT_WALL:100"),
                    List.of("PLAYER_HIT_WALL:100"), List.of("PLAYER_HIT_WALL:100"), List.of("PLAYER_HIT_WALL:100"),
                    List.of(), List.of()), got,
                    "wall, wall speed/no fuel/angle, target, treasure, universe, unknown: PLAYER_HIT_WALL; wormhole and no crash: none"),
                () -> assertEquals(List.of("PLAYER_HIT_CANNON:100", "CANNON_EXPLOSION:100"), cannon,
                    "an unshielded ship hits the cannon, which explodes"),
                () -> assertEquals(List.of("CANNON_EXPLOSION:100"), shielded, "a fully shielded ship only destroys it"),
                () -> assertEquals(List.of(), heard(b), "a dead cannon does not explode again"));
        }

        @Test
        @DisplayName("a survived wall bounce plays PLAYER_BOUNCED (walls.c Move_player)")
        void bounce() {
            Simulation s = MoverPlayerTest.sim("", MoverPlayerTest.RIGHT_WALL);
            Player p = MoverPlayerTest.headOn(s);
            quiet(p);
            s.mover.movePlayer(p);
            assertEquals(List.of("PLAYER_BOUNCED:100"), heard(p), "the bouncing ship hears it");
        }
    }

    @Nested
    @DisplayName("game flow")
    class Flow {
        static final String[] WORMS_3 = {
            "xxxxxxxxxxx",
            "x         x",
            "x         x",
            "x    @    x",
            "x         x",
            "x @  _  @ x",
            "x         x",
            "x    (    x",
            "x         x",
            "x _     _ x",
            "xxxxxxxxxxx",
        };

        @Test
        @DisplayName("a wormhole plays WORM_HOLE where the ship leaves and where it arrives (update.c)")
        void wormhole() {
            Simulation s = ShipFx.sim(ShipFx.C_FRAMES, WORMS_3);
            Player p = ShipsWarpTest.warping(s, 5, 0, ShipsWarpTest.seed(false));
            Player src = TestWorlds.join(s, "src", 0);
            Player dst = TestWorlds.join(s, "dst", 0);
            p.initPixels(87.5, 192.5);
            src.initPixels(87.5, 192.5);
            dst.initPixels(297.5, 192.5);
            quiet(p, src, dst);
            s.ships.warp(p);
            assertAll(
                () -> assertEquals(3, p.wormHoleDest, "(the ship goes to the wormhole at (297.5, 192.5))"),
                () -> assertEquals(List.of("WORM_HOLE:100"), heard(src), "a player at the entry hears the departure at 100"),
                () -> assertEquals(List.of("WORM_HOLE:100"), heard(dst), "a player at the exit hears the arrival at 100"));
        }

        @Test
        @DisplayName("a human joining plays START to every human, itself included; robots neither play nor hear it "
            + "(netserver.c Handle_login, then Receive_audio_request's sound_player_onoff)")
        void start() {
            Simulation s = sim(FRAME, 20, 20, "___");
            Player a = TestWorlds.join(s, "A", 0);
            quiet(a);
            Player r = s.ships.join("R", 0, true);
            Player b = TestWorlds.join(s, "B", 0);
            assertAll(
                () -> assertEquals(List.of("START:100"), heard(a), "a player already in the game hears the human newcomer only"),
                () -> assertEquals(List.of("START:100"), heard(b), "the newcomer hears its own start at full volume"),
                () -> assertEquals(List.of(), heard(r), "robots hear nothing"));
        }

        @Test
        @DisplayName("changing home plays CHANGE_HOME to everyone, only when the home changed (event.c)")
        void changeHome() {
            Simulation s = ShipFx.sim(ShipFx.C_FRAMES, ShipsJoinTest.THREE);
            World w = s.world;
            Player a = s.ships.join("ann", 0);
            a.homeBase = 0;
            Player b = s.ships.join("bob", 0);
            b.homeBase = 2;
            a.initPixels(w.map.bases().get(0).x(), w.map.bases().get(0).y());
            quiet(a, b);
            s.keys.pressed(a, ControlKey.CHANGE_HOME);
            List<String> same = heard(b);
            a.initPixels(w.map.bases().get(1).x(), w.map.bases().get(1).y());
            s.keys.pressed(a, ControlKey.CHANGE_HOME);
            assertAll(
                () -> assertEquals(List.of(), same, "pressing on the own home changes nothing and plays nothing"),
                () -> assertEquals(List.of("CHANGE_HOME:100"), heard(b), "another player hears the change at full volume"),
                () -> assertEquals(List.of("CHANGE_HOME:100"), heard(a), "so does the changer"));
        }

        @Test
        @DisplayName("round ends play PLAYER_DRAW / PLAYER_WIN / TEAM_WIN / TEAM_DRAW to everyone (player.c)")
        void gameOver() {
            List<String> got = new ArrayList<>();
            for (int winner : new int[] {-1, -2, 0}) {
                Simulation s = sim(FRAME, 20, 20, "__");
                Player a = TestWorlds.join(s, "A", 0);
                quiet(a);
                s.rounds.individualGameOver(winner);
                got.add(heard(a).get(0)); // then the reset kills everyone: PLAYER_EXPLOSION, as C
            }
            for (int team : new int[] {1, -1}) {
                Simulation s = sim(FRAME, 20, 20, "__");
                Player a = TestWorlds.join(s, "A", 0);
                quiet(a);
                s.rounds.teamGameOver(team, "");
                got.add(heard(a).get(0));
            }
            assertEquals(List.of("PLAYER_DRAW:100", "PLAYER_WIN:100", "PLAYER_WIN:100", "TEAM_WIN:100", "TEAM_DRAW:100"),
                got, "draw, robots won, a player won, a team won, a team draw");
        }
    }
}
