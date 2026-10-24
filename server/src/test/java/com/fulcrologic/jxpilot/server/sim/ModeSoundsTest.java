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

import com.fulcrologic.jxpilot.common.map.Target;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ModeFx.ballOf;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.raceSim;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.ship;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.targetSim;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.teamSim;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.tow;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SoundSitesTest.heard;
import static com.fulcrologic.jxpilot.server.sim.SoundSitesTest.quiet;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The game-mode {@code sound_play_*} sites: balls (collision.c:492, player.c:1866, shot.c:1191, play.c:89),
 * targets (walls.c:1771), the race (player.c:1340) and asteroids (collision.c:1555, asteroid.c:246).
 */
@DisplayName("game-mode sound sites")
class ModeSoundsTest {
    @Nested
    @DisplayName("balls")
    class BallSounds {
        @Test
        @DisplayName("connecting plays CONNECT_BALL at the ship; reaching for the ball plays nothing")
        void connect() {
            Simulation s = teamSim("");
            World w = s.world;
            Objects o = w.objects;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, o.px[h] - 100, o.py[h]);
            Player far = ship(s, "F", 1, o.px[h] - 100, o.py[h] - 210);
            p.used |= Has.CONNECTOR;
            quiet(p, far);
            s.balls.connect(p);
            List<String> reaching = heard(p);
            p.initPixels(o.px[h] - 121, o.py[h]);
            s.balls.connect(p);
            assertAll(
                () -> assertEquals(List.of(), reaching, "starting the reach is silent"),
                () -> assertEquals(List.of("CONNECT_BALL:100"), heard(p), "the ship hears its connect at full volume"),
                // the far ship is 210 px from the ship's old spot and 21 px further along x: (int) hypot(21, 210)
                () -> assertEquals(List.of("CONNECT_BALL:" + Sounds.volume(21, 210, Sounds.range(far))), heard(far),
                    "played at the ship's position"));
        }

        @Test
        @DisplayName("dropping one of two balls plays DROP_BALL; dropping the last one is silent")
        void drop() {
            Simulation s = teamSim("");
            World w = s.world;
            int a = ballOf(w, 0);
            int b = ballOf(w, 2);
            Player p = ship(s, "P", 1, 300, 200);
            tow(w, p, a);
            tow(w, p, b);
            quiet(p);
            s.balls.detach(p, a);
            List<String> one = heard(p);
            quiet(p);
            s.balls.detach(p, b);
            assertAll(
                () -> assertEquals(List.of("DROP_BALL:100"), one, "a ball still connected: DROP_BALL"),
                () -> assertEquals(List.of(), heard(p), "no ball left: no sound (C clears HAS_BALL instead)"));
        }

        @Test
        @DisplayName("a recreated ball plays EXPLODE_BALL at the ball, unless NOEXPLOSION")
        void explode() {
            Simulation s = teamSim("");
            World w = s.world;
            Objects o = w.objects;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, o.px[h], o.py[h] - 210);
            quiet(p);
            s.balls.deleting(h);
            List<String> boom = heard(p);
            int q = ballOf(w, 0);
            o.status[q] |= Obj.NOEXPLOSION;
            quiet(p);
            s.balls.deleting(q);
            assertAll(
                () -> assertEquals(List.of("EXPLODE_BALL:60"), boom, "210 px from the ball: volume 60"),
                () -> assertEquals(List.of(), heard(p), "a quiet recreation plays nothing"));
        }

        @Test
        @DisplayName("destroying a team's treasure plays DESTROY_BALL to everyone at full volume")
        void destroyTreasure() {
            Simulation s = teamSim("");
            Player p = ship(s, "P", 1, 100, 100);
            Player v = ship(s, "V", 2, 650, 250);
            quiet(p, v);
            s.balls.punishTeam(p, 2, 0);
            assertAll(
                () -> assertEquals(List.of("DESTROY_BALL:100"), heard(p), "the cashier"),
                () -> assertEquals(List.of("DESTROY_BALL:100"), heard(v), "a far ship of the losing team"));
        }
    }

    @Test
    @DisplayName("a destroyed target plays DESTROY_TARGET at its block coordinates (C quirk), not at its pixels")
    void target() {
        Simulation s = targetSim("");
        World w = s.world;
        Target t = w.map.targets().get(1);
        Player p = ship(s, "P", 1, 100, 100);
        // on the target's pixel centre: 541 px from (bx, by), outside the 525 px range
        Player onIt = ship(s, "V", 2, (t.bx() + 0.5) * 35, (t.by() + 0.5) * 35);
        quiet(p, onIt);
        s.targets.damage(1, Obj.HEAT_SHOT, p.id, 1, 0, 0, 1, true, 0, 1f, -1);
        assertAll(
            () -> assertEquals(List.of("DESTROY_TARGET:" + Sounds.volume(100 - t.bx(), 100 - t.by(), Sounds.range(p))),
                heard(p), "heard as if the target were at (bx, by) pixels"),
            () -> assertEquals(List.of(), heard(onIt), "a ship on the target hears nothing"));
    }

    @Test
    @DisplayName("the first finisher plays PLAYER_WIN to everyone; a later position plays nothing")
    void raceWin() {
        Simulation s = raceSim("");
        Player a = ship(s, "A", 0, 100, 100);
        Player b = ship(s, "B", 0, 600, 300);
        Player c = ship(s, "C", 0, 300, 100);
        a.status |= Obj.GAME_OVER | Obj.FINISH;
        quiet(a, b, c);
        s.race.gameStatus();
        List<String> first = heard(b);
        Simulation s2 = raceSim("limitedLives: yes\nworldLives: 3\n");
        Player x = ship(s2, "X", 0, 100, 100);
        Player y = ship(s2, "Y", 0, 600, 300);
        ship(s2, "Z", 0, 300, 100);
        x.status |= Obj.GAME_OVER | Obj.RACE_OVER;
        y.status |= Obj.GAME_OVER | Obj.FINISH;
        quiet(x, y);
        s2.race.gameStatus();
        assertAll(
            // the race then ends and Race_game_over kills every ship, so explosions follow
            () -> assertEquals("PLAYER_WIN:100", first.get(0), "position 1: everyone hears it at full volume, first"),
            () -> assertEquals(1, first.stream().filter(e -> e.startsWith("PLAYER_WIN")).count(), "once"),
            () -> assertEquals(List.of(), heard(y), "position 2: no fanfare"));
    }

    @Nested
    @DisplayName("asteroids")
    class AsteroidSounds {
        @Test
        @DisplayName("a shot hit plays ASTEROID_HIT at the asteroid; spark, mine and debris hits are silent")
        void hit() {
            Simulation s = AsteroidsTest.sim("");
            World w = s.world;
            Objects o = w.objects;
            Player p = ship(s, "P", 0, 500, 290);
            s.asteroids.make(500, 500, 2, 0, 0f);
            obj(w, Obj.SHOT, p.id, 505, 500, 10, 0, 1, 20);
            w.grid.rebuild(o);
            quiet(p);
            s.asteroids.collide();
            List<String> shot = heard(p);
            Simulation q = AsteroidsTest.sim("");
            World qw = q.world;
            Player r = ship(q, "R", 0, 500, 290);
            q.asteroids.make(500, 500, 2, 0, 0f);
            obj(qw, Obj.SPARK, r.id, 505, 500, 0, 0, 1, 20);
            obj(qw, Obj.MINE, r.id, 495, 500, 0, 0, 1, 20);
            obj(qw, Obj.DEBRIS, r.id, 500, 505, 0, 0, 1, 20);
            qw.grid.rebuild(qw.objects);
            quiet(r);
            q.asteroids.collide();
            assertAll(
                () -> assertEquals(List.of("ASTEROID_HIT:60"), shot, "210 px from the asteroid: volume 60"),
                () -> assertEquals(List.of(), heard(r), "no sound for the other kinds"));
        }

        @Test
        @DisplayName("breaking an asteroid plays ASTEROID_BREAK at it")
        void breaks() {
            Simulation s = AsteroidsTest.sim("");
            Player p = ship(s, "P", 0, 500, 290);
            int a = s.asteroids.make(500, 500, 2, 0, 0f);
            quiet(p);
            s.asteroids.broken(a);
            assertEquals(List.of("ASTEROID_BREAK:60"), heard(p), "210 px away: volume 60");
        }
    }
}
