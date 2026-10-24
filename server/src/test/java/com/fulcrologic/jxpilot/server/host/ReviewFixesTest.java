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
package com.fulcrologic.jxpilot.server.host;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.host.CommandsTest.reply;
import static com.fulcrologic.jxpilot.server.host.HostFx.TEAMS;
import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static com.fulcrologic.jxpilot.server.host.HostFx.only;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fixes of the server-commands review (M2, M3/M7, M5, L1-L3, L6, L10). */
@DisplayName("server-commands review fixes")
class ReviewFixesTest {
    @Nested
    @DisplayName("M2: /team applies Pick_startpos's tail")
    class TeamTail {
        @Test
        @DisplayName("a ship not playing (dead, or paused: C's pause clears PLAYING) waits 3*FPS; a game-over one goes home")
        void tail() {
            CommandsTest.Fx f = new CommandsTest.Fx("teamPlay: yes\nreserveRobotTeam: no\n", TEAMS);
            int ann = enter(f.h, "Ann", 2);
            int bob = enter(f.h, "Bob", 0);
            Player a = f.p(ann);
            Player b = f.p(bob);
            int fps = f.w.params.fps;
            f.s.ships.pause(a, true);
            int pausedX = a.px;
            f.say(ann, "/team 1");
            b.status |= Obj.PLAYING | Obj.GAME_OVER;
            b.px += 7 * 64;
            b.vx = 3;
            int far = b.px;
            f.say(bob, "/team 1");
            int homeX = b.px;
            int homeY = b.py;
            f.s.ships.goHome(b);
            assertAll(
                () -> assertEquals(3 * fps, a.count, "paused: RECOVERY_DELAY count replaces the pause's 10 s"),
                () -> assertEquals(pausedX, a.px, "and stays where it paused (Go_home comes when it unpauses)"),
                () -> assertTrue(far != homeX, "the game-over ship left where it was"),
                () -> assertEquals(0.0, b.vx, 1e-9, "at rest"),
                () -> assertEquals(homeX, b.px, "on its new home base (a second Go_home does not move it)"),
                () -> assertEquals(homeY, b.py, "on its new home base (y)"),
                () -> assertEquals(1, f.w.map.bases().get(b.homeBase).team(), "which is a base of team 1"));
        }
    }

    @Nested
    @DisplayName("M3/M7: alliance marks are resent when they all may have changed")
    class Marks {
        @Test
        @DisplayName("/set announceAlliances resends every viewer every player's mark, now as the alliance number")
        void announce() {
            CommandsTest.Fx f = new CommandsTest.Fx("allowAlliances: yes\n", THREE);
            int ann = enter(f.h, "Ann", 0);
            int bob = enter(f.h, "Bob", 0);
            int cid = enter(f.h, "Cid", 0);
            f.say(ann, "/ally invite Bob");
            f.say(bob, "/ally accept Ann");
            f.op(cid);
            HostFx.Wire c = new HostFx.Wire();
            f.h.initialState(cid, c.out);
            c.take();
            f.h.drainEvents(cid, c.out);
            c.take();
            f.say(cid, "/set announceAlliances yes");
            f.h.drainEvents(cid, c.out);
            List<String> marks = only(c.take(), "ALLIANCE");
            char id = (char) ('0' + f.p(ann).alliance);
            assertAll(
                () -> assertTrue(marks.contains("ALLIANCE " + ann + " '" + id + "'"), "Ann's alliance number: " + marks),
                () -> assertTrue(marks.contains("ALLIANCE " + bob + " '" + id + "'"), "Bob's alliance number"),
                () -> assertTrue(marks.contains("ALLIANCE " + cid + " ' '"), "Cid is in none"),
                () -> assertEquals(3 + 2, marks.size(), "Cid's own event resends all three; Ann's and Bob's one each"));
        }
    }

    @Nested
    @DisplayName("M5: allowShields on skips tanks (tuner.c:103-115)")
    class TankShields {
        @Test
        @DisplayName("a tank gets no shields when they are allowed again; off gives everyone 2 s, tanks too")
        void tanks() {
            TuningTest.Fx f = new TuningTest.Fx("");
            Player p = f.s.ships.join("Ann", 0);
            f.s.tanks.add(p, 1000);
            f.s.tanks.detach(p);
            Player tank = null;
            for (int k = 0; k < f.w.numPlayers; k++) {
                if (f.w.playerAt(k).tank) {
                    tank = f.w.playerAt(k);
                }
            }
            Player t = tank;
            f.t.tune("allowShields", "no");
            int offTime = t.shieldTime;
            t.have &= ~Has.SHIELD;
            t.used &= ~Has.SHIELD;
            t.shieldTime = 5;
            f.t.tune("allowShields", "yes");
            assertAll(
                () -> assertEquals(2 * f.w.params.fps, offTime, "off: 2 s for the tank as well"),
                () -> assertEquals(0, t.have & Has.SHIELD, "the tank gets no shield"),
                () -> assertEquals(0, t.used & Has.SHIELD, "nor raises one"),
                () -> assertEquals(5, t.shieldTime, "its shield time is left alone"),
                () -> assertTrue((p.have & Has.SHIELD) != 0, "the player gets one"));
        }
    }

    @Nested
    @DisplayName("L1-L3, L6: values as C keeps them")
    class Values {
        @Test
        @DisplayName("L1: Set_initial_resources, Move_init and Tune_asteroid_prob limits show in /get")
        void limits() {
            TuningTest.Fx f = new TuningTest.Fx("");
            f.t.tune(Opt.MAX_ARMOR.optionName(), "500");
            String maxArmor = f.get(Opt.MAX_ARMOR.optionName());
            f.t.tune(Opt.MAX_TANKS.optionName(), "-3");
            String maxTanks = f.get(Opt.MAX_TANKS.optionName());
            f.t.tune(Opt.MAX_MINES.optionName(), "10");
            f.t.tune(Opt.INITIAL_MINES.optionName(), "50");
            String initialMines = f.get(Opt.INITIAL_MINES.optionName());
            f.t.tune(Opt.INITIAL_FUEL.optionName(), "-5");
            String initialFuel = f.get(Opt.INITIAL_FUEL.optionName());
            f.t.tune(Opt.MAX_AFTERBURNERS.optionName(), "20");
            String maxAfterburners = f.get(Opt.MAX_AFTERBURNERS.optionName());
            f.t.tune(Opt.PLAYER_WALL_BOUNCE_BRAKE_FACTOR.optionName(), "3");
            f.t.tune(Opt.MAX_SHIELDED_PLAYER_WALL_BOUNCE_ANGLE.optionName(), "-10");
            f.t.tune(Opt.MAX_OBJECT_WALL_BOUNCE_SPEED.optionName(), "1e9");
            f.t.tune(Opt.ASTEROID_CONCENTRATOR_RADIUS.optionName(), "0");
            f.t.tune(Opt.ASTEROID_CONCENTRATOR_PROB.optionName(), "2");
            assertAll(
                () -> assertEquals("99", maxArmor, "maxArmor up to MAX_ARMOR 99"),
                () -> assertEquals("0", maxTanks, "maxTanks at least 0"),
                () -> assertEquals("10", initialMines, "the initial amount up to the limit"),
                () -> assertEquals("0", initialFuel, "and at least 0"),
                () -> assertEquals("15", maxAfterburners, "MAX_AFTERBURNER 15"),
                () -> assertEquals(1.0, f.w.options.getDouble(Opt.PLAYER_WALL_BOUNCE_BRAKE_FACTOR), "brake factor up to 1"),
                () -> assertEquals(0.0, f.w.options.getDouble(Opt.MAX_SHIELDED_PLAYER_WALL_BOUNCE_ANGLE), "angle at least 0"),
                () -> assertEquals(f.w.params.hypotenuse, f.w.options.getDouble(Opt.MAX_OBJECT_WALL_BOUNCE_SPEED), 1e-6,
                    "speed up to the world's hypotenuse"),
                () -> assertEquals(1, f.w.options.getInt(Opt.ASTEROID_CONCENTRATOR_RADIUS), "radius at least 1"),
                () -> assertEquals(1.0, f.w.options.getDouble(Opt.ASTEROID_CONCENTRATOR_PROB), "probability up to 1"));
        }

        @Test
        @DisplayName("L2: /get gameDuration shows C's variable, -1 after the game is over")
        void gameDuration() {
            TuningTest.Fx f = new TuningTest.Fx("gameDuration: 5\n");
            f.w.gameDuration = -1;
            assertEquals("-1", f.get("gameDuration"), "Game_Over's -1");
        }

        @Test
        @DisplayName("L3: a name with whitespace is no option, for /set and /get (closes the '/get  mapData' bypass)")
        void whitespace() {
            CommandsTest.Fx c = new CommandsTest.Fx("", THREE);
            int ann = enter(c.h, "Ann", 0);
            TuningTest.Fx f = new TuningTest.Fx("");
            assertAll(
                () -> assertEquals(Tuning.NO_OPTION, f.t.tune(" shots", "1"), "leading space"),
                () -> assertEquals(Tuning.NO_OPTION, f.t.tune("shots\t", "1"), "trailing tab"),
                () -> assertEquals("#" + Tuning.NO_OPTION, f.get(" mapData"), "get: leading space"),
                () -> assertEquals("#" + Tuning.NO_OPTION, f.get("map Data"), "get: inner space"),
                () -> assertEquals(List.of(reply(ann, "No option named  mapData.")), c.say(ann, "/get  mapData"),
                    "the command refuses it as unknown"));
        }

        @Test
        @DisplayName("L6: seconds wrap in int as C's ival * FPS; numbers parse as glibc (hex), non-finite is invalid")
        void numbers() {
            TuningTest.Fx f = new TuningTest.Fx("");
            int fps = f.w.params.fps;
            f.t.tune("maxPauseTime", "5");
            int five = f.w.options.getFrames(Opt.MAX_PAUSE_TIME);
            String fiveShown = f.get("maxPauseTime");
            f.t.tune("maxPauseTime", "40000000");
            int wrapped = f.w.options.getFrames(Opt.MAX_PAUSE_TIME);
            f.t.tune("maxPauseTime", "0x10");
            int hex = f.w.options.getFrames(Opt.MAX_PAUSE_TIME);
            f.t.tune("gravity", "0x1p-2");
            double hexFloat = f.w.options.getDouble(Opt.GRAVITY);
            assertAll(
                () -> assertEquals(5 * fps, five, "5 s is exactly 5 * FPS frames"),
                () -> assertEquals("5", fiveShown, "and shows as 5"),
                () -> assertEquals((int) (40000000L * fps), wrapped, "40000000 * FPS wraps in int"),
                () -> assertEquals(16 * fps, hex, "strtol hex"),
                () -> assertEquals(0.25, hexFloat, 1e-12, "strtod hex float"),
                () -> assertEquals(Tuning.INVALID, f.t.tune("gravity", "inf"), "inf is refused"),
                () -> assertEquals(Tuning.INVALID, f.t.tune("gravity", "NaN"), "nan is refused"),
                () -> assertEquals(Tuning.INVALID, f.t.tune("gravity", "1e999"), "an overflow to infinity is refused"),
                () -> assertEquals(0.25, f.w.options.getDouble(Opt.GRAVITY), 1e-12, "a refusal keeps the value"));
        }
    }

    @Nested
    @DisplayName("L10: /password brute-force cooldown (JXPilot)")
    class PasswordCooldown {
        @Test
        @DisplayName("3 wrong tries start 10 s, every further 3 double it up to 5 min; the password resets it")
        void cooldown() {
            CommandsTest.Fx f = new CommandsTest.Fx("", THREE);
            f.w.options.force(Opt.PASSWORD, "pw");
            int ann = enter(f.h, "Ann", 0);
            int fps = f.w.params.fps;
            Player a = f.p(ann);
            String wrong = reply(ann, "Wrong.");
            List<String> first = f.say(ann, "/password a");
            f.say(ann, "/password b");
            List<String> third = f.say(ann, "/password c");
            List<String> locked = f.say(ann, "/password pw");
            f.w.frames += 10L * fps - 1;
            List<String> lastFrame = f.say(ann, "/password pw");
            f.w.frames += 1;
            f.say(ann, "/password x");
            f.say(ann, "/password y");
            f.say(ann, "/password z");
            List<String> doubled = f.say(ann, "/password pw");
            for (int i = 0; i < 6; i++) {
                f.w.frames += 400L * fps;
                f.say(ann, "/password x");
                f.say(ann, "/password y");
                f.say(ann, "/password z");
            }
            List<String> capped = f.say(ann, "/password pw");
            f.w.frames += 300L * fps;
            List<String> right = f.say(ann, "/password pw");
            boolean oper = a.isOperator;
            List<String> afterReset = f.say(ann, "/password a");
            f.say(ann, "/password b");
            f.say(ann, "/password c");
            List<String> relocked = f.say(ann, "/password pw");
            assertAll(
                () -> assertEquals(List.of(wrong), first, "a wrong try is C's reply"),
                () -> assertEquals(List.of(wrong), third, "so is the third"),
                () -> assertEquals(List.of(reply(ann, "Too many wrong passwords.  Try again in 10 seconds.")), locked,
                    "then even the password is not checked"),
                () -> assertEquals(List.of(reply(ann, "Too many wrong passwords.  Try again in 1 seconds.")), lastFrame,
                    "the seconds left round up"),
                () -> assertEquals(List.of(reply(ann, "Too many wrong passwords.  Try again in 20 seconds.")), doubled,
                    "the next three double it"),
                () -> assertEquals(List.of(reply(ann, "Too many wrong passwords.  Try again in 300 seconds.")), capped,
                    "up to 5 minutes"),
                () -> assertEquals(List.of(reply(ann, "You got operator status.")), right, "after it the password works"),
                () -> assertTrue(oper, "operator"),
                () -> assertEquals(List.of(reply(ann, "Wrong.  You lost operator status.")), afterReset,
                    "a wrong try still costs the status"),
                () -> assertEquals(List.of(reply(ann, "Too many wrong passwords.  Try again in 10 seconds.")), relocked,
                    "the password reset the length to 10 s"),
                () -> assertFalse(a.isOperator, "not an operator any more"));
        }
    }
}
