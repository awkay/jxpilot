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

import com.fulcrologic.jxpilot.client.ClientMain;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Main class of the release client jar ({@code java -jar jxpilot-client-VERSION.jar ...}).
 * <p>
 * On macOS GLFW must run on the process's first thread, which needs the JVM flag
 * {@code -XstartOnFirstThread}, and a jar manifest cannot set JVM flags. So when it is missing this
 * launcher re-executes the same java binary with the same class path, JVM arguments and program
 * arguments plus {@code -XstartOnFirstThread} and the recommended flags, shares its terminal with the
 * child and exits with the child's status. Everywhere else, and in the child, it calls
 * {@link ClientMain#main} directly. Decisions are in {@link LaunchPlan}; this class is the effectful edge.
 * <p>
 * {@code JXPILOT_NO_RELAUNCH=1} disables the re-execution.
 */
public final class ClientLauncher {
    private ClientLauncher() {}

    public static void main(String[] args) throws Exception {
        long pid = ProcessHandle.current().pid();
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        Map<String, String> env = System.getenv();
        switch (LaunchPlan.decide(System.getProperty("os.name"), jvmArgs, env, pid)) {
            case RUN -> ClientMain.main(args);
            case RUN_AFTER_RELAUNCH -> {
                System.err.println("jxpilot: still not on the first thread after relaunching with "
                    + LaunchPlan.FIRST_THREAD + "; starting anyway");
                ClientMain.main(args);
            }
            case RELAUNCH -> {
                List<String> cmd = LaunchPlan.childCommand(System.getProperty("java.home"), jvmArgs,
                    System.getProperty("java.class.path"), ClientLauncher.class.getName(), Arrays.asList(args));
                int status;
                try {
                    status = relaunch(cmd);
                } catch (IOException e) {
                    System.err.println("jxpilot: could not relaunch with " + LaunchPlan.FIRST_THREAD + " (" + e
                        + "); starting in this JVM. If the window fails to open, run: java "
                        + LaunchPlan.FIRST_THREAD + " -jar <jar> ...");
                    ClientMain.main(args);
                    return;
                }
                System.exit(status);
            }
        }
    }

    /** Run the child with this process's stdin/stdout/stderr and wait for it; the child dies with this JVM. */
    private static int relaunch(List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        pb.environment().put(LaunchPlan.ENV_RELAUNCHED, "1");
        Process child = pb.start();
        Thread reaper = new Thread(child::destroy, "jxpilot-relaunch-reaper");
        Runtime.getRuntime().addShutdownHook(reaper);
        int status = child.waitFor();
        try {
            Runtime.getRuntime().removeShutdownHook(reaper);
        } catch (IllegalStateException alreadyShuttingDown) {
            // the hook is running and has already stopped the child
        }
        return status;
    }
}
