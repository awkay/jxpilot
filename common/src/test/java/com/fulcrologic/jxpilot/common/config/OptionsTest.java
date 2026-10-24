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
package com.fulcrologic.jxpilot.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Options and config parsing")
class OptionsTest {
    /** Parsed doubles are exact up to rounding. */
    static final double EPS = 1e-12;

    static Options parse(String text) {
        Options o = new Options();
        ConfigParser.parse(text, "t", OptionOrigin.MAP, o);
        return o;
    }

    @Nested
    @DisplayName("defaults (cmdline.c)")
    class Defaults {
        @Test
        @DisplayName("typed defaults for gameplay options")
        void defaults() {
            Options o = new Options();
            assertAll(
                () -> assertEquals(-0.14, o.getDouble(Opt.GRAVITY), EPS, "gravity -0.14"),
                () -> assertEquals(256, o.getInt(Opt.MAX_PLAYER_SHOTS), "maxPlayerShots 256"),
                () -> assertEquals(14, o.framesPerSecond(), "framesPerSecond 14"),
                () -> assertEquals(21.0, o.getDouble(Opt.SHOT_SPEED), EPS, "shotSpeed 21"),
                () -> assertEquals(60, o.getInt(Opt.SHOT_LIFE), "shotLife 60"),
                () -> assertTrue(o.getBool(Opt.EDGE_BOUNCE), "edgeBounce yes"),
                () -> assertFalse(o.getBool(Opt.EDGE_WRAP), "edgeWrap no"),
                () -> assertTrue(o.getBool(Opt.PLAYER_STARTS_SHIELDED), "playerStartsShielded yes"),
                () -> assertEquals(0.89, o.getDouble(Opt.PLAYER_WALL_BOUNCE_BRAKE_FACTOR), EPS, "playerWallBrake 0.89"),
                () -> assertEquals(1000, o.getInt(Opt.INITIAL_FUEL), "initialFuel 1000"),
                () -> assertEquals(1e-9, o.getDouble(Opt.ITEM_TANK_PROB), EPS, "item probabilities 1e-9"),
                () -> assertEquals(72 * 14, o.getFrames(Opt.CANNON_DEAD_TIME), "cannonDeadTime 72 s in frames"),
                () -> assertEquals(0, o.getPosX(Opt.GRAVITY_POINT), "gravityPoint x 0"),
                () -> assertNull(o.mapData(), "no map data by default"),
                () -> assertEquals("unknown", o.getString(Opt.MAP_NAME), "mapName 'unknown'"),
                () -> assertFalse(o.isSet(Opt.GRAVITY), "defaults are not 'set'"),
                () -> assertEquals(OptionOrigin.INIT, o.originOf(Opt.GRAVITY), "defaults have INIT origin"),
                () -> assertEquals(301, Opt.values().length, "all 293 cmdline.c options plus 8 JXPilot network options exist"));
        }

        @Test
        @DisplayName("JXPilot network binding options")
        void bindOptions() {
            Options o = new Options();
            Options set = new Options();
            boolean mapBind = set.setValue("bind", "utun4,10.0.0.0/8", false, OptionOrigin.MAP);
            boolean mapWait = set.setValue("bindWait", "yes", false, OptionOrigin.MAP);
            set.setValue("bind", "utun4,10.0.0.0/8", false, OptionOrigin.DEFAULTS);
            set.setValue("bindWait", "yes", false, OptionOrigin.DEFAULTS);
            Options cli = new Options();
            List<String> rest = cli.parseCommandLine(new String[] {"--bind", "loopback", "--bindWait"});
            assertAll(
                () -> assertEquals(List.of("all"), o.getList(Opt.BIND), "bind defaults to all"),
                () -> assertFalse(o.getBool(Opt.BIND_WAIT), "bindWait defaults to off"),
                () -> assertFalse(mapBind, "a map cannot set bind"),
                () -> assertFalse(mapWait, "a map cannot set bindWait"),
                () -> assertEquals(2, set.warnings().size(), "both refusals are warned about"),
                () -> assertEquals(List.of("utun4", "10.0.0.0/8"), set.getList(Opt.BIND),
                    "bind is a comma list settable from the server config file"),
                () -> assertTrue(set.getBool(Opt.BIND_WAIT), "bindWait is settable from the server config file"),
                () -> assertEquals(List.of(), rest, "both are command-line options"),
                () -> assertEquals(List.of("loopback"), cli.getList(Opt.BIND), "--bind sets bind"),
                () -> assertTrue(cli.getBool(Opt.BIND_WAIT), "--bindWait switches bindWait on"),
                () -> assertFalse(set.toConfigText().contains("bind"), "neither is shipped to clients (not visible)"));
        }

        @Test
        @DisplayName("JXPilot server network tunables: defaults, command line and config file only")
        void networkTunables() {
            Options o = new Options();
            Options map = new Options();
            List<Boolean> fromMap = new ArrayList<>();
            for (String n : List.of("snapshotDivisor", "radarDivisor", "ipPacketRate", "ipPacketBurst",
                "sessionPacketRate", "sessionPacketBurst", "maxClientsPerIP")) {
                fromMap.add(map.setValue(n, "7", false, OptionOrigin.MAP));
            }
            Options cli = new Options();
            cli.parseCommandLine(new String[] {"--snapshotDivisor", "3", "-radarDivisor", "5", "--ipPacketRate", "50",
                "--ipPacketBurst", "60", "--sessionPacketRate", "70", "--sessionPacketBurst", "80", "--maxPerIP", "9"});
            Options cfg = new Options();
            cfg.setValue("sessionPacketRate", "123.5", false, OptionOrigin.DEFAULTS);
            assertAll(
                () -> assertEquals(1, o.getInt(Opt.SNAPSHOT_DIVISOR), "snapshotDivisor defaults to 1 (every frame)"),
                () -> assertEquals(3, o.getInt(Opt.RADAR_DIVISOR), "radarDivisor defaults to 3"),
                () -> assertEquals(400.0, o.getDouble(Opt.IP_PACKET_RATE), EPS, "ipPacketRate defaults to 400/s"),
                () -> assertEquals(800.0, o.getDouble(Opt.IP_PACKET_BURST), EPS, "ipPacketBurst defaults to 800"),
                () -> assertEquals(400.0, o.getDouble(Opt.SESSION_PACKET_RATE), EPS, "sessionPacketRate defaults to 400/s"),
                () -> assertEquals(800.0, o.getDouble(Opt.SESSION_PACKET_BURST), EPS, "sessionPacketBurst defaults to 800"),
                () -> assertEquals(2, o.getInt(Opt.MAX_CLIENTS_PER_IP), "maxClientsPerIP defaults to 2, as C (cmdline.c:1122)"),
                () -> assertEquals(List.of(false, false, false, false, false, false, false), fromMap,
                    "none of them can be set from a map"),
                () -> assertEquals(3, cli.getInt(Opt.SNAPSHOT_DIVISOR), "--snapshotDivisor"),
                () -> assertEquals(5, cli.getInt(Opt.RADAR_DIVISOR), "-radarDivisor"),
                () -> assertEquals(50.0, cli.getDouble(Opt.IP_PACKET_RATE), EPS, "--ipPacketRate"),
                () -> assertEquals(60.0, cli.getDouble(Opt.IP_PACKET_BURST), EPS, "--ipPacketBurst"),
                () -> assertEquals(70.0, cli.getDouble(Opt.SESSION_PACKET_RATE), EPS, "--sessionPacketRate"),
                () -> assertEquals(80.0, cli.getDouble(Opt.SESSION_PACKET_BURST), EPS, "--sessionPacketBurst"),
                () -> assertEquals(9, cli.getInt(Opt.MAX_CLIENTS_PER_IP), "--maxPerIP"),
                () -> assertEquals(123.5, cfg.getDouble(Opt.SESSION_PACKET_RATE), EPS, "the server config file sets them"));
        }

        @Test
        @DisplayName("Opt metadata and lookup")
        void metadata() {
            assertAll(
                () -> assertSame(Opt.SHOT_SPEED, Opt.find("S hoT spEEd"), "lookup ignores case and whitespace"),
                () -> assertSame(Opt.MAX_PLAYER_SHOTS, Opt.find("shots"), "lookup by alias"),
                () -> assertSame(Opt.FRAMES_PER_SECOND, Opt.find("FPS"), "FPS alias"),
                () -> assertSame(Opt.TEAM_PLAY, Opt.find("teams"), "teams alias"),
                () -> assertSame(Opt.TIMING, Opt.find("race"), "race alias"),
                () -> assertNull(Opt.find("noSuchOption"), "unknown names give null"),
                () -> assertEquals("shots", Opt.MAX_PLAYER_SHOTS.alias(), "alias accessor"),
                () -> assertEquals("maxPlayerShots", Opt.MAX_PLAYER_SHOTS.optionName(), "name accessor"),
                () -> assertEquals(Opt.F.ANY | Opt.F.VIS, Opt.GRAVITY.flags(), "flags accessor"),
                () -> assertEquals("Gravity strength.", Opt.GRAVITY.help(), "help accessor"),
                () -> assertTrue(Opt.GRAVITY.visible(), "visible options"),
                () -> assertFalse(Opt.ROBOT_FILE.visible(), "invisible options"),
                () -> assertTrue(Opt.CONTACT_PORT.allowedFrom(OptionOrigin.COMMAND), "port from command line"),
                () -> assertFalse(Opt.CONTACT_PORT.allowedFrom(OptionOrigin.MAP), "port not from map"),
                () -> assertTrue(Opt.CONTACT_PORT.allowedFrom(OptionOrigin.INIT), "INIT is always allowed"),
                () -> assertEquals("abc@", Opt.normalize(" A\tb C\r\n\f\u000b@"), "normalize drops ASCII space, lowercases"),
                () -> assertEquals("az@[", Opt.normalize("AZ@["), "A and Z are lower-cased, '@' and '[' are not"));
        }
    }

    @Nested
    @DisplayName("CNum (C number parsing)")
    class Numbers {
        static long l(String s) {
            long[] r = new long[1];
            return CNum.strtol(s, r) ? r[0] : Long.MIN_VALUE;
        }

        static double d(String s) {
            double[] r = new double[1];
            return CNum.strtod(s, r) ? r[0] : Double.NaN;
        }

        @Test
        @DisplayName("strtol base 0")
        void strtol() {
            assertAll(
                () -> assertEquals(42, l("  42"), "skips leading whitespace"),
                () -> assertEquals(42, l("+42"), "accepts a plus sign"),
                () -> assertEquals(-42, l("-42"), "accepts a minus sign"),
                () -> assertEquals(31, l("0x1F"), "hex with 0x"),
                () -> assertEquals(-31, l("-0X1f"), "negative hex with 0X"),
                () -> assertEquals(0, l("0x"), "a bare 0x parses the 0"),
                () -> assertEquals(8, l("010"), "leading 0 is octal"),
                () -> assertEquals(0, l("08"), "an invalid octal digit stops after the 0"),
                () -> assertEquals(12, l("12abc"), "a numeric prefix is enough"),
                () -> assertEquals(9, l("9"), "single digit"),
                () -> assertEquals(Long.MIN_VALUE, l("abc"), "no digits fails"),
                () -> assertEquals(Long.MIN_VALUE, l(""), "empty fails"),
                () -> assertEquals(Long.MIN_VALUE, l("-"), "a lone sign fails"),
                () -> assertEquals(15, l("0x0F"), "a hex number may start with the digit 0"),
                () -> assertEquals(Integer.MAX_VALUE * 4L, l("99999999999999999999"), "huge values clamp"),
                () -> assertEquals(1, l("1١"), "non-ASCII digits stop parsing"));
        }

        @Test
        @DisplayName("strtod prefix")
        void strtod() {
            assertAll(
                () -> assertEquals(-1500.0, d("  -1.5e3x"), EPS, "sign, fraction, exponent, trailing junk"),
                () -> assertEquals(1.0, d("1e"), EPS, "an incomplete exponent is ignored"),
                () -> assertEquals(1.0, d("1e+"), EPS, "an exponent sign without digits is ignored"),
                () -> assertEquals(150.0, d("1.5E+2"), EPS, "upper-case exponent with sign"),
                () -> assertEquals(0.015, d("1.5e-2"), EPS, "negative exponent"),
                () -> assertEquals(0.5, d(".5"), EPS, "leading dot"),
                () -> assertEquals(-0.5, d("-.5"), EPS, "signed leading dot"),
                () -> assertEquals(5.0, d("5."), EPS, "trailing dot"),
                () -> assertEquals(7.0, d("+7"), EPS, "plus sign"),
                () -> assertTrue(Double.isNaN(d("e5")), "no mantissa fails"),
                () -> assertTrue(Double.isNaN(d(".")), "a lone dot fails"),
                () -> assertEquals(10.09, d("10.09"), EPS, "0 and 9 are mantissa digits"),
                () -> assertEquals(1e10, d("1e10"), 1, "0 is an exponent digit"),
                () -> assertEquals(1e9, d("1e9"), 1, "9 is an exponent digit"),
                () -> assertTrue(Double.isNaN(d("")), "empty fails"));
        }

        @Test
        @DisplayName("boolean words")
        void bools() {
            assertAll(
                () -> assertEquals(1, CNum.bool("YES"), "yes"),
                () -> assertEquals(1, CNum.bool("On"), "on"),
                () -> assertEquals(1, CNum.bool("true"), "true"),
                () -> assertEquals(0, CNum.bool("NO"), "no"),
                () -> assertEquals(0, CNum.bool("off"), "off"),
                () -> assertEquals(0, CNum.bool("False"), "false"),
                () -> assertEquals(-1, CNum.bool("1"), "digits are not booleans"));
        }
    }

    @Nested
    @DisplayName("ConfigParser")
    class Parser {
        @Test
        @DisplayName("names, comments, blank lines and errors")
        void lines() {
            Options o = parse("# full comment\n\n   \n\t# indented comment\n"
                + "Shot Speed : 30\n"
                + "MAP width: 12 # comment\n"
                + "  teams: yes\n"
                + "gravity: -0.5   # trailing\n"
                + "1bad: 3\n"
                + "nocolon\n"
                + "nocolon2 # c\n"
                + "empty:\n"
                + "onlycomment: # c\n"
                + "shotLife: 10\n"
                + "mapName:x");
            assertAll(
                () -> assertEquals(30.0, o.getDouble(Opt.SHOT_SPEED), EPS, "whitespace inside names is ignored"),
                () -> assertEquals(12, o.getInt(Opt.MAP_WIDTH), "comments after values are dropped"),
                () -> assertTrue(o.getBool(Opt.TEAM_PLAY), "leading whitespace before a name is allowed"),
                () -> assertEquals(-0.5, o.getDouble(Opt.GRAVITY), EPS, "values are trimmed"),
                () -> assertEquals(10, o.getInt(Opt.SHOT_LIFE), "errors do not swallow following lines"),
                () -> assertEquals("x", o.getString(Opt.MAP_NAME), "a last line without newline is read"),
                () -> assertEquals(java.util.Map.of(), o.unknownOptions(),
                    "the rest of a bad line ('bad: 3') is skipped, not parsed as an option"),
                () -> assertEquals(List.of("t line 9: Names must start with an alphabetic.",
                    "t line 10: No colon found on line.", "t line 11: No colon found on line.",
                    "t line 13: no value specified.", "t line 14: no value specified."), o.warnings(),
                    "each bad line is reported; like C, value errors report the following line number"));
        }

        @Test
        @DisplayName("boundary characters and end-of-input cases")
        void boundaries() {
            Options o = parse("aaa: 1\nzzz: 2\nAbb: 3\nZyy: 4\nmapWidth: 3 # c");
            Options trailingSpace = parse("mapWidth: 4\n   ");
            Options lonely = parse("define: lonely");
            Options crFirst = parse("\r\nmapData: \\multiline: E\r\nxx\r\nE\r\n");
            assertAll(
                () -> assertEquals("1", o.getRaw("aaa"), "names may start with 'a'"),
                () -> assertEquals("2", o.getRaw("zzz"), "names may start with 'z'"),
                () -> assertEquals("3", o.getRaw("Abb"), "names may start with 'A'"),
                () -> assertEquals("4", o.getRaw("Zyy"), "names may start with 'Z'"),
                () -> assertEquals(3, o.getInt(Opt.MAP_WIDTH), "a comment at end of input without newline"),
                () -> assertEquals(4, trailingSpace.getInt(Opt.MAP_WIDTH), "trailing spaces at end of input"),
                () -> assertEquals(List.of("t line 1: no value specified."), lonely.warnings(), "define with only a name"),
                () -> assertEquals("xx\n", crFirst.mapData(), "CRLF is folded even when the text starts with CR"));
        }

        @Test
        @DisplayName("multiline values")
        void multiline() {
            Options o = parse("mapData: \\multiline: END\nab#c\n  x\nEND\nmapWidth: 4\n");
            Options eof = parse("mapData: \\multiline: END\nline1\nline2");
            Options crlf = parse("mapWidth: 7\r\nmapData: \\multiline: END\r\nxx\r\nEND\r\nbad\r\n");
            Options partial = parse("mapData: \\multiline: END\nENDING\nEN\nEND\n");
            assertAll(
                () -> assertEquals("ab#c\n  x\n", o.mapData(), "lines are kept verbatim including '#'"),
                () -> assertEquals(4, o.getInt(Opt.MAP_WIDTH), "parsing continues after the delimiter"),
                () -> assertEquals("line1\nline2", eof.mapData(), "end of file terminates a multiline value"),
                () -> assertEquals("xx\n", crlf.mapData(), "CRLF files work"),
                () -> assertEquals(7, crlf.getInt(Opt.MAP_WIDTH), "CRLF values are read"),
                () -> assertEquals(List.of("t line 4: No colon found on line."), crlf.warnings(),
                    "line numbers count multiline lines but, like C, not the delimiter line"),
                () -> assertEquals("ENDING\nEN\n", partial.mapData(), "only an exact delimiter line ends the value"));
        }

        @Test
        @DisplayName("override, define and expand")
        void macros() {
            Options o = parse("gravity: \\override: -3\ngravity: -4\n"
                + "define: fast \\multiline: EOM\nshotSpeed: 40\nshotLife: 20\nEOM\n"
                + "expand: fast\n"
                + "expand: missing\n"
                + "define: sp  shotLife: 7\n");
            Options cli = new Options();
            ConfigParser.parse("define: quick shotLife: 9\n", "d", OptionOrigin.DEFAULTS, cli);
            ConfigParser.expand(cli, "quick");
            assertAll(
                () -> assertEquals(-3.0, o.getDouble(Opt.GRAVITY), EPS, "an override value is not replaced by the map"),
                () -> assertEquals(40.0, o.getDouble(Opt.SHOT_SPEED), EPS, "expand parses the macro lines"),
                () -> assertEquals(20, o.getInt(Opt.SHOT_LIFE), "expand applies every macro line"),
                () -> assertEquals("shotLife: 7", o.getRaw("sp"), "define stores the rest of the line as a macro"),
                () -> assertEquals(List.of("Can't expand `missing' because it has not been defined."), o.warnings(),
                    "expanding an undefined macro warns"),
                () -> assertEquals(9, cli.getInt(Opt.SHOT_LIFE), "expand uses the macro's own origin"),
                () -> assertEquals(OptionOrigin.DEFAULTS, cli.originOf(Opt.SHOT_LIFE), "expanded values keep that origin"));
        }

        @Test
        @DisplayName("old v1.x files are refused")
        void oldFormat() {
            Options o = parse("1 2 3\nmapWidth: 5\n");
            assertAll(
                () -> assertEquals(100, o.getInt(Opt.MAP_WIDTH), "nothing is parsed from an old-format file"),
                () -> assertEquals(List.of("t is in old (v1.x) format, which is not supported"), o.warnings(),
                    "the refusal is reported"));
        }
    }

    @Nested
    @DisplayName("Options.setValue / conversion")
    class SetValue {
        @Test
        @DisplayName("C-style conversion with default fallback and warnings")
        void conversion() {
            Options o = parse("shotLife: 0x10\nmapWidth: 010\nmapHeight: 12abc\ngravity: -.5e1x\n"
                + "shotSpeed: fast\nedgeWrap: ON\nedgeBounce: nope\ngravityPoint: 3, 4\nmaxRobots: many\n"
                + "gravityAngle: 45\n");
            Options bad = parse("gravityPoint: 7\n");
            Options bad2 = parse("gravityPoint: a,1\n");
            Options bad3 = parse("gravityPoint: 1,b\n");
            Options bad4 = parse("gravityPoint: 3,x\n");
            assertAll(
                () -> assertEquals(16, o.getInt(Opt.SHOT_LIFE), "hex ints"),
                () -> assertEquals(8, o.getInt(Opt.MAP_WIDTH), "octal ints"),
                () -> assertEquals(12, o.getInt(Opt.MAP_HEIGHT), "numeric prefix ints"),
                () -> assertEquals(-5.0, o.getDouble(Opt.GRAVITY), EPS, "numeric prefix reals"),
                () -> assertEquals(21.0, o.getDouble(Opt.SHOT_SPEED), EPS, "an invalid real falls back to the default"),
                () -> assertEquals(4, o.getInt(Opt.MAX_ROBOTS), "an invalid int falls back to the default"),
                () -> assertTrue(o.getBool(Opt.EDGE_WRAP), "boolean words"),
                () -> assertTrue(o.getBool(Opt.EDGE_BOUNCE), "an invalid boolean falls back to the default"),
                () -> assertEquals(3, o.getPosX(Opt.GRAVITY_POINT), "position x"),
                () -> assertEquals(4, o.getPosY(Opt.GRAVITY_POINT), "position y after the comma"),
                () -> assertEquals(45, o.getInt(Opt.GRAVITY_ANGLE), "getInt truncates reals"),
                () -> assertEquals("fast", o.getString(Opt.SHOT_SPEED), "the raw string is kept"),
                () -> assertEquals(List.of("shotSpeed value 'fast' not a number.", "edgeBounce value 'nope' not a boolean.",
                    "maxRobots value 'many' not an integral number."), o.warnings(), "each bad value is reported"),
                () -> assertEquals(0, bad.getPosX(Opt.GRAVITY_POINT), "a position without comma falls back"),
                () -> assertEquals(List.of("gravityPoint value '7' not a valid position."), bad.warnings(),
                    "a bad position is reported"),
                () -> assertEquals(0, bad2.getPosY(Opt.GRAVITY_POINT), "a non-numeric x falls back"),
                () -> assertEquals(0, bad3.getPosY(Opt.GRAVITY_POINT), "a non-numeric y falls back"),
                () -> assertEquals(0, bad4.getPosX(Opt.GRAVITY_POINT), "'3,x' is rejected as a whole: x falls back too"),
                () -> assertEquals(List.of("gravityPoint value '3,x' not a valid position."), bad4.warnings(),
                    "'3,x' is reported"));
        }

        @Test
        @DisplayName("allowChange implements Option_change_node precedence")
        void precedence() {
            OptionOrigin I = OptionOrigin.INIT;
            OptionOrigin M = OptionOrigin.MAP;
            OptionOrigin D = OptionOrigin.DEFAULTS;
            OptionOrigin C = OptionOrigin.COMMAND;
            OptionOrigin P = OptionOrigin.PASSWORD;
            assertAll(
                () -> assertTrue(Options.allowChange(P, true, C, false), "command line always wins"),
                () -> assertTrue(Options.allowChange(C, true, I, false), "INIT always applies"),
                () -> assertFalse(Options.allowChange(C, false, D, true), "defaults never beat the command line"),
                () -> assertTrue(Options.allowChange(D, false, D, false), "defaults replace defaults"),
                () -> assertFalse(Options.allowChange(D, true, D, false), "defaults cannot replace an overriding default"),
                () -> assertTrue(Options.allowChange(M, false, D, true), "defaults beat the map only with override"),
                () -> assertFalse(Options.allowChange(M, false, D, false), "defaults lose to the map without override"),
                () -> assertFalse(Options.allowChange(P, false, D, true), "defaults never beat the password file"),
                () -> assertTrue(Options.allowChange(I, false, D, false), "defaults replace built-ins"),
                () -> assertFalse(Options.allowChange(C, false, M, true), "the map never beats the command line"),
                () -> assertTrue(Options.allowChange(D, false, M, false), "the map beats plain defaults"),
                () -> assertFalse(Options.allowChange(D, true, M, false), "the map loses to overriding defaults"),
                () -> assertTrue(Options.allowChange(M, false, M, false), "the map replaces the map"),
                () -> assertFalse(Options.allowChange(M, true, M, true), "the map cannot replace an overriding map value"),
                () -> assertFalse(Options.allowChange(P, false, M, true), "the map never beats the password file"),
                () -> assertTrue(Options.allowChange(I, false, M, false), "the map replaces built-ins"),
                () -> assertFalse(Options.allowChange(C, false, P, true), "password never beats the command line"),
                () -> assertTrue(Options.allowChange(D, true, P, false), "password beats defaults"),
                () -> assertTrue(Options.allowChange(M, true, P, false), "password beats the map"),
                () -> assertTrue(Options.allowChange(P, false, P, false), "password replaces password"),
                () -> assertFalse(Options.allowChange(P, true, P, false), "password cannot replace overriding password"),
                () -> assertTrue(Options.allowChange(I, false, P, false), "password replaces built-ins"));
        }

        @Test
        @DisplayName("setValue applies permissions, precedence and retains unknown names")
        void setValues() {
            Options o = new Options();
            boolean cmd = o.setValue("gravity", "-1", false, OptionOrigin.COMMAND);
            boolean mapLater = o.setValue("gravity", "-2", true, OptionOrigin.MAP);
            boolean portFromMap = o.setValue("port", "999", false, OptionOrigin.MAP);
            boolean unknownNew = o.setValue("myThing", "a", false, OptionOrigin.MAP);
            boolean unknownNull = o.setValue("otherThing", null, false, OptionOrigin.MAP);
            boolean unknownReplace = o.setValue("my thing", "b", true, OptionOrigin.DEFAULTS);
            boolean unknownBlocked = o.setValue("MYTHING", "c", false, OptionOrigin.MAP);
            o.force(Opt.SHOT_SPEED, "33");
            assertAll(
                () -> assertTrue(cmd, "a command line value is stored"),
                () -> assertFalse(mapLater, "a later map value loses to the command line"),
                () -> assertEquals(-1.0, o.getDouble(Opt.GRAVITY), EPS, "the command line value stays"),
                () -> assertFalse(portFromMap, "a map may not set the port"),
                () -> assertEquals(15345, o.getInt(Opt.CONTACT_PORT), "the port keeps its default"),
                () -> assertEquals(List.of("Not allowed to change option 'port' from MAP."), o.warnings(),
                    "the refused permission is reported"),
                () -> assertTrue(unknownNew, "unknown options are retained"),
                () -> assertFalse(unknownNull, "an unknown option without value is not created"),
                () -> assertNull(o.getRaw("otherThing"), "no value for the null unknown"),
                () -> assertTrue(unknownReplace, "an unknown option follows precedence (override beats map)"),
                () -> assertFalse(unknownBlocked, "a map cannot replace an overriding default for unknowns"),
                () -> assertEquals("b", o.getRaw("myThing"), "the unknown keeps the winning value"),
                () -> assertEquals(OptionOrigin.DEFAULTS, o.getOrigin("my thing"), "the unknown keeps its origin"),
                () -> assertEquals("myThing", o.unknownOptions().get("mything").name, "the original name is kept"),
                () -> assertNull(o.getOrigin("never"), "never-set names have no origin"),
                () -> assertEquals(OptionOrigin.COMMAND, o.getOrigin("gravity"), "known names report their origin"),
                () -> assertEquals("-1", o.getRaw("Gravity"), "known names report their raw value"),
                () -> assertEquals(33.0, o.getDouble(Opt.SHOT_SPEED), EPS, "force converts the value"),
                () -> assertEquals(OptionOrigin.COMMAND, o.originOf(Opt.SHOT_SPEED), "force records a command origin"),
                () -> assertFalse(o.setValue("shotSpeed", "1", true, OptionOrigin.MAP), "forced values beat the map"));
        }

        @Test
        @DisplayName("derived getters")
        void derived() {
            Options o = parse("framesPerSecond: 0\nexpand: \n");
            Options cleared = new Options();
            cleared.force(Opt.GRAVITY_POINT, "3,4");
            cleared.force(Opt.GRAVITY_POINT, null);
            Options o2 = new Options();
            o2.force(Opt.FRAMES_PER_SECOND, "20");
            o2.force(Opt.MAX_PAUSE_TIME, "2.5");
            o2.force(Opt.EXPAND, ",a,,b,");
            assertAll(
                () -> assertEquals(1, o.framesPerSecond(), "framesPerSecond is at least 1"),
                () -> assertEquals(50, o2.getFrames(Opt.MAX_PAUSE_TIME), "seconds to frames: (int)(2.5 * 20)"),
                () -> assertEquals(1.05, o2.getPerFrame(Opt.SHOT_SPEED), EPS, "per second to per frame: 21 / 20"),
                () -> assertEquals(List.of("a", "b"), o2.getList(Opt.EXPAND), "lists split on commas, empties dropped"),
                () -> assertEquals(List.of(), new Options().getList(Opt.ROBOT_FILE), "a null list is empty"),
                () -> assertEquals(0, cleared.getPosX(Opt.GRAVITY_POINT), "a null position falls back to 0,0"),
                () -> assertEquals(List.of(), cleared.warnings(), "a null position is not reported"));
        }
    }

    @Nested
    @DisplayName("parseCommandLine")
    class CommandLine {
        @Test
        @DisplayName("XPilot-style -name value, -bool/+bool, leftovers")
        void commandLine() {
            Options o = new Options();
            List<String> rest = o.parseCommandLine(new String[] {"-shots", "100", "+edgeBounce", "-teams", "--map",
                "foo.xp", "extra", "-unknownOpt", "-help", "-", "-FPS"});
            ConfigParser.parse("shots: 5\n", "m", OptionOrigin.MAP, o);
            Options ex = new Options();
            ex.parseCommandLine(new String[] {"-expand", "m1"});
            int beforeFinish = ex.getInt(Opt.SHOT_LIFE);
            ConfigParser.parse("define: m1 shotLife: 3\n", "d", OptionOrigin.MAP, ex);
            ex.finishParsing();
            Options none = new Options();
            none.finishParsing();
            assertAll(
                () -> assertEquals(100, o.getInt(Opt.MAX_PLAYER_SHOTS), "-name value sets a value"),
                () -> assertFalse(o.getBool(Opt.EDGE_BOUNCE), "+bool sets false"),
                () -> assertTrue(o.getBool(Opt.TEAM_PLAY), "-bool sets true"),
                () -> assertEquals("foo.xp", o.getString(Opt.MAP_FILE_NAME), "--name works like -name"),
                () -> assertEquals(List.of("extra", "-unknownOpt", "-"), rest, "unknown arguments are returned"),
                () -> assertEquals(List.of("Option '-FPS' needs an argument"), o.warnings(),
                    "a missing value is reported"),
                () -> assertEquals(100, o.getInt(Opt.MAX_PLAYER_SHOTS), "the command line beats a later map"),
                () -> assertEquals(60, beforeFinish, "-expand is not applied while parsing the command line"),
                () -> assertEquals(3, ex.getInt(Opt.SHOT_LIFE), "finishParsing expands a macro defined later in the map"),
                () -> assertEquals(OptionOrigin.MAP, ex.originOf(Opt.SHOT_LIFE), "expanded lines use the macro's origin"),
                () -> assertEquals(List.of(), none.warnings(), "finishParsing without -expand does nothing"));
        }
    }

    @Nested
    @DisplayName("toConfigText")
    class ConfigText {
        @Test
        @DisplayName("unknown names (macros, settings this server ignores) are shipped only when the map set them")
        void unknownOrigins() {
            Options o = parse("mapWidth: 1\nmapHeight: 1\nfromMap: m\nmapData: \\multiline: EOF\nx\nEOF\n");
            o.setValue("fromDefaults", "d", false, OptionOrigin.DEFAULTS);
            o.setValue("fromCommand", "c", false, OptionOrigin.COMMAND);
            o.setValue("fromPassword", "p", false, OptionOrigin.PASSWORD);
            ConfigParser.parse("define: Macro \\multiline: End\ngravity: 1\nEnd\n", "defaults", OptionOrigin.DEFAULTS, o);
            String text = o.toConfigText();
            assertAll(
                () -> assertTrue(text.contains("fromMap: m\n"), "the map's own unknown name"),
                () -> assertFalse(text.contains("fromDefaults"), "not the defaults file's"),
                () -> assertFalse(text.contains("fromCommand"), "not the command line's"),
                () -> assertFalse(text.contains("fromPassword"), "not the password file's"),
                () -> assertFalse(text.contains("Macro"), "not the defaults file's macros (lib/defaults' SoftWalls...)"));
        }

        @Test
        @DisplayName("round trips through the parser exactly")
        void roundTrip() {
            Options o = parse("mapWidth: 3\nmapHeight: 2\nmapName: Test Map\nteamPlay: yes\ngravity: -0.3\n"
                + "weird: a\\b\nmapData: \\multiline: EndOfMapdata\nx_x\nEndOfValue\nEndOfValue1\nEndOfMapdata\n");
            String[][] values = {
                {"mapAuthor", "  padded name  "},
                {"mapName", "Map #2"},
                {"robotRealName", "\\leading backslash"},
                {"tankRealName", "\\override: x"},
                {"robotHostName", ""},
                {"tankHostName", "line1\r\nline2\r\n"},
                {"defaultShipShape", "ends with cr\r"},
                {"tankShipShape", "two\nlines"},
                {"crValue", "a\r\r\nb"},
                {"plainValue", "\tleading tab"}};
            for (String[] v : values) {
                o.setValue(v[0], v[1], true, OptionOrigin.MAP);
            }
            String text = o.toConfigText();
            Options r = parse(text);
            List<String> sent = new ArrayList<>();
            List<String> got = new ArrayList<>();
            for (String[] v : values) {
                sent.add(v[1]);
                got.add(r.getRaw(v[0]));
            }
            assertAll(
                () -> assertEquals(sent, got,
                    "padding, '#', backslashes, empty, CRLF, a final CR, newlines and tabs all survive exactly"),
                () -> assertEquals("x_x\nEndOfValue\nEndOfValue1\n", r.mapData(),
                    "map data survives even when it contains candidate delimiters"),
                () -> assertTrue(r.getBool(Opt.TEAM_PLAY), "booleans survive"),
                () -> assertEquals(-0.3, r.getDouble(Opt.GRAVITY), EPS, "numbers survive"),
                () -> assertEquals("a\\b", r.getRaw("weird"), "unknown options survive"),
                () -> assertTrue(text.contains("mapName: \\noeol: \\multiline: EndOfValue\nMap #2\nEndOfValue\n"),
                    "a value without final newline uses \\noeol: + \\multiline:"),
                () -> assertTrue(text.contains("mapWidth: 3\n"), "plain values stay on one line"),
                () -> assertFalse(text.contains("contactPort"), "options a map may not set are omitted"),
                () -> assertFalse(text.contains("robotFile"), "invisible options are omitted"),
                () -> assertEquals(List.of(), r.warnings(), "the generated text parses without warnings"));
        }

        @Test
        @DisplayName("the delimiter is the first EndOfValue[n] that is not a whole line of the value")
        void delimiters() {
            StringBuilder partial = new StringBuilder();
            Options.appendValue(partial, "n", "aEndOfValue\nEndOfValueb\n");
            StringBuilder whole = new StringBuilder();
            Options.appendValue(whole, "n", "aEndOfValue\nEndOfValue\n");
            StringBuilder first = new StringBuilder();
            Options.appendValue(first, "n", "EndOfValue\nx\n");
            StringBuilder last = new StringBuilder();
            Options.appendValue(last, "n", "x\nEndOfValue");
            StringBuilder two = new StringBuilder();
            Options.appendValue(two, "n", "EndOfValue\nEndOfValue1\n");
            assertAll(
                () -> assertEquals("n: \\multiline: EndOfValue\naEndOfValue\nEndOfValueb\nEndOfValue\n", partial.toString(),
                    "partial-line matches do not count"),
                () -> assertEquals("n: \\multiline: EndOfValue1\naEndOfValue\nEndOfValue\nEndOfValue1\n", whole.toString(),
                    "a whole line after a partial match counts"),
                () -> assertTrue(first.toString().startsWith("n: \\multiline: EndOfValue1\n"), "a whole first line counts"),
                () -> assertTrue(last.toString().startsWith("n: \\noeol: \\multiline: EndOfValue1\n"),
                    "a whole last line without newline counts"),
                () -> assertTrue(two.toString().startsWith("n: \\multiline: EndOfValue2\n"), "suffixes count up from 1"));
        }

        @Test
        @DisplayName("needsMultiline decides per character class")
        void needsMultiline() {
            assertAll(
                () -> assertFalse(Options.needsMultiline("plain value"), "plain text is single-line"),
                () -> assertFalse(Options.needsMultiline("a\\b"), "an inner backslash is fine"),
                () -> assertTrue(Options.needsMultiline(""), "empty needs multiline"),
                () -> assertTrue(Options.needsMultiline("\\x"), "a leading backslash needs multiline"),
                () -> assertTrue(Options.needsMultiline(" x"), "a leading space needs multiline"),
                () -> assertTrue(Options.needsMultiline("x\t"), "a trailing tab needs multiline"),
                () -> assertFalse(Options.needsMultiline("x\u000by"), "an inner vertical tab is fine"),
                () -> assertTrue(Options.needsMultiline("a#b"), "'#' needs multiline"),
                () -> assertTrue(Options.needsMultiline("#a"), "a leading '#' needs multiline"),
                () -> assertTrue(Options.needsMultiline("a\rb"), "CR needs multiline"),
                () -> assertTrue(Options.needsMultiline("a\nb"), "LF needs multiline"),
                () -> assertTrue(Options.needsMultiline("x\f"), "a trailing form feed needs multiline"),
                () -> assertTrue(Options.needsMultiline("\u000bx"), "a leading vertical tab needs multiline"));
        }

        @Test
        @DisplayName("\\noeol: only trims a multiline value's final newline")
        void noeol() {
            Options o = parse("mapName: \\noeol: \\multiline: E\nabc\nE\nmapAuthor: \\noeol: plain\n"
                + "robotRealName: \\noeol: \\multiline: E\nend");
            assertAll(
                () -> assertEquals("abc", o.getString(Opt.MAP_NAME), "drops exactly one final newline"),
                () -> assertEquals("plain", o.getString(Opt.MAP_AUTHOR), "without multiline \\noeol: is just removed"),
                () -> assertEquals("end", o.getString(Opt.ROBOT_REAL_NAME), "a value ending at EOF keeps its text"));
        }
    }
}
