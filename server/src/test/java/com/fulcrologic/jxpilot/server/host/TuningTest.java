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
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.addRobots;
import static com.fulcrologic.jxpilot.server.host.HostFx.events;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run-time option changes: C Tune_option, Get_option_value and the tuners. */
@DisplayName("Tuning (C Tune_option, Get_option_value, tuners)")
class TuningTest {
    static final class Fx {
        final Simulation s;
        final World w;
        final List<Opt> changed = new ArrayList<>();
        final Tuning t;

        Fx(String extra, String... rows) {
            s = sim(extra, rows.length == 0 ? THREE : rows);
            w = s.world;
            t = new Tuning(s, changed::add);
        }

        String get(String name) {
            StringBuilder sb = new StringBuilder();
            int r = t.get(name, sb);
            return r == Tuning.OK ? sb.toString() : "#" + r;
        }
    }

    @Nested
    @DisplayName("the tuner table")
    class Table {
        @Test
        @DisplayName("C's tuners: 256 options can change at run time; examples of each kind")
        void table() {
            int changeable = 0;
            for (Opt o : Opt.values()) {
                if (Tuning.tuner(o) != Tuning.Tuner.NONE) {
                    changeable++;
                }
            }
            final int n = changeable;
            assertAll(
                () -> assertEquals(256, n, "C's table has 293 options, 37 of them tuner_none"),
                () -> assertEquals(Tuning.Tuner.GRAVITY, Tuning.tuner(Opt.GRAVITY), "Compute_gravity"),
                () -> assertEquals(Tuning.Tuner.NONE, Tuning.tuner(Opt.MAP_WIDTH), "tuner_none"),
                () -> assertEquals(Tuning.Tuner.NONE, Tuning.tuner(Opt.BIND), "JXPilot network options"),
                () -> assertEquals(Tuning.Tuner.DUMMY, Tuning.tuner(Opt.find("ignore20MaxFPS")), "tuner_dummy"),
                () -> assertEquals(Tuning.Tuner.DUMMY, Tuning.tuner(Opt.find("pLockServer")), "tuner_plock: value only"),
                () -> assertEquals(Tuning.Tuner.MOVE_INIT, Tuning.tuner(Opt.find("shotsWallBounce")), "Move_init"),
                () -> assertEquals(Tuning.Tuner.INITIAL_RESOURCES, Tuning.tuner(Opt.find("maxArmor")),
                    "Set_initial_resources"));
        }
    }

    @Nested
    @DisplayName("tune: parsing")
    class Parse {
        @Test
        @DisplayName("results per type, C's prefix parsing, and the value stored")
        void parse() {
            Fx f = new Fx("");
            assertAll(
                () -> assertEquals(Tuning.NO_OPTION, f.t.tune("nope", "1"), "unknown"),
                () -> assertEquals(Tuning.UNDEFINED, f.t.tune("mapWidth", "1"), "tuner_none"),
                () -> assertEquals(Tuning.UNDEFINED, f.t.tune("gravityPoint", "1,1"), "a position cannot be set"),
                () -> assertEquals(Tuning.INVALID, f.t.tune("shots", "x1"), "int without a digit"),
                () -> assertEquals(Tuning.OK, f.t.tune("shots", "0x10"), "hex"),
                () -> assertEquals(16, f.w.params.maxPlayerShots, "16"),
                () -> assertEquals(Tuning.OK, f.t.tune("shots", "12abc"), "a numeric prefix"),
                () -> assertEquals("12", f.get("maxPlayerShots"), "12"),
                () -> assertEquals(Tuning.INVALID, f.t.tune("shotsGravity", "maybe"), "not a bool"),
                () -> assertEquals(Tuning.INVALID, f.t.tune("shotsGravity", "tru"), "true needs 4 letters"),
                () -> assertEquals(Tuning.OK, f.t.tune("shotsGravity", "nope"), "starts with no"),
                () -> assertFalse(f.w.params.shotsGravity, "off"),
                () -> assertEquals(Tuning.OK, f.t.tune("shotsGravity", "ONward"), "starts with on"),
                () -> assertTrue(f.w.params.shotsGravity, "on"),
                () -> assertEquals(Tuning.OK, f.t.tune("shotsGravity", "offish"), "starts with off"),
                () -> assertEquals(Tuning.OK, f.t.tune("shotsGravity", "YESS"), "starts with yes"),
                () -> assertEquals(Tuning.INVALID, f.t.tune("shotSpeed", "fast"), "real without a digit"),
                () -> assertEquals(Tuning.OK, f.t.tune("shotSpeed", "1e1x"), "a real prefix"),
                () -> assertEquals(10f, f.w.params.shotSpeed, "10"),
                () -> assertEquals(Tuning.INVALID, f.t.tune("maxPauseTime", "soon"), "seconds without a digit"),
                () -> assertEquals(Tuning.OK, f.t.tune("maxPauseTime", "2.9"), "seconds are an int"),
                () -> assertEquals(2 * 60, f.w.params.maxPauseTime, "2 s at 60 fps"),
                () -> assertEquals(Tuning.OK, f.t.tune("password", "x y"), "a string as given"),
                () -> assertEquals("x y", f.w.options.getString(Opt.PASSWORD), "stored"));
        }

        @Test
        @DisplayName("after a change the listener is told the option; refusals tell nothing")
        void listener() {
            Fx f = new Fx("");
            f.t.tune("mapWidth", "3");
            f.t.tune("shots", "x");
            f.t.tune("shots", "3");
            assertEquals(List.of(Opt.MAX_PLAYER_SHOTS), f.changed, "only the change");
        }
    }

    @Nested
    @DisplayName("get")
    class Get {
        @Test
        @DisplayName("C's formats per type and the errors")
        void get() {
            Fx f = new Fx("shotMass: 0.1\nmaxPauseTime: 2.5\n");
            assertAll(
                () -> assertEquals("256", f.get("shots"), "int"),
                () -> assertEquals("0.1", f.get("shotMass"), "real %g of the float"),
                () -> assertEquals("true", f.get("shotsGravity"), "bool"),
                () -> assertEquals("Arena", f.get("mapName"), "string"),
                () -> assertEquals("#" + Tuning.NO_VALUE, f.get("serverHost"), "a string without a value"),
                () -> assertEquals("2", f.get("maxPauseTime"), "seconds: (int)(2.5 * 60) / 60"),
                () -> assertEquals("#" + Tuning.NOT_VISIBLE, f.get("robotFile"), "invisible"),
                () -> assertEquals("#" + Tuning.NO_OPTION, f.get("zz"), "unknown"),
                () -> assertEquals("#" + Tuning.UNDEFINED, f.get("gravityPoint"), "a position"),
                () -> assertEquals("0", f.get("maxRobots"), "an int option as set"));
        }

        @Test
        @DisplayName("the variables C's start-up code changes: maxRobots -1, minRobots -1, an out-of-range robotTeam")
        void startup() {
            Fx f = new Fx("maxRobots: -1\nminRobots: -1\nrobotTeam: 12\n");
            assertAll(
                () -> assertEquals("3", f.get("maxRobots"), "one per base"),
                () -> assertEquals("3", f.get("minRobots"), "maxRobots"),
                () -> assertEquals("0", f.get("robotTeam"), "Robot_init's 0"));
        }
    }

    @Nested
    @DisplayName("tuners")
    class Tuners {
        @Test
        @DisplayName("maxRobots: robots beyond it are kicked at once; minRobots follows; -1 is one per base")
        void maxRobots() {
            Fx f = new Fx("maxRobots: 3\nminRobots: 3\n");
            addRobots(f.s, 3);
            long from = f.w.events.seq();
            f.t.tune("robots", "1");
            List<String> got = HostFx.only(events(f.w, from), "MESSAGE");
            int robots = f.s.robots.numRobots();
            f.t.tune("maxRobots", "-1");
            assertAll(
                () -> assertEquals(1, robots, "two robots left"),
                () -> assertEquals(2, got.size(), "each announced: " + got),
                () -> assertTrue(got.get(0).endsWith("upset the gods and was kicked out of the game."), "C Robot_delete(-1, true)"),
                () -> assertEquals(1, f.w.params.minRobots, "minRobots dropped to 1"),
                () -> assertEquals("1", f.get("minRobots"), "and /get shows it"),
                () -> assertEquals(3, f.w.params.maxRobots, "-1: the number of bases"),
                () -> assertEquals("3", f.get("maxRobots"), "shown"));
        }

        @Test
        @DisplayName("minRobots: -1 is maxRobots; above maxRobots raises it")
        void minRobots() {
            Fx f = new Fx("maxRobots: 1\nminRobots: 0\n");
            f.t.tune("minRobots", "2");
            int max = f.w.params.maxRobots;
            f.t.tune("minRobots", "-1");
            assertAll(
                () -> assertEquals(2, max, "maxRobots rose to 2"),
                () -> assertEquals(2, f.w.params.minRobots, "-1 is maxRobots"),
                () -> assertEquals("2", f.get("minRobots"), "shown"));
        }

        @Test
        @DisplayName("shipMass and maxPlayerShots reach the players in the game")
        void players() {
            Fx f = new Fx("");
            Player p = f.s.ships.join("Ann", 0);
            f.t.tune("shipMass", "33");
            f.t.tune("shots", "7");
            assertAll(
                () -> assertEquals(33f, p.emptyMass, "empty mass"),
                () -> assertEquals(7, p.shotMax, "shot limit"));
        }

        @Test
        @DisplayName("allowShields: off gives players 2 s of shield time; on gives shields (up unless firing)")
        void shields() {
            Fx f = new Fx("");
            Player p = f.s.ships.join("Ann", 0);
            Player q = f.s.ships.join("Bob", 0);
            f.t.tune("allowShields", "no");
            int time = p.shieldTime;
            boolean defHave = (f.w.params.defHave & Has.SHIELD) != 0;
            p.used &= ~Has.SHIELD;
            q.used = (q.used | Has.SHOT) & ~Has.SHIELD;
            f.t.tune("allowShields", "yes");
            assertAll(
                () -> assertEquals(2 * 60, time, "2 seconds to get to safety"),
                () -> assertFalse(defHave, "shields leave DEF_HAVE"),
                () -> assertTrue((p.used & Has.SHIELD) != 0, "shields raised"),
                () -> assertTrue((q.used & Has.SHIELD) == 0, "not for a player firing"),
                () -> assertTrue((q.have & Has.SHIELD) != 0, "but it has them"),
                () -> assertEquals(0, p.shieldTime, "shield time 0"),
                () -> assertTrue((f.w.params.defHave & Has.SHIELD) != 0, "back in DEF_HAVE"),
                () -> assertTrue((f.w.params.defUsed & Has.SHIELD) == 0, "DEF_USED keeps the loss (C: &= DEF_HAVE only)"));
        }

        @Test
        @DisplayName("playerStartsShielded cannot be turned off while shields are allowed")
        void startsShielded() {
            Fx f = new Fx("allowShields: no\n");
            f.t.tune("playerStartsShielded", "no");
            boolean offWithoutShields = f.w.params.playerStartsShielded;
            Fx g = new Fx("");
            g.t.tune("playerStartsShielded", "no");
            assertAll(
                () -> assertFalse(offWithoutShields, "off when shields are not allowed"),
                () -> assertTrue(g.w.params.playerStartsShielded, "forced on with shields"),
                () -> assertEquals("true", g.get("playerStartsShielded"), "shown"));
        }

        @Test
        @DisplayName("allowPlayerKilling off clears the killing shots for good (C KILLING_SHOTS); masks wait for Move_init")
        void killing() {
            Fx f = new Fx("");
            int cannonMask = f.w.params.objCannonMask;
            f.t.tune("allowPlayerKilling", "no");
            int afterOff = f.w.params.killingShots;
            int maskAfterOff = f.w.params.objCannonMask;
            f.t.tune("allowPlayerKilling", "yes");
            int afterOn = f.w.params.killingShots;
            f.t.tune("shotsWallBounce", "no");
            int maskAfterMoveInit = f.w.params.objCannonMask;
            assertAll(
                () -> assertEquals(0, afterOff, "no killing shots"),
                () -> assertEquals(cannonMask, maskAfterOff, "the cannon mask is Move_init's, unchanged"),
                () -> assertEquals(0, afterOn, "they stay cleared"),
                () -> assertTrue(f.w.params.playerKillings, "though player killing is on"),
                () -> assertEquals(Obj.MINE | Obj.SHOT | Obj.PULSE | Obj.SMART_SHOT | Obj.TORPEDO | Obj.HEAT_SHOT
                    | Obj.ASTEROID | (f.w.params.cannonsUseItems ? Obj.ITEM : 0), maskAfterMoveInit,
                    "Move_init rebuilds the masks without them"),
                () -> assertTrue(maskAfterMoveInit != cannonMask, "they were in it before"));
        }

        @Test
        @DisplayName("worldLives: below 0 is 0; with limitedLives everyone is reset and a finished game restarts")
        void worldLives() {
            Fx f = new Fx("limitedLives: yes\nworldLives: 3\n");
            Player p = f.s.ships.join("Ann", 0);
            p.life = 1;
            f.w.gameDuration = -1;
            f.t.tune("worldLives", "-4");
            assertAll(
                () -> assertEquals("0", f.get("worldLives"), "0"),
                () -> assertEquals(0, f.w.params.worldLives, "used"),
                () -> assertEquals(0f, f.w.gameDuration, "the game restarts"),
                () -> assertEquals(0, p.life, "the player is reset with the new lives"));
        }

        @Test
        @DisplayName("allowAlliances follows C's tuner: the rules bit changes only at a rules update; off dissolves then")
        void alliances() {
            Fx f = new Fx("");
            Player a = f.s.ships.join("Ann", 0);
            Player b = f.s.ships.join("Bob", 0);
            f.s.alliances.invite(a, b);
            f.s.alliances.accept(b, a);
            f.t.tune("allowAlliances", "no");
            int countAfterOff = f.s.alliances.count();
            boolean enabledAfterOff = f.s.alliances.enabled;
            f.t.tune("allowPlayerCrashes", "yes");
            boolean enabledAfterRules = f.s.alliances.enabled;
            int countAfterRules = f.s.alliances.count();
            f.t.tune("allowAlliances", "no");
            int countAfterSecondOff = f.s.alliances.count();
            f.t.tune("alliances", "yes");
            boolean enabledAfterOn = f.s.alliances.enabled;
            f.t.tune("allowShields", "yes");
            Fx team = new Fx("teamPlay: yes\n", HostFx.TEAMS);
            team.t.tune("allowAlliances", "yes");
            assertAll(
                () -> assertTrue(enabledAfterOff, "outside team play C's tuner leaves the rules bit set"),
                () -> assertEquals(1, countAfterOff, "so nothing is dissolved"),
                () -> assertFalse(enabledAfterRules, "a rules update (Set_world_rules) clears it"),
                () -> assertEquals(1, countAfterRules, "and keeps the alliance"),
                () -> assertEquals(0, countAfterSecondOff, "the tuner with the bit clear dissolves it"),
                () -> assertFalse(enabledAfterOn, "turning it on waits for a rules update"),
                () -> assertTrue(f.s.alliances.enabled, "allowShields' tuner updates the rules"),
                () -> assertFalse(team.s.alliances.enabled, "never in team play"));
        }

        @Test
        @DisplayName("wormTime sets every countdown; cannonSmartness is limited to 0..3; gameDuration")
        void misc() {
            Fx f = new Fx("", "xxxxxxx", "x _ _ x", "x @ @ x", "xxxxxxx");
            f.t.tune("wormTime", "2");
            int countdown = f.w.wormCountdown[0];
            f.t.tune("wormTime", "0");
            int off = f.w.wormCountdown[0];
            f.t.tune("cannonSmartness", "9");
            f.t.tune("gameDuration", "5");
            assertAll(
                () -> assertEquals(120, countdown, "2 s at 60 fps"),
                () -> assertEquals(Tuning.WORMCOUNT, off, "WORMCOUNT without wormTime"),
                () -> assertEquals("3", f.get("cannonSmartness"), "limited to 3"),
                () -> assertEquals(3, f.w.params.cannonSmartness, "used"),
                () -> assertEquals(5f, f.w.gameDuration, "the game's duration"));
        }

        @Test
        @DisplayName("gravity recomputes the field; Set_misc_item_limits clamps; names are fixed like C")
        void gravityLimitsNames() {
            Fx f = new Fx("gravity: 0\n");
            double before = f.w.gravityY(2, 2);
            f.t.tune("gravity", "-1");
            f.t.tune("movingItemProb", "2");
            f.t.tune("itemConcentratorRadius", "0");
            f.t.tune("asteroidMaxItems", "-3");
            f.t.tune("robotRealName", "a b\tc");
            f.t.tune("robotHostName", ".bad");
            f.t.tune("tankHostName", "a..b-c-");
            assertAll(
                () -> assertEquals(0.0, before, "no gravity"),
                () -> assertEquals(1.0, Math.hypot(f.w.gravityX(2, 2), f.w.gravityY(2, 2)), 1e-6, "now of strength 1"),
                () -> assertEquals("1", f.get("movingItemProb"), "limited to 1"),
                () -> assertEquals("1", f.get("itemConcentratorRadius"), "at least 1"),
                () -> assertEquals("0", f.get("asteroidMaxItems"), "at least 0"),
                () -> assertEquals("axbxc", f.get("robotRealName"), "Fix_real_name"),
                () -> assertEquals("xxx.xxx", f.get("robotHostName"), "Fix_host_name: must start alphanumeric"),
                () -> assertEquals("ax.b-cx", f.get("tankHostName"), "Fix_host_name: a separator needs a non-separator after"));
        }

        @Test
        @DisplayName("fixRealName and fixHostName edges")
        void names() {
            assertAll(
                () -> assertEquals("X", Tuning.fixRealName(""), "empty"),
                () -> assertEquals("abcdefghijklmno", Tuning.fixRealName("abcdefghijklmnopq"), "15 characters"),
                () -> assertEquals("x~x", Tuning.fixRealName(" ~é"), "only printable non-space ASCII"),
                () -> assertEquals("xxx.xxx", Tuning.fixHostName(""), "empty"),
                () -> assertEquals("a.b-c", Tuning.fixHostName("a.b-c"), "fine"),
                () -> assertEquals(63, Tuning.fixHostName("a".repeat(80)).length(), "63 characters"));
        }

        @Test
        @DisplayName("only the parameters the change alters are copied (state the sim adjusted is kept)")
        void delta() {
            Fx f = new Fx("");
            f.w.params.minRobots = 77;
            f.t.tune("shotSpeed", "40");
            assertAll(
                () -> assertEquals(40f, f.w.params.shotSpeed, "changed"),
                () -> assertEquals(77, f.w.params.minRobots, "untouched"));
        }

        @Test
        @DisplayName("copyChanges: changed fields, array elements and strings; equal ones keep the live value")
        void copyChanges() {
            Params before = new Params();
            Params after = new Params();
            Params live = new Params();
            after.shotLife = 9;
            live.shotLife = 1;
            live.fps = 5;
            after.initialItems[3] = 4;
            live.initialItems[2] = 8;
            Tuning.copyChanges(before, after, live);
            assertAll(
                () -> assertEquals(9, live.shotLife, "changed field"),
                () -> assertEquals(5, live.fps, "unchanged field keeps the live value"),
                () -> assertEquals(4, live.initialItems[3], "changed element"),
                () -> assertEquals(8, live.initialItems[2], "unchanged element"),
                () -> assertSame(live.initialItems, live.initialItems, "the live array is kept"));
        }

        @Test
        @DisplayName("H1: robotTeam outside 0..9 becomes 0 (Robot_init's clamp); robots and Pick_team keep working")
        void robotTeamClamp() {
            Fx f = new Fx("teamPlay: yes\nmaxRobots: 2\n", HostFx.TEAMS);
            int r = f.t.tune("robotTeam", "10");
            String shown = f.get("robotTeam");
            f.s.robots.setEnabled(true);
            for (int i = 0; i < 200; i++) {
                f.s.robots.update();
            }
            f.t.tune("robotTeam", "-1");
            int neg = f.w.params.robotTeam;
            f.t.tune("robotTeam", "2");
            assertAll(
                () -> assertEquals(Tuning.OK, r, "accepted"),
                () -> assertEquals("0", shown, "shown as 0"),
                () -> assertEquals(0, neg, "-1 too"),
                () -> assertEquals(2, f.w.params.robotTeam, "a valid team is kept"),
                () -> assertTrue(f.s.robots.numRobots() > 0, "robots joined"),
                () -> assertTrue(f.s.ships.pickTeam(null) >= 0, "Pick_team works"));
        }
    }
}
