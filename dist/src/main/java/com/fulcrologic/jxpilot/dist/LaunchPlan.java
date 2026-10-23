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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure decision logic of {@link ClientLauncher}: whether the client can run in this JVM or must be
 * re-executed with {@code -XstartOnFirstThread} (GLFW on macOS must own the process's first thread,
 * and an executable jar cannot set JVM flags), and the command line of that child JVM.
 */
public final class LaunchPlan {
    /** The JVM flag macOS GLFW needs. */
    public static final String FIRST_THREAD = "-XstartOnFirstThread";
    /** Set (to anything but 0/false/no/off) to never re-execute. */
    public static final String ENV_NO_RELAUNCH = "JXPILOT_NO_RELAUNCH";
    /** Set by the launcher in the child's environment; a child never re-executes again. */
    public static final String ENV_RELAUNCHED = "JXPILOT_RELAUNCHED";
    /** Recommended collector, added unless the user picked a collector. */
    public static final String ZGC = "-XX:+UseZGC";
    /** Lets LWJGL call native code without JDK warnings, added unless the user set native access. */
    public static final String NATIVE_ACCESS = "--enable-native-access=ALL-UNNAMED";
    /** Silences the JDK's sun.misc.Unsafe deprecation warning for LWJGL, added unless the user set the mode. */
    public static final String UNSAFE_ACCESS = "--sun-misc-unsafe-memory-access=allow";

    /** What the launcher does. */
    public enum Action {
        /** Run the client in this JVM. */
        RUN,
        /** Re-execute with {@code -XstartOnFirstThread}. */
        RELAUNCH,
        /**
         * This JVM is already the re-executed child but still is not on the first thread (the flag
         * had no effect): run anyway with a warning rather than loop.
         */
        RUN_AFTER_RELAUNCH
    }

    private LaunchPlan() {}

    /**
     * Decide how to start the client.
     * <p>
     * BEHAVIOR: on anything but macOS, RUN.
     * BEHAVIOR: on macOS, RUN when the JVM was started on the first thread (see {@link #startedOnFirstThread}).
     * BEHAVIOR: on macOS off the first thread, RUN when {@link #ENV_NO_RELAUNCH} is truthy, RUN_AFTER_RELAUNCH
     * when {@link #ENV_RELAUNCHED} is present (no relaunch loops), otherwise RELAUNCH.
     *
     * @param osName  the {@code os.name} system property
     * @param jvmArgs the JVM's input arguments (RuntimeMXBean)
     * @param env     the process environment
     * @param pid     this process's id
     */
    public static Action decide(String osName, List<String> jvmArgs, Map<String, String> env, long pid) {
        if (!isMac(osName) || startedOnFirstThread(jvmArgs, env, pid)) {
            return Action.RUN;
        }
        if (truthy(env.get(ENV_NO_RELAUNCH))) {
            return Action.RUN;
        }
        if (env.containsKey(ENV_RELAUNCHED)) {
            return Action.RUN_AFTER_RELAUNCH;
        }
        return Action.RELAUNCH;
    }

    /** BEHAVIOR: true iff {@code os.name} starts with "mac" or contains "darwin", ignoring case; null is false. */
    public static boolean isMac(String osName) {
        if (osName == null) {
            return false;
        }
        String o = osName.toLowerCase(Locale.ROOT);
        return o.startsWith("mac") || o.contains("darwin");
    }

    /**
     * BEHAVIOR: true iff the JVM arguments contain {@link #FIRST_THREAD}, or the macOS java launcher
     * marked this process with {@code JAVA_STARTED_ON_FIRST_THREAD_<pid>=1} (also set by jpackage launchers).
     */
    public static boolean startedOnFirstThread(List<String> jvmArgs, Map<String, String> env, long pid) {
        return jvmArgs.contains(FIRST_THREAD) || "1".equals(env.get("JAVA_STARTED_ON_FIRST_THREAD_" + pid));
    }

    /** BEHAVIOR: null or blank is false; 0, false, no and off (any case, trimmed) are false; anything else true. */
    public static boolean truthy(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        String t = v.strip().toLowerCase(Locale.ROOT);
        return !(t.equals("0") || t.equals("false") || t.equals("no") || t.equals("off"));
    }

    /**
     * The child JVM's command line: {@code <javaHome>/bin/java -XstartOnFirstThread <jvm args>
     * <recommended flags> -cp <classPath> <mainClass> <args>}.
     * <p>
     * BEHAVIOR: keeps the parent's JVM arguments in order, except debugger agents ({@code -agentlib:jdwp},
     * {@code -Xrunjdwp}), whose listen port the still-running parent holds, and duplicates of the
     * first-thread flag.
     * BEHAVIOR: adds {@link #ZGC} unless an argument already selects a collector ({@code -XX:+Use...GC}).
     * BEHAVIOR: adds {@link #NATIVE_ACCESS} unless an argument already starts with {@code --enable-native-access}.
     * BEHAVIOR: adds {@link #UNSAFE_ACCESS} unless an argument already starts with
     * {@code --sun-misc-unsafe-memory-access}.
     * BEHAVIOR: the program arguments follow the main class unchanged.
     */
    public static List<String> childCommand(String javaHome, List<String> jvmArgs, String classPath,
                                            String mainClass, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaHome + "/bin/java");
        cmd.add(FIRST_THREAD);
        boolean gc = false;
        boolean nativeAccess = false;
        boolean unsafeAccess = false;
        for (String a : jvmArgs) {
            if (a.equals(FIRST_THREAD) || a.startsWith("-agentlib:jdwp") || a.startsWith("-Xrunjdwp")) {
                continue;
            }
            gc |= a.startsWith("-XX:+Use") && a.endsWith("GC");
            nativeAccess |= a.startsWith("--enable-native-access");
            unsafeAccess |= a.startsWith("--sun-misc-unsafe-memory-access");
            cmd.add(a);
        }
        if (!gc) {
            cmd.add(ZGC);
        }
        if (!nativeAccess) {
            cmd.add(NATIVE_ACCESS);
        }
        if (!unsafeAccess) {
            cmd.add(UNSAFE_ACCESS);
        }
        cmd.add("-cp");
        cmd.add(classPath);
        cmd.add(mainClass);
        cmd.addAll(args);
        return cmd;
    }
}
