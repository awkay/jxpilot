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
package com.fulcrologic.jxpilot.server.robot;

import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.server.world.ShipGeom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** RobotRoster: the robot personalities (robot.c DefaultRobots / Parse_robot_file, lib/robots). */
class RobotRosterTest {

    @Nested
    @DisplayName("defaults")
    class Defaults {
        @Test
        @DisplayName("are the 38 robots of XPilot's lib/robots, in file order, with parsed ships")
        void libRobots() {
            RobotRoster r = RobotRoster.defaults();
            assertAll(
                () -> assertEquals(38, r.size(), "38 robots"),
                () -> assertEquals(new RobotDef("default", "Mad Max", "94 20",
                    "(15,8,7)(15,0)(7,1)(7,2)(2,4)(-1,11)(-3,11)(-2,3)(-8,6)(-8,-6)(-2,-3)(-3,-11)(-1,-11)(2,-4)(7,-2)(7,-1)"),
                    r.def(0), "the first is Mad Max"),
                () -> assertEquals("Neuro", r.def(37).name(), "the last is Neuro"),
                () -> assertEquals("(7,2,4)(10,0)(5,9)(-6,9)(-11,0)(-6,-9)(5,-9)(10,0)", r.def(12).shape(),
                    "Droidion has the lib/robots shape, not robot.c's"),
                () -> assertEquals(15, r.ship(0).numPoints, "Mad Max's ship has its 15 hull points"),
                () -> assertEquals(0, r.used(5), "nobody has been used yet"));
        }

        @Test
        @DisplayName("equal what parsing XPilot's lib/robots file gives")
        void sameAsFile() {
            RobotRoster file = RobotRoster.load(Path.of("src", "test", "resources", "robots", "lib-robots"));
            RobotRoster def = RobotRoster.defaults();
            for (int i = 0; i < def.size(); i++) {
                assertEquals(file.def(i), def.def(i), "robot " + i + " matches lib/robots");
            }
            assertEquals(def.size(), file.size(), "same number of robots");
        }
    }

    @Nested
    @DisplayName("parse")
    class Parse {
        @Test
        @DisplayName("reads name, ship, type and para lines into records ended by an empty line or the end")
        void records() {
            RobotRoster r = RobotRoster.parse("""
                name:  Alpha \t
                para:\t10 20
                ship:  (15,0)(-9,8)(-9,-8)
                type:  special

                name: Beta
                """);
            assertAll(
                () -> assertEquals(2, r.size(), "two records"),
                () -> assertEquals(new RobotDef("special", "Alpha", "10 20", "(15,0)(-9,8)(-9,-8)"), r.def(0),
                    "white space around values is dropped"),
                () -> assertEquals(new RobotDef("special", "Beta", "10 20", "(15,0)(-9,8)(-9,-8)"), r.def(1),
                    "fields carry over into the next record (C reuses its buffers)"));
        }

        @Test
        @DisplayName("starts with type default, empty para and the default ship, and needs a name")
        void defaultsAndNames() {
            RobotRoster r = RobotRoster.parse("para: 1 2\n\nname: Solo\nship: bogus\nunknown: x\n");
            assertAll(
                () -> assertEquals(1, r.size(), "a record without a name is dropped"),
                () -> assertEquals(new RobotDef("default", "Solo", "1 2", "bogus"), r.def(0),
                    "type defaults to default, para carries over from the dropped record"),
                () -> assertSame(ShipGeom.defaultShip(), r.ship(0), "an invalid ship gives the default ship"));
            RobotRoster d = RobotRoster.parse("name: A\n");
            assertEquals(new RobotDef("default", "A", "", RobotRoster.DEFAULT_SHIP), d.def(0),
                "untouched fields have C's initial values");
        }

        @Test
        @DisplayName("cuts values to the C buffer sizes")
        void truncation() {
            String longName = "ABCDEFGHIJKLMNOPQRST";
            String longPara = "9".repeat(300);
            RobotRoster r = RobotRoster.parse("name: " + longName + "\ntype: " + longName + "\npara: " + longPara
                + "\nship: " + "(1,2)".repeat(120) + "\n");
            assertAll(
                () -> assertEquals("ABCDEFGHIJKLMNO", r.def(0).name(), "names keep 15 characters"),
                () -> assertEquals("ABCDEFGHIJKLMNO", r.def(0).driver(), "types keep 15 characters"),
                () -> assertEquals(79, r.def(0).config().length(), "the config keeps 79 (MAX_CHARS - 1)"),
                () -> assertEquals(511, r.def(0).shape().length(), "ships keep 511 characters"));
        }

        @Test
        @DisplayName("a value cut inside trailing blanks loses the blanks; exactly 15 characters are kept")
        void cutThenTrim() {
            RobotRoster r = RobotRoster.parse("name: ABCDEFGHIJKLMN P\n\nname: ABCDEFGHIJKLMNO\n");
            assertAll(
                () -> assertEquals("ABCDEFGHIJKLMN", r.def(0).name(), "the cut leaves a trailing blank, then trimmed"),
                () -> assertEquals("ABCDEFGHIJKLMNO", r.def(1).name(), "15 characters fit"));
            RobotRoster one = RobotRoster.parse("name: First\nname: Second\n");
            assertAll(
                () -> assertEquals(1, one.size(), "a second name before the end of the record replaces the first"),
                () -> assertEquals("Second", one.def(0).name(), "the last name wins"));
        }

        @Test
        @DisplayName("without any named record gives the built-in robots")
        void emptyFile() {
            assertAll(
                () -> assertEquals(38, RobotRoster.parse("").size(), "an empty file"),
                () -> assertEquals(38, RobotRoster.parse("ship: (1,1)\n\n").size(), "no names"));
        }

        @Test
        @DisplayName("a value line with only blanks after the colon gives an empty value")
        void blankValue() {
            assertEquals("", RobotRoster.parse("name: X\npara:   \n").def(0).config(), "empty para");
        }
    }

    @Nested
    @DisplayName("load")
    class Load {
        @Test
        @DisplayName("falls back to the built-in robots when the file cannot be read")
        void missing() {
            RobotRoster r = RobotRoster.load(Path.of("no", "such", "robots"));
            assertEquals(RobotRoster.defaults().def(3), r.def(3), "defaults");
        }
    }

    @Nested
    @DisplayName("ships")
    class Ships {
        @Test
        @DisplayName("each robot gets its own geometry built from its shape")
        void geometry() {
            RobotRoster r = RobotRoster.defaults();
            ShipShape kryten = ShipShape.parse(r.def(2).shape());
            assertAll(
                () -> assertEquals(kryten.numPoints(), r.ship(2).numPoints, "Kryten's points"),
                () -> assertNotSame(r.ship(1), r.ship(2), "separate geometry per robot"));
        }
    }
}
