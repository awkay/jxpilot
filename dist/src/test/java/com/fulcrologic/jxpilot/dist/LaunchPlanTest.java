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
package com.fulcrologic.jxpilot.dist;

import com.fulcrologic.jxpilot.dist.LaunchPlan.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchPlanTest {
    private static final long PID = 4242;
    private static final String MAC = "Mac OS X";

    @Nested
    @DisplayName("decide")
    class Decide {
        @Test
        @DisplayName("runs directly off macOS, on the first thread, or when disabled; relaunches once otherwise")
        void decisions() {
            assertAll(
                () -> assertEquals(Action.RUN, LaunchPlan.decide("Linux", List.of(), Map.of(), PID),
                    "runs directly on Linux"),
                () -> assertEquals(Action.RUN, LaunchPlan.decide("Windows 11", List.of(), Map.of(), PID),
                    "runs directly on Windows"),
                () -> assertEquals(Action.RELAUNCH, LaunchPlan.decide(MAC, List.of("-Xmx1g"), Map.of(), PID),
                    "relaunches on macOS when the JVM is not on the first thread"),
                () -> assertEquals(Action.RUN, LaunchPlan.decide(MAC, List.of("-XstartOnFirstThread"), Map.of(), PID),
                    "runs directly on macOS when the flag is among the JVM arguments"),
                () -> assertEquals(Action.RUN,
                    LaunchPlan.decide(MAC, List.of(), Map.of("JAVA_STARTED_ON_FIRST_THREAD_4242", "1"), PID),
                    "runs directly on macOS when the java launcher marked this pid as started on the first thread"),
                () -> assertEquals(Action.RUN, LaunchPlan.decide(MAC, List.of(), Map.of("JXPILOT_NO_RELAUNCH", "1"), PID),
                    "runs directly on macOS when relaunching is disabled"),
                () -> assertEquals(Action.RELAUNCH,
                    LaunchPlan.decide(MAC, List.of(), Map.of("JXPILOT_NO_RELAUNCH", "false"), PID),
                    "a false JXPILOT_NO_RELAUNCH does not disable relaunching"),
                () -> assertEquals(Action.RUN_AFTER_RELAUNCH,
                    LaunchPlan.decide(MAC, List.of(), Map.of("JXPILOT_RELAUNCHED", "1"), PID),
                    "a relaunched child that is still off the first thread runs instead of relaunching again"),
                () -> assertEquals(Action.RUN,
                    LaunchPlan.decide(MAC, List.of("-XstartOnFirstThread"), Map.of("JXPILOT_RELAUNCHED", "1"), PID),
                    "a relaunched child on the first thread runs normally"));
        }
    }

    @Nested
    @DisplayName("isMac, startedOnFirstThread and truthy")
    class Predicates {
        @Test
        void predicates() {
            assertAll(
                () -> assertTrue(LaunchPlan.isMac("Mac OS X"), "Mac OS X is macOS"),
                () -> assertTrue(LaunchPlan.isMac("macOS"), "macOS is macOS, any case"),
                () -> assertTrue(LaunchPlan.isMac("Darwin"), "Darwin is macOS"),
                () -> assertFalse(LaunchPlan.isMac("Linux"), "Linux is not macOS"),
                () -> assertFalse(LaunchPlan.isMac(null), "a missing os.name is not macOS"),
                () -> assertFalse(LaunchPlan.startedOnFirstThread(List.of(),
                        Map.of("JAVA_STARTED_ON_FIRST_THREAD_7", "1"), PID),
                    "another pid's first-thread marker does not count"),
                () -> assertFalse(LaunchPlan.startedOnFirstThread(List.of(),
                        Map.of("JAVA_STARTED_ON_FIRST_THREAD_4242", "0"), PID),
                    "only the marker value 1 counts"),
                () -> assertTrue(LaunchPlan.truthy("1"), "1 is true"),
                () -> assertTrue(LaunchPlan.truthy("yes"), "yes is true"),
                () -> assertFalse(LaunchPlan.truthy(null), "absent is false"),
                () -> assertFalse(LaunchPlan.truthy("  "), "blank is false"),
                () -> assertFalse(LaunchPlan.truthy(" OFF "), "off is false, trimmed and any case"),
                () -> assertFalse(LaunchPlan.truthy("0"), "0 is false"),
                () -> assertFalse(LaunchPlan.truthy("No"), "no is false"),
                () -> assertFalse(LaunchPlan.truthy("FALSE"), "false is false"));
        }
    }

    @Nested
    @DisplayName("childCommand")
    class ChildCommand {
        @Test
        @DisplayName("same java, class path and arguments plus the first-thread and recommended flags")
        void plainParent() {
            assertEquals(
                List.of("/jdk/bin/java", "-XstartOnFirstThread", "-Xmx512m", "-XX:+UseZGC",
                    "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-cp", "client.jar", "Main", "--view-map", "a b.xp"),
                LaunchPlan.childCommand("/jdk", List.of("-Xmx512m"), "client.jar", "Main", List.of("--view-map", "a b.xp")),
                "builds java -XstartOnFirstThread, the parent's JVM args, ZGC, native and unsafe access, -cp, main and args");
        }

        @Test
        @DisplayName("respects the user's collector and native access, drops debugger agents and duplicates")
        void userFlags() {
            List<String> cmd = LaunchPlan.childCommand("/jdk",
                List.of("-XX:+UseG1GC", "--enable-native-access=org.lwjgl", "-agentlib:jdwp=transport=dt_socket",
                    "-Xrunjdwp:server=y", "-XstartOnFirstThread", "-Dx=1", "--sun-misc-unsafe-memory-access=warn"),
                "cp", "Main", List.of());
            assertAll(
                () -> assertEquals(List.of("/jdk/bin/java", "-XstartOnFirstThread", "-XX:+UseG1GC",
                        "--enable-native-access=org.lwjgl", "-Dx=1", "--sun-misc-unsafe-memory-access=warn", "-cp", "cp", "Main"),
                    cmd, "keeps the chosen GC, native and unsafe access, adds no defaults for them, drops jdwp agents "
                        + "and the duplicate flag"),
                () -> assertEquals(1, cmd.stream().filter("-XstartOnFirstThread"::equals).count(),
                    "the first-thread flag appears exactly once"));
        }

        @Test
        @DisplayName("an option that merely mentions GC is not a collector choice")
        void notACollector() {
            assertEquals(List.of("/j/bin/java", "-XstartOnFirstThread", "-XX:+PrintGC", "-XX:UseFooGC",
                    "-XX:+UseZGC", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-cp", "c", "M"),
                LaunchPlan.childCommand("/j", List.of("-XX:+PrintGC", "-XX:UseFooGC"), "c", "M", List.of()),
                "adds ZGC when no -XX:+Use...GC flag is present");
        }
    }
}
